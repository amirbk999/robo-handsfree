package com.robo.handsfree

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.robo.handsfree.core.LanguagePacks
import com.robo.handsfree.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: RoboSettings
    private lateinit var skills: SkillStore
    private lateinit var engine: CommandEngine

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startAssistant()
        } else {
            toast("Robo needs the microphone to hear you.")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshSetup() }

    private val phonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = RoboSettings(this)
        skills = SkillStore(this)
        engine = CommandEngine(this)

        if (Robo.chat.value.isEmpty()) {
            Robo.say(
                "Hi, I'm Robo. Tap the mic once, then just talk — English, Hindi, Spanish, " +
                    "Arabic, Chinese, or any of the 44 languages in Voice settings. I can open apps, " +
                    "call or message people, set alarms and reminders, keep notes, and control the " +
                    "screen while your hands are busy."
            )
        }

        binding.examplesText.text = EXAMPLES
        binding.micButton.setOnClickListener { onMicPressed() }
        binding.sendButton.setOnClickListener { sendTyped() }
        binding.inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        binding.setupButton.setOnClickListener { refreshSetup() }
        binding.clearSkillsButton.setOnClickListener {
            skills.clearLearned()
            renderSkills()
            toast("Learned commands cleared.")
        }

        bindVoiceSettings()
        buildQuickActions()
        renderSkills()
        refreshSetup()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Robo.state.collect { renderState(it) } }
                launch { Robo.chat.collect { renderChat(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        syncBubble()
    }

    // --------------------------------------------------------------- actions

    private fun onMicPressed() {
        val service = Robo.voiceService
        if (service == null) {
            if (Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
                startAssistant()
            } else {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (Robo.state.value == AssistantState.OFF) {
            service.resumeListening()
        } else {
            service.pauseListening()
        }
    }

    private fun startAssistant() {
        VoiceService.start(this)
        if (!Permissions.accessibilityEnabled(this)) {
            toast("Turn on Robo's accessibility service so I can touch the screen for you.")
        }
        syncBubble()
    }

    private fun sendTyped() {
        val text = binding.inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.inputText.setText("")
        runText(text)
    }

    private fun runText(text: String) {
        lifecycleScope.launch {
            val reply = engine.handle(text)
            if (settings.speakReplies) {
                Robo.voiceService?.speakAloud(reply)
            }
            renderSkills()
        }
    }

    // ------------------------------------------------------------- rendering

    private fun renderState(state: AssistantState) {
        val (label, hint, active) = when (state) {
            AssistantState.OFF -> Triple(
                getString(R.string.status_paused),
                "Tap the mic once, then talk. No touching after that.",
                false
            )
            AssistantState.LISTENING -> Triple(
                getString(R.string.status_listening),
                "Say “Robo, open WhatsApp” or “Robo, call Aunty”.",
                true
            )
            AssistantState.THINKING -> Triple(
                getString(R.string.status_thinking),
                "One moment…",
                true
            )
            AssistantState.SPEAKING -> Triple(
                getString(R.string.status_speaking),
                "I'll listen again as soon as I finish.",
                true
            )
        }
        binding.statusText.text = label
        binding.hintText.text = hint
        binding.statusDot.setBackgroundResource(
            if (active) R.drawable.bg_dot_active else R.drawable.bg_dot_idle
        )
        binding.micButton.alpha = if (active) 1f else 0.85f
        binding.micButton.contentDescription =
            if (active) getString(R.string.mic_stop) else getString(R.string.mic_start)
    }

    private fun renderChat(lines: List<ChatLine>) {
        binding.chatContainer.removeAllViews()
        if (lines.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No conversation yet."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            }
            binding.chatContainer.addView(empty)
            return
        }
        lines.forEach { line ->
            binding.chatContainer.addView(chatRow(line))
        }
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatRow(line: ChatLine): View {
        val bubble = TextView(this).apply {
            text = line.text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (line.fromUser) R.color.bubble_user else R.color.bubble_robo
                    )
                )
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (line.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
            addView(bubble)
        }
    }

    private fun buildQuickActions() {
        val actions = listOf(
            "Open WhatsApp" to "open WhatsApp",
            "Alarm 7 am" to "set an alarm for 7 am",
            "Read screen" to "read the screen",
            "Battery" to "what's the battery",
            "Joke" to "tell me a joke",
            "Notes" to "read my notes"
        )
        binding.quickChips.removeAllViews()
        actions.forEach { (label, command) ->
            val chip = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                isClickable = true
                setOnClickListener { runText(command) }
            }
            binding.quickChips.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
    }

    private fun renderSkills() {
        val learned = skills.learned()
        binding.skillsEmpty.visibility = if (learned.isEmpty()) View.VISIBLE else View.GONE
        binding.learnedContainer.removeAllViews()

        learned.toList().sortedBy { it.first }.forEach { (phrase, action) ->
            val row = TextView(this).apply {
                text = "\"$phrase\"  →  $action"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13.5f
                setPadding(0, dp(8), 0, dp(8))
                setOnLongClickListener {
                    skills.forget(phrase)
                    renderSkills()
                    toast("Forgot \"$phrase\".")
                    true
                }
            }
            binding.learnedContainer.addView(row)
        }
    }

    private fun bindVoiceSettings() {
        bindLanguagePicker()

        binding.wakeWordSwitch.isChecked = settings.wakeWordOnly
        binding.wakeWordSwitch.setOnCheckedChangeListener { _, checked ->
            settings.wakeWordOnly = checked
        }

        binding.speakSwitch.isChecked = settings.speakReplies
        binding.speakSwitch.setOnCheckedChangeListener { _, checked ->
            settings.speakReplies = checked
        }

        binding.rateSeek.progress = settings.speechRateStep
        binding.rateSeek.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) settings.speechRateStep = progress
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            }
        )

        binding.userNameInput.setText(settings.userName)
        binding.userNameInput.doAfterTextChanged { editable ->
            settings.userName = editable?.toString().orEmpty()
        }

        binding.bubbleSwitch.isChecked = settings.showBubble
        binding.bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !Permissions.canDrawOverlays(this)) {
                binding.bubbleSwitch.isChecked = false
                toast("Allow “display over other apps”, then switch the bubble on.")
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnCheckedChangeListener
            }
            settings.showBubble = checked
            syncBubble()
        }
    }

    /** Lets the user pick any of the languages Robo understands and speaks. */
    private fun bindLanguagePicker() {
        val tags = mutableListOf("auto")
        val labels = mutableListOf("Automatic — my phone's language")
        LanguagePacks.spokenLanguages.forEach { pack ->
            tags += pack.tag
            labels += "${pack.nativeName} · ${pack.englishName}"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter
        binding.languageSpinner.setSelection(tags.indexOf(settings.languageTag).coerceAtLeast(0), false)

        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = tags[position]
                if (tag == settings.languageTag) return
                settings.languageTag = tag
                val name = if (tag == "auto") "your phone's language"
                else labels[position].substringBefore(" · ")
                toast("Robo will listen and answer in $name.")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ---------------------------------------------------------------- setup

    private fun refreshSetup() {
        binding.setupContainer.removeAllViews()

        val mic = Permissions.has(this, Manifest.permission.RECORD_AUDIO)
        binding.setupContainer.addView(
            setupRow("Microphone", "Lets me hear you. Required.", mic) {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        val phone = Permissions.has(this, Manifest.permission.CALL_PHONE) &&
            Permissions.has(this, Manifest.permission.READ_CONTACTS) &&
            Permissions.has(this, Manifest.permission.SEND_SMS)
        binding.setupContainer.addView(
            setupRow("Calls, contacts and SMS", "Needed to call or text by name.", phone) {
                phonePermissions.launch(
                    arrayOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.SEND_SMS
                    )
                )
            }
        )

        val accessibility = Permissions.accessibilityEnabled(this)
        binding.setupContainer.addView(
            setupRow(
                "Accessibility service",
                "Lets me tap, type, scroll and read the screen for you.",
                accessibility
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Choose Robo hands-free control, then turn it on.")
            }
        )

        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            Permissions.has(this, Manifest.permission.POST_NOTIFICATIONS)
        binding.setupContainer.addView(
            setupRow("Notifications", "Shows when I'm listening.", notifications) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        val overlay = Permissions.canDrawOverlays(this)
        binding.setupContainer.addView(
            setupRow("Floating bubble", "Wake me without opening the app.", overlay) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )

        val power = getSystemService(PowerManager::class.java)
        val battery = power?.isIgnoringBatteryOptimizations(packageName) == true
        binding.setupContainer.addView(
            setupRow("Battery freedom", "Stops Android silencing me in the background.", battery) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                toast("Find Robo and choose “Don't optimise”.")
            }
        )
    }

    private fun setupRow(title: String, detail: String, ok: Boolean, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            setOnClickListener { onClick() }
        }
        container.addView(
            TextView(this).apply {
                text = (if (ok) "✓  " else "✕  ") + title
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (ok) R.color.ok else R.color.warn
                    )
                )
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = detail
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(20), dp(2), 0, 0)
            }
        )
        return container
    }

    private fun syncBubble() {
        if (settings.showBubble && Permissions.canDrawOverlays(this)) {
            startService(Intent(this, BubbleService::class.java))
        } else {
            stopService(Intent(this, BubbleService::class.java))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val EXAMPLES = """
            • Open WhatsApp · YouTube · Camera
            • Call Mom · message Priya saying I'm on my way
            • Set an alarm for 7 am · set a timer for 10 minutes
            • Remind me to take medicine at 9 pm
            • Take a note … · read my notes
            • Read the screen · scroll down · tap Send · type hello
            • Turn on Wi-Fi · Bluetooth · flashlight · brightness up
            • Search best chai near me · navigate to India Gate
            • When I say music, open Spotify
            • Or just talk: how are you, tell me a joke
            • Other languages: «abre WhatsApp» · «öffne YouTube» ·
              «llama a mamá» · «व्हाट्सएप खोलो» · «打开相机» · «WhatsApp aç»

            44 languages are understood. The microphone stays on your phone.
        """.trimIndent()
    }
}
