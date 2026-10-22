package com.robo.handsfree

import android.content.Context
import com.robo.handsfree.core.localeTagOf

class RoboSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("robo_settings", Context.MODE_PRIVATE)

    var wakeWordOnly: Boolean
        get() = prefs.getBoolean(KEY_WAKE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WAKE, value).apply()
        }

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPEAK, value).apply()
        }

    var userName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim()).apply()
        }

    var showBubble: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BUBBLE, value).apply()
        }

    var speechRateStep: Int
        get() = prefs.getInt(KEY_RATE, 5)
        set(value) {
            prefs.edit().putInt(KEY_RATE, value.coerceIn(0, 10)).apply()
        }

    /** "auto" listens in the phone's own language; otherwise a BCP-47 tag such as hi-IN. */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, "auto").orEmpty().ifBlank { "auto" }
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.ifBlank { "auto" }).apply()
        }

    /** Concrete BCP-47 tag handed to speech recognition and text-to-speech. */
    val effectiveLanguageTag: String
        get() = localeTagOf(languageTag)

    /** TextToSpeech wants roughly 0.5 – 1.5 for natural speech. */
    val speechRate: Float
        get() = 0.6f + speechRateStep * 0.08f

    private companion object {
        const val KEY_WAKE = "wake_word_only"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_NAME = "user_name"
        const val KEY_BUBBLE = "show_bubble"
        const val KEY_RATE = "speech_rate"
        const val KEY_LANGUAGE = "language_tag"
    }
}
