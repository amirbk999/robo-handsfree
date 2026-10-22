package com.robo.handsfree.core

enum class MessageChannel { SMS, WHATSAPP }

enum class MediaAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS }

enum class VolumeAction { UP, DOWN, MUTE, UNMUTE, SET }

enum class ScrollDirection { UP, DOWN }

enum class ToggleDirection { ON, OFF, TOGGLE }

enum class PhoneSetting { WIFI, BLUETOOTH, AIRPLANE, MOBILE_DATA }

/** Everything Robo can understand, expressed without any Android dependency. */
sealed interface Command {
    data class OpenApp(val app: String) : Command
    data class OpenSettings(val page: String?) : Command
    data class Call(val contact: String) : Command
    data class Message(val contact: String, val body: String, val channel: MessageChannel) : Command
    data class SetAlarm(val hour: Int?, val minute: Int?, val label: String) : Command
    data class SetTimer(val seconds: Int) : Command
    data class SetReminder(val text: String, val atMillis: Long) : Command
    data class AddNote(val text: String) : Command
    data object ReadNotes : Command
    data class Flashlight(val direction: ToggleDirection) : Command
    data class Volume(val action: VolumeAction, val level: Int? = null) : Command
    data class Media(val action: MediaAction) : Command
    data class Brightness(val percent: Int?, val up: Boolean?) : Command
    data class Scroll(val direction: ScrollDirection) : Command
    data object Back : Command
    data object Home : Command
    data object Recents : Command
    data object OpenNotifications : Command
    data object OpenQuickSettings : Command
    data object LockScreen : Command
    data class Tap(val target: String) : Command
    data class TypeText(val text: String) : Command
    data object ReadScreen : Command
    data class Search(val query: String, val onYoutube: Boolean = false) : Command
    data class Navigate(val place: String) : Command
    data object BatteryStatus : Command
    data object TimeNow : Command
    data object DateNow : Command
    data class ToggleSetting(val setting: PhoneSetting, val direction: ToggleDirection) : Command
    data object Camera : Command
    data object Screenshot : Command
    data class Learn(val phrase: String, val action: String) : Command
    data class Forget(val phrase: String) : Command
    data object Help : Command
    data object StopListening : Command
    data object StartListening : Command
    data class Chat(val text: String) : Command
    data class Unknown(val text: String) : Command
}
