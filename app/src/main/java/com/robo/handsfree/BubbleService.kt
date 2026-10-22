package com.robo.handsfree

import android.Manifest
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import android.app.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A small floating mic that stays on top of other apps. Tap it to wake or pause Robo,
 * drag it anywhere along the edge.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: ImageView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var dragged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
        scope.launch {
            Robo.state.collect { state ->
                bubble?.alpha = when (state) {
                    AssistantState.LISTENING, AssistantState.SPEAKING, AssistantState.THINKING -> 1f
                    AssistantState.OFF -> 0.55f
                }
            }
        }
    }

    private fun showBubble() {
        val size = dp(62)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = ContextCompat.getDrawable(this@BubbleService, R.drawable.bg_mic_button)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            contentDescription = "Robo"
            alpha = 0.6f
            setOnTouchListener(touchListener)
        }

        layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = resources.displayMetrics.heightPixels / 2
        }

        try {
            windowManager.addView(view, layoutParams)
            bubble = view
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private val touchListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = layoutParams.x
                startY = layoutParams.y
                touchX = event.rawX
                touchY = event.rawY
                dragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) dragged = true
                layoutParams.x = startX + dx
                layoutParams.y = startY + dy
                try {
                    windowManager.updateViewLayout(view, layoutParams)
                } catch (t: Throwable) {
                    // View already detached.
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) toggleListening()
                true
            }
            else -> false
        }
    }

    private fun toggleListening() {
        val service = Robo.voiceService
        if (service == null) {
            if (Permissions.has(this, Manifest.permission.RECORD_AUDIO)) {
                VoiceService.start(this)
            } else {
                openApp()
            }
            return
        }
        if (Robo.state.value == AssistantState.OFF) {
            service.resumeListening()
        } else {
            service.pauseListening()
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // Already removed.
            }
        }
        bubble = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
