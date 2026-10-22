package com.robo.handsfree.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CommandParserTest {

    private val parser = CommandParser()

    // Fixed "now": 21 Oct 2026, 10:00 local time.
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 21, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(text: String) = parser.parse(text, now)

    @Test
    fun `opens an app and keeps its capitalisation`() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("Open WhatsApp"))
        assertEquals(Command.OpenApp("Instagram"), parse("please launch Instagram"))
        assertEquals(Command.OpenApp("Spotify"), parse("hey Robo, start Spotify"))
    }

    @Test
    fun `calls a contact`() {
        assertEquals(Command.Call("Aunty"), parse("Robo, call Aunty"))
        assertEquals(Command.Call("mom"), parse("can you dial mom"))
    }

    @Test
    fun `sends sms and whatsapp messages with original casing`() {
        assertEquals(
            Command.Message("mom", "I'll be late", MessageChannel.SMS),
            parse("message mom saying I'll be late")
        )
        assertEquals(
            Command.Message("Rahul", "Reaching in 10 minutes", MessageChannel.WHATSAPP),
            parse("send a WhatsApp to Rahul saying Reaching in 10 minutes")
        )
        assertEquals(
            Command.Message("Priya", "Good morning", MessageChannel.SMS),
            parse("text Priya Good morning")
        )
    }

    @Test
    fun `sets alarms with and without minutes`() {
        assertEquals(Command.SetAlarm(7, 30, "wake up"), parse("set an alarm for 7:30 am to wake up"))
        assertEquals(Command.SetAlarm(6, 0, ""), parse("wake me up at 6 am"))
        assertEquals(Command.SetAlarm(18, 15, ""), parse("alarm at 6:15 pm"))
        assertEquals(Command.SetAlarm(null, null, ""), parse("set an alarm"))
    }

    @Test
    fun `sets timers in seconds`() {
        assertEquals(Command.SetTimer(600), parse("set a timer for 10 minutes"))
        assertEquals(Command.SetTimer(90), parse("timer 90 seconds"))
        assertEquals(Command.SetTimer(1800), parse("start a timer for half an hour"))
    }

    @Test
    fun `creates reminders at a time or in a duration`() {
        val expected = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(Command.SetReminder("take medicine", expected), parse("remind me to take medicine at 9 pm"))
        assertEquals(
            Command.SetReminder("stretch", now + 30 * 60_000L),
            parse("remind me in 30 minutes to stretch")
        )
    }

    @Test
    fun `keeps notes and reads them back`() {
        assertEquals(Command.AddNote("buy milk"), parse("take a note buy milk"))
        assertEquals(Command.AddNote("the wifi password is 1234"), parse("remember that the wifi password is 1234"))
        assertEquals(Command.ReadNotes, parse("read my notes"))
        assertEquals(Command.ReadNotes, parse("open notes"))
    }

    @Test
    fun `controls hardware`() {
        assertEquals(Command.Flashlight(ToggleDirection.ON), parse("turn on the flashlight"))
        assertEquals(Command.Flashlight(ToggleDirection.OFF), parse("torch off"))
        assertEquals(Command.Volume(VolumeAction.UP), parse("volume up"))
        assertEquals(Command.Volume(VolumeAction.DOWN), parse("turn the volume down"))
        assertEquals(Command.Volume(VolumeAction.SET, 30), parse("set volume to 30"))
        assertEquals(Command.Volume(VolumeAction.MUTE), parse("mute"))
        assertEquals(Command.Brightness(50, null), parse("set brightness to 50"))
        assertEquals(Command.Brightness(null, true), parse("brightness up"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("play music"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("next song"))
    }

    @Test
    fun `drives the screen through accessibility`() {
        assertEquals(Command.Scroll(ScrollDirection.DOWN), parse("scroll down"))
        assertEquals(Command.Scroll(ScrollDirection.UP), parse("swipe up"))
        assertEquals(Command.Back, parse("go back"))
        assertEquals(Command.Home, parse("home screen"))
        assertEquals(Command.Recents, parse("show recent apps"))
        assertEquals(Command.OpenNotifications, parse("open notifications"))
        assertEquals(Command.OpenQuickSettings, parse("open quick settings"))
        assertEquals(Command.Tap("Send"), parse("tap on Send"))
        assertEquals(Command.TypeText("hello world"), parse("type hello world"))
        assertEquals(Command.ReadScreen, parse("read the screen"))
        assertEquals(Command.Screenshot, parse("take a screenshot"))
    }

    @Test
    fun `reports device state`() {
        assertEquals(Command.BatteryStatus, parse("what's the battery"))
        assertEquals(Command.TimeNow, parse("what time is it"))
        assertEquals(Command.DateNow, parse("what's the date"))
    }

    @Test
    fun `toggles phone settings and opens the right settings page`() {
        assertEquals(Command.ToggleSetting(PhoneSetting.WIFI, ToggleDirection.ON), parse("turn on wifi"))
        assertEquals(Command.ToggleSetting(PhoneSetting.BLUETOOTH, ToggleDirection.OFF), parse("switch off bluetooth"))
        assertEquals(Command.ToggleSetting(PhoneSetting.AIRPLANE, ToggleDirection.ON), parse("turn on airplane mode"))
        assertEquals(Command.OpenSettings("wifi"), parse("open wifi settings"))
        assertEquals(Command.OpenSettings(null), parse("open settings"))
    }

    @Test
    fun `searches the web maps and youtube`() {
        assertEquals(Command.Search("best chai near me"), parse("search for best chai near me"))
        assertEquals(Command.Navigate("India Gate"), parse("navigate to India Gate"))
        assertEquals(Command.Search("lofi beats", onYoutube = true), parse("play lofi beats on youtube"))
    }

    @Test
    fun `learns and forgets custom commands`() {
        assertEquals(Command.Learn("music", "open Spotify"), parse("when I say music, open Spotify"))
        assertEquals(Command.Learn("goodnight", "lock the phone"), parse("learn that goodnight means lock the phone"))
        assertEquals(Command.Forget("music"), parse("forget music"))

        val taught = CommandParser(mapOf("music" to "open Spotify"))
        assertEquals(Command.OpenApp("Spotify"), taught.parse("music", now))
    }

    @Test
    fun `handles assistant control and chat`() {
        assertEquals(Command.Help, parse("what can you do"))
        assertEquals(Command.StopListening, parse("stop listening"))
        assertEquals(Command.StartListening, parse("wake up"))
        assertEquals(Command.Chat(""), parse("hello Robo"))
        assertTrue(parse("how are you") is Command.Chat)
        assertTrue(parse("tell me a joke") is Command.Chat)
        assertTrue(parse("blah blah nonsense") is Command.Chat)
    }

    @Test
    fun `strips wake word and polite fillers`() {
        assertEquals(Command.OpenApp("Maps"), parse("Hey Robo, could you please open Maps"))
        assertEquals(Command.Call("dad"), parse("okay robot I want you to call dad"))
    }
}
