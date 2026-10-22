package com.robo.handsfree

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class AssistantState { OFF, LISTENING, THINKING, SPEAKING }

data class ChatLine(
    val fromUser: Boolean,
    val text: String,
    val time: Long = System.currentTimeMillis()
)

/** One tiny state hub so the voice service and the screen always agree. */
object Robo {

    private val _state = MutableStateFlow(AssistantState.OFF)
    val state: StateFlow<AssistantState> = _state

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat

    @Volatile
    var voiceService: VoiceService? = null

    fun setState(value: AssistantState) {
        _state.value = value
    }

    fun say(text: String, fromUser: Boolean = false) {
        if (text.isBlank()) return
        _chat.update { lines -> (lines + ChatLine(fromUser, text.trim())).takeLast(80) }
    }

    fun clearChat() {
        _chat.value = emptyList()
    }
}
