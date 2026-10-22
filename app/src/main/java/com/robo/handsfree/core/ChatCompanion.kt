package com.robo.handsfree.core

import java.util.Calendar
import java.util.Locale

/**
 * The "friend" side of Robo: small talk, encouragement and honest limits.
 * Everything is local — no audio or text leaves the phone.
 */
class ChatCompanion(private val userNameProvider: () -> String = { "friend" }) {

    private val jokes = listOf(
        "Why did the phone go to therapy? It had too many unresolved apps.",
        "I asked my charger how it was doing. It said it was feeling drained.",
        "My Wi-Fi and I have trust issues. It keeps saying “connecting”.",
        "A robot walked into a café and said: “One coffee, no beans, I run on code.”"
    )

    fun reply(text: String, now: Long = System.currentTimeMillis()): String {
        val t = text.lowercase(Locale.US).trim().trimEnd('.', '!', '?')
        val name = userNameProvider().ifBlank { "friend" }
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

        return when {
            t.isEmpty() -> "Yes, I'm here. What do you need?"

            Regex("\\b(thank you|thanks|thank u|thx)\\b").containsMatchIn(t) ->
                "Anytime, $name. That's what I'm here for."

            Regex("^(hi|hello|hey|hii|yo|namaste|good morning|good afternoon|good evening)\\b").containsMatchIn(t) ->
                greeting(hour, name)

            Regex("\\bhow (are|r) (you|u)\\b").containsMatchIn(t) || t == "how are you doing" ->
                "I'm good and fully charged. More importantly, how are you doing?"

            Regex("\\b(who are you|what('s| is) your name|your name)\\b").containsMatchIn(t) ->
                "I'm Robo — a voice assistant that lives on your phone. Tap once, then I'm your hands."

            Regex("\\b(are you (real|human|alive)|are you a robot|do you sleep)\\b").containsMatchIn(t) ->
                "I'm software, not a person. But I do listen carefully, and I never get bored of you talking."

            Regex("\\b(what can you do|your features|how do i use you|what do you do)\\b").containsMatchIn(t) ->
                "I can open apps, call or message people, set alarms and reminders, keep notes, control sound and brightness, tap and type on screen, read the screen to you, and just talk when you want company."

            Regex("\\b(tell me a joke|joke|make me laugh)\\b").containsMatchIn(t) ->
                jokes.random()

            Regex("\\b(sad|depressed|upset|lonely|alone|crying|anxious|stressed)\\b").containsMatchIn(t) ->
                "I'm sorry, $name. That sounds heavy. Want me to call someone you trust, or should I put on some music? Say “call Amma” or “play music”."

            Regex("\\b(tired|exhausted|sleepy|can't sleep|insomnia)\\b").containsMatchIn(t) ->
                "You've done enough for today. Say “set an alarm for 7 am” and let yourself rest."

            Regex("\\b(i('m| am) (happy|great|excited|fine|good))\\b").containsMatchIn(t) ->
                "Love that for you, $name. Want to tell me what happened?"

            Regex("\\b(good night|goodnight|sleep well)\\b").containsMatchIn(t) ->
                "Good night, $name. I'll be right here when you wake up."

            Regex("\\b(i love you|love you)\\b").containsMatchIn(t) ->
                "That's kind of you. I'm fond of you too, in a very software way."

            Regex("\\b(sing|song|music for me)\\b").containsMatchIn(t) ->
                "I can't sing without upsetting everyone nearby — but I can play music. Say “play music”."

            Regex("\\b(story|tell me something|interesting fact)\\b").containsMatchIn(t) ->
                "Here's one: your phone has more computing power than the guidance computer that landed humans on the Moon. And now it can listen to you."

            Regex("\\b(bored|nothing to do)\\b").containsMatchIn(t) ->
                "Let's fix that. Say “play something on YouTube”, or ask me for a joke."

            Regex("\\b(what'?s up|whats up|how'?s it going)\\b").containsMatchIn(t) ->
                "Just listening, $name. What's on your mind?"

            Regex("\\b(can you learn|learn something|remember things)\\b").containsMatchIn(t) ->
                "Yes. Say “Robo, when I say music, open Spotify” and I'll remember it. Everything I learn is listed on the home screen."

            Regex("\\b(money|earn|income|invest|trade|business)\\b").containsMatchIn(t) ->
                "I can't move money, trade or earn cash for you, and I won't pretend to. What I can do is save you time — reminders, calls, messages, notes and hands-free control."

            Regex("^(ok|okay|cool|nice|great|hmm|oh|yes|no|yeah|nope)$").containsMatchIn(t) ->
                "Got it. What next, $name?"

            Regex("\\b(weather|rain|temperature|forecast)\\b").containsMatchIn(t) ->
                "I can look that up for you — say “search weather today”, and I'll open it on your screen."

            Regex("\\b(stop|cancel|never mind|nevermind)\\b").containsMatchIn(t) ->
                "Fine, I'll stay quiet. Tap the mic whenever you need me."

            else -> fallback(name)
        }
    }

    private fun greeting(hour: Int, name: String): String = when (hour) {
        in 4..11 -> "Good morning, $name. What can I do for you?"
        in 12..16 -> "Good afternoon, $name. I'm listening."
        in 17..21 -> "Good evening, $name. What do you need?"
        else -> "Hello, $name. You're up late — how can I help?"
    }

    private fun fallback(name: String): String =
        "I didn't catch a command there, $name. I'm still listening — try “open WhatsApp”, " +
            "“call Mom”, “set an alarm for 7 am”, or just keep talking to me."
}
