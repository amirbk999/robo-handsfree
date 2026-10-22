package com.robo.handsfree

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySmokeTest {

    @Test
    fun `main screen inflates with mic, checklist, chips and transcript`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertNotNull(activity.findViewById<View>(R.id.micButton))
        assertNotNull(activity.findViewById<View>(R.id.sendButton))
        assertNotNull(activity.findViewById<View>(R.id.wakeWordSwitch))

        val setup = activity.findViewById<LinearLayout>(R.id.setupContainer)
        assertEquals(6, setup.childCount)

        val chips = activity.findViewById<LinearLayout>(R.id.quickChips)
        assertEquals(6, chips.childCount)

        val chat = activity.findViewById<LinearLayout>(R.id.chatContainer)
        assertTrue("Robo should greet on first open", chat.childCount >= 1)

        val status = activity.findViewById<TextView>(R.id.statusText)
        assertTrue(status.text.isNotBlank())

        val examples = activity.findViewById<TextView>(R.id.examplesText)
        assertTrue(examples.text.contains("Open WhatsApp"))
    }

    @Test
    fun `typed command is logged and answered in the transcript`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val engine = CommandEngine(activity)
        val reply = kotlinx.coroutines.runBlocking { engine.handle("what's the battery") }

        assertTrue(reply.isNotBlank())
        val lines = Robo.chat.value
        assertTrue(lines.any { it.fromUser && it.text == "what's the battery" })
        assertTrue(lines.last().text == reply)
    }
}
