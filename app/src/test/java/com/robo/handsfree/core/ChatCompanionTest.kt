package com.robo.handsfree.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChatCompanionTest {

    private val companion = ChatCompanion { "Asha" }

    private val morning: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 21, 8, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `greets by name and time of day`() {
        val reply = companion.reply("hello", morning)
        assertTrue(reply.contains("Asha"))
        assertTrue(reply.contains("Good morning"))
    }

    @Test
    fun `answers how are you and asks back`() {
        val reply = companion.reply("how are you", morning)
        assertTrue(reply.contains("how are you", ignoreCase = true))
    }

    @Test
    fun `is honest about money instead of inventing earnings`() {
        val reply = companion.reply("can you earn money for me", morning)
        assertTrue(reply.contains("can't move money"))
    }

    @Test
    fun `offers a joke and a story`() {
        assertTrue(companion.reply("tell me a joke", morning).isNotBlank())
        assertTrue(companion.reply("tell me a story", morning).isNotBlank())
    }

    @Test
    fun `falls back with usable examples`() {
        val reply = companion.reply("qwerty zxcv", morning)
        assertTrue(reply.contains("open WhatsApp"))
        assertTrue(reply.contains("Asha"))
    }

    @Test
    fun `handles empty and thanks`() {
        assertEquals("Yes, I'm here. What do you need?", companion.reply("", morning))
        assertTrue(companion.reply("thanks", morning).contains("Anytime"))
    }
}

class TextUtilsTest {

    @Test
    fun `normalises case and punctuation`() {
        assertEquals("open whatsapp", normalizeText("  Open   WhatsApp! "))
    }

    @Test
    fun `matches spoken app names fuzzily`() {
        assertEquals(1.0, similarity("WhatsApp", "whatsapp"), 0.001)
        assertTrue(similarity("insta", "Instagram") > 0.5)
        assertTrue(similarity("spotify", "Spotify") > 0.9)
        assertTrue(similarity("chrome", "calculator") < 0.5)
    }
}
