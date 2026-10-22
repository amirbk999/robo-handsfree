package com.robo.handsfree

import android.content.Context
import com.robo.handsfree.core.ChatCompanion
import com.robo.handsfree.core.Command
import com.robo.handsfree.core.CommandParser

/**
 * One entry point for every kind of input: voice, typed text or a quick-action chip.
 * It logs the exchange, parses the sentence and returns the sentence to speak.
 */
class CommandEngine(context: Context) {

    private val appContext = context.applicationContext
    private val settings = RoboSettings(appContext)
    private val skills = SkillStore(appContext)
    private val runner = ActionRunner(appContext, skills)
    private val companion = ChatCompanion { settings.userName.ifBlank { "friend" } }

    suspend fun handle(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return "I'm listening."

        Robo.say(input, fromUser = true)

        val reply = try {
            val parser = CommandParser(skills.learned(), settings.languageTag)
            when (val command = parser.parse(input)) {
                is Command.Chat -> companion.reply(command.text)
                is Command.Unknown -> "I didn't quite catch that. Try “open WhatsApp” or “call Mom”."
                else -> runner.run(command)
            }
        } catch (t: Throwable) {
            "Sorry, that didn't work: ${t.message ?: "unknown error"}"
        }

        Robo.say(reply)
        return reply
    }
}
