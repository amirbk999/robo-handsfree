package com.robo.handsfree

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.robo.handsfree.core.containsWakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps the microphone warm, decides whether an utterance was meant for Robo,
 * and speaks the answer. Android only allows long-running microphones from a
 * foreground service, which is why this exists as one.
 */
class VoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private lateinit var settings: RoboSettings
    private lateinit var engine: CommandEngine

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var paused = false
    private var speaking = false
    private var destroyed = false
    private var restartAttempts = 0
    private var pendingSpeech: String? = null
    private var commandJob: Job? = null
    private var appliedVoiceLanguage: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val restartRunnable = Runnable { startListening() }

    override fun onCreate() {
        super.onCreate()
        settings = RoboSettings(this)
        engine = CommandEngine(this)
        Robo.voiceService = this
        ensureForeground("Starting…")

        if (!Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
            updateNotification("Microphone permission needed")
            stopSelf()
            return
        }

        tts = TextToSpeech(this, this).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post { finishSpeaking() }
                }

                override fun onError(utteranceId: String?) {
                    main.post { finishSpeaking() }
                }
            })
        }
        recognizer = createRecognizer()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME, ACTION_START -> {
                paused = false
                ensureForeground("Listening…")
                startListening()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!paused && !listening && !speaking) startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- listening

    private fun createRecognizer(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(this@VoiceService) }
    } catch (t: Throwable) {
        null
    }

    private fun startListening() {
        if (destroyed || paused || speaking || listening) return
        if (!Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
            updateNotification("Microphone permission needed")
            Robo.setState(AssistantState.OFF)
            return
        }

        val recognizer = recognizer ?: createRecognizer()?.also { recognizer = it } ?: run {
            scheduleRestart(1500)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.effectiveLanguageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, settings.effectiveLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }

        try {
            recognizer.startListening(intent)
            listening = true
            Robo.setState(AssistantState.LISTENING)
            updateNotification("Listening…")
        } catch (t: Throwable) {
            listening = false
            scheduleRestart(1200)
        }
    }

    private fun scheduleRestart(delayMs: Long = 350) {
        if (destroyed || paused || speaking) return
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun handleUtterance(raw: String?) {
        if (destroyed || paused) return
        if (raw.isNullOrBlank()) {
            scheduleRestart(250)
            return
        }
        if (settings.wakeWordOnly && !containsWakeWord(raw)) {
            scheduleRestart(200)
            return
        }

        listening = false
        Robo.setState(AssistantState.THINKING)
        updateNotification("Working on it…")

        commandJob?.cancel()
        commandJob = scope.launch {
            val reply = engine.handle(raw)
            if (settings.speakReplies) {
                speak(reply)
            } else {
                Robo.setState(AssistantState.LISTENING)
                scheduleRestart(400)
            }
        }
    }

    // ------------------------------------------------------------- speaking

    private fun speak(text: String) {
        if (destroyed || text.isBlank()) return
        val tts = tts
        if (tts == null || !ttsReady) {
            pendingSpeech = text
            return
        }
        applyVoiceLanguage()
        speaking = true
        Robo.setState(AssistantState.SPEAKING)
        updateNotification("Speaking…")
        listening = false
        try {
            recognizer?.cancel()
        } catch (t: Throwable) {
            // Recogniser already gone; nothing to stop.
        }
        tts.setSpeechRate(settings.speechRate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun finishSpeaking() {
        if (destroyed) return
        speaking = false
        if (paused) {
            Robo.setState(AssistantState.OFF)
        } else {
            Robo.setState(AssistantState.LISTENING)
            scheduleRestart(300)
        }
    }

    /** Keeps the spoken voice in the same language the user chose to speak. */
    private fun applyVoiceLanguage() {
        val tts = tts ?: return
        val tag = settings.effectiveLanguageTag
        if (tag == appliedVoiceLanguage) return
        val result = try {
            tts.setLanguage(Locale.forLanguageTag(tag))
        } catch (t: Throwable) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.US
        }
        appliedVoiceLanguage = tag
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyVoiceLanguage()
            ttsReady = true
            pendingSpeech?.let { text ->
                pendingSpeech = null
                speak(text)
            }
        } else {
            ttsReady = false
        }
    }

    /** Used by reminders so they can be spoken even when the screen is off. */
    fun speakAloud(text: String) {
        speak(text)
    }

    // -------------------------------------------------------- public control

    fun pauseListening() {
        paused = true
        listening = false
        speaking = false
        main.removeCallbacks(restartRunnable)
        commandJob?.cancel()
        try {
            recognizer?.cancel()
            tts?.stop()
        } catch (t: Throwable) {
            // Nothing to clean up.
        }
        Robo.setState(AssistantState.OFF)
        updateNotification("Paused — tap the mic to wake me")
    }

    fun resumeListening() {
        paused = false
        ensureForeground("Listening…")
        startListening()
    }

    // -------------------------------------------------------- recognition API

    override fun onReadyForSpeech(params: Bundle?) {
        listening = true
        Robo.setState(AssistantState.LISTENING)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        listening = false
        if (paused || speaking || destroyed) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                updateNotification("Microphone permission needed")
                Robo.setState(AssistantState.OFF)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recreateRecognizer()
                scheduleRestart(1200)
            }
            else -> {
                restartAttempts++
                scheduleRestart((300L + restartAttempts * 250L).coerceAtMost(2500L))
            }
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        restartAttempts = 0
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        handleUtterance(text)
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun recreateRecognizer() {
        try {
            recognizer?.destroy()
        } catch (t: Throwable) {
            // Already destroyed.
        }
        recognizer = createRecognizer()
    }

    // ----------------------------------------------------------- notification

    private fun ensureForeground(status: String) {
        val notification = buildNotification(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun updateNotification(status: String) {
        try {
            ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(status))
        } catch (t: Throwable) {
            // Notification permission can be revoked at any time.
        }
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RoboApp.CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (paused) "Robo is paused" else "Robo is listening")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", toggle)
            .build()
    }

    // --------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        commandJob?.cancel()
        scope.cancel()
        try {
            recognizer?.destroy()
        } catch (t: Throwable) {
            // Nothing to do.
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            // TTS never initialised.
        }
        tts = null
        Robo.voiceService = null
        Robo.setState(AssistantState.OFF)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.robo.handsfree.action.START"
        const val ACTION_PAUSE = "com.robo.handsfree.action.PAUSE"
        const val ACTION_RESUME = "com.robo.handsfree.action.RESUME"
        const val ACTION_STOP = "com.robo.handsfree.action.STOP"

        private const val NOTIFICATION_ID = 42
        private const val UTTERANCE_ID = "robo_reply"

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_PAUSE))
        }
    }
}
