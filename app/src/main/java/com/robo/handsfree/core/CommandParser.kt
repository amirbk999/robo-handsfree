package com.robo.handsfree.core

import java.util.Calendar
import java.util.Locale

/**
 * Turns a spoken sentence into a [Command].
 *
 * Deliberately plain Kotlin (no Android imports) so the whole language layer can be
 * unit tested on the JVM. `nowMillis` is injected so time-based rules are testable.
 */
class CommandParser(
    private val learned: Map<String, String> = emptyMap(),
    languageTag: String = "auto"
) {

    private val matcher = LanguageMatcher(languageTag)
    private val selectedLanguage = LanguagePacks.forTag(languageTag)?.code

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis(), depth: Int = 0): Command {
        var text = raw.trim().replace('\u2019', '\'').replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return Command.Unknown("")

        text = stripWakeWord(text)
        text = stripFillers(text)
        if (text.isEmpty()) return Command.Chat("")

        // A command the user taught earlier overrides everything else.
        if (depth < 3) {
            val key = normalizeText(text)
            val mapped = learned[key]
            if (mapped != null && normalizeText(mapped) != key) {
                return parse(mapped, nowMillis, depth + 1)
            }
        }

        val t = text.lowercase(Locale.US).trim().trimEnd('.', '!', '?', ',')
        if (t.isEmpty()) return Command.Chat("")

        // Words from another language (or any non-Latin script) take priority: "Mummy ko
        // WhatsApp kholo" must not be read as an English app called "WhatsApp kholo".
        // A command in another script, or in Hinglish, is never ambiguous with English, so
        // it is handled first. Other Latin-script languages fall through to the English
        // rules and are picked up by the matcher only when English has nothing to say.
        val hit = matcher.find(text)
        val normalized = normalizeText(text)
        val nonLatin = text.any { it.code > 127 }
        val mixed = HINGLISH_MARKERS.any { containsWord(normalized, it) }
        val chosenLanguage = hit != null && selectedLanguage != null &&
            hit.pack.code == selectedLanguage
        if (hit != null && (nonLatin || mixed || chosenLanguage)) {
            buildFromHit(hit, text, nowMillis)?.let { return it }
        }

        val english = parseRules(t, text, nowMillis)
        if (english !is Command.Chat) return english

        // Never let a foreign reading hijack ordinary English: "open up about my feelings"
        // must stay a conversation, while "abre WhatsApp" may become a command.
        if (hit != null && (nonLatin || chosenLanguage || hit.start > 0 || hit.trigger !in ENGLISH_COMMAND_WORDS)) {
            buildFromHit(hit, text, nowMillis)?.let { return it }
        }
        return english
    }

    private fun containsWord(text: String, word: String): Boolean {
        var index = text.indexOf(word)
        while (index >= 0) {
            if (isWordBoundary(text, index, word.length)) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }

    // ------------------------------------------------ multilingual command building

    private fun buildFromHit(hit: Hit, original: String, now: Long): Command? {
        val text = original
        return when (hit.intent) {
            Intent.OPEN -> {
                var app = matcher.slot(text, hit)
                if (app.isEmpty()) return Command.Chat("Which app should I open?")
                app = LanguagePacks.canonicalApp(app)
                when {
                    normalizeText(app).startsWith("note") -> return Command.ReadNotes
                    normalizeText(app) == "camera" -> return Command.Camera
                    normalizeText(app) == "settings" -> return Command.OpenSettings(null)
                }
                Command.OpenApp(app)
            }

            Intent.CALL -> {
                val contact = matcher.slot(text, hit)
                if (contact.isEmpty()) Command.Chat("Who should I call?") else Command.Call(contact)
            }

            Intent.MESSAGE -> messageFrom(hit, text)

            Intent.ALARM -> {
                val clock = TimeLanguage.clockTime(text)
                Command.SetAlarm(clock?.first, clock?.second, "")
            }

            Intent.TIMER -> {
                val seconds = TimeLanguage.durationSeconds(text)
                if (seconds == null) Command.Chat("How long should the timer be?")
                else Command.SetTimer(seconds)
            }

            Intent.REMINDER -> {
                val what = matcher.slot(text, hit).ifEmpty { matcher.rawAfter(text, hit) }
                if (what.isEmpty()) return Command.Chat("What should I remind you about?")
                val clock = TimeLanguage.clockTime(text)
                val at = if (clock != null) nextOccurrence(clock.first, clock.second, now) else now + 10 * 60_000L
                Command.SetReminder(what, at)
            }

            Intent.NOTE -> {
                val note = matcher.rawAfter(text, hit).ifEmpty { matcher.slot(text, hit) }
                if (note.isEmpty()) Command.Chat("What should I note down?")
                else Command.AddNote(note)
            }

            Intent.READ_NOTES -> Command.ReadNotes
            Intent.FLASHLIGHT -> Command.Flashlight(matcher.direction(text) ?: ToggleDirection.TOGGLE)
            Intent.WIFI -> Command.ToggleSetting(PhoneSetting.WIFI, matcher.direction(text) ?: ToggleDirection.TOGGLE)
            Intent.BLUETOOTH ->
                Command.ToggleSetting(PhoneSetting.BLUETOOTH, matcher.direction(text) ?: ToggleDirection.TOGGLE)
            Intent.AIRPLANE ->
                Command.ToggleSetting(PhoneSetting.AIRPLANE, matcher.direction(text) ?: ToggleDirection.TOGGLE)
            Intent.DATA ->
                Command.ToggleSetting(PhoneSetting.MOBILE_DATA, matcher.direction(text) ?: ToggleDirection.TOGGLE)

            Intent.VOLUME_UP -> Command.Volume(VolumeAction.UP)
            Intent.VOLUME_DOWN -> Command.Volume(VolumeAction.DOWN)
            Intent.MUTE -> Command.Volume(VolumeAction.MUTE)
            Intent.UNMUTE -> Command.Volume(VolumeAction.UNMUTE)
            Intent.BRIGHTNESS_UP -> Command.Brightness(null, true)
            Intent.BRIGHTNESS_DOWN -> Command.Brightness(null, false)
            Intent.SCREENSHOT -> Command.Screenshot
            Intent.CAMERA -> Command.Camera
            Intent.SCROLL_DOWN -> Command.Scroll(ScrollDirection.DOWN)
            Intent.SCROLL_UP -> Command.Scroll(ScrollDirection.UP)
            Intent.BACK -> Command.Back
            Intent.HOME -> Command.Home
            Intent.RECENTS -> Command.Recents
            Intent.READ_SCREEN -> Command.ReadScreen
            Intent.LOCK -> Command.LockScreen
            Intent.BATTERY -> Command.BatteryStatus
            Intent.TIME -> Command.TimeNow
            Intent.DATE -> Command.DateNow
            Intent.NOTIFICATIONS -> Command.OpenNotifications
            Intent.QUICK_SETTINGS -> Command.OpenQuickSettings
            Intent.SETTINGS -> Command.OpenSettings(null)
            Intent.PLAY -> Command.Media(MediaAction.PLAY)
            Intent.PAUSE -> Command.Media(MediaAction.PAUSE)
            Intent.NEXT -> Command.Media(MediaAction.NEXT)
            Intent.PREVIOUS -> Command.Media(MediaAction.PREVIOUS)
            Intent.HELP -> Command.Help
            Intent.STOP -> Command.StopListening
            Intent.START -> Command.StartListening

            Intent.SEARCH -> {
                val query = matcher.slot(text, hit)
                if (query.isEmpty()) Command.Chat("What should I search for?")
                else Command.Search(query, onYoutube = normalizeText(text).contains("youtube"))
            }

            Intent.NAVIGATE -> {
                val place = matcher.slot(text, hit)
                if (place.isEmpty()) Command.Chat("Where should I take you?")
                else Command.Navigate(place)
            }

            Intent.TAP -> {
                val target = matcher.slot(text, hit)
                if (target.isEmpty()) Command.Chat("What should I tap?") else Command.Tap(target)
            }

            Intent.TYPE -> {
                val value = matcher.rawAfter(text, hit)
                if (value.isEmpty()) Command.Chat("What should I type?") else Command.TypeText(value)
            }
        }
    }

    /** "Message John saying I am late" in any language: contact before the connector, body after. */
    private fun messageFrom(hit: Hit, text: String): Command {
        val split = matcher.connectorSplit(text, hit.pack)
        val head = split?.first ?: text
        val body = split?.second.orEmpty()
        val headHit = matcher.find(head) ?: hit
        var contact = matcher.slot(head, headHit)
        if (contact.isEmpty()) contact = matcher.slot(text, hit)

        if (contact.isEmpty()) return Command.Chat("Who should I message?")
        val channel = if (isWhatsApp(text)) MessageChannel.WHATSAPP else MessageChannel.SMS
        return Command.Message(contact, body, channel)
    }

    private fun isWhatsApp(text: String): Boolean {
        val folded = normalizeText(text)
        return WHATSAPP_WORDS.any { folded.contains(it) }
    }

    private fun parseRules(t: String, original: String, now: Long): Command {

        // ---------- teaching / memory ----------
        for (pattern in LEARN_PATTERNS) {
            pattern.find(t)?.let { match ->
                val phrase = cleanTarget(capture(original, match, 1))
                val action = capture(original, match, 2).trim()
                if (phrase.isNotEmpty() && action.isNotEmpty()) return Command.Learn(phrase, action)
            }
        }
        forgetPattern.find(t)?.let { match ->
            val phrase = cleanTarget(capture(original, match, 1))
            if (phrase.isNotEmpty()) return Command.Forget(phrase)
        }

        // ---------- assistant control ----------
        when {
            STOP_PATTERN.matches(t) -> return Command.StopListening
            START_PATTERN.matches(t) -> return Command.StartListening
            HELP_PATTERN.matches(t) || t == "help" || t == "commands" -> return Command.Help
        }

        // ---------- notes ----------
        READ_NOTES_PATTERN.find(t)?.let { return Command.ReadNotes }
        NOTE_PATTERN.find(t)?.let { match ->
            val index = if (match.groups[1]?.value?.isNotBlank() == true) 1 else 2
            val note = capture(original, match, index).trim()
            if (note.isNotEmpty()) return Command.AddNote(note)
        }

        // ---------- time, alarms, reminders ----------
        if (t.startsWith("remind me") || t.startsWith("reminder")) {
            return parseReminder(t, original, now)
        }
        if (ALARM_PATTERN.find(t) != null || WAKE_ME_PATTERN.find(t) != null || t == "set alarm") {
            val clock = clockTime(t)
            val label = ALARM_LABEL_PATTERN.find(t)?.let { capture(original, it, 1).trim() }.orEmpty()
            return Command.SetAlarm(clock?.first, clock?.second, label)
        }
        if (TIMER_PATTERN.find(t) != null || t == "set timer" || t == "timer") {
            val seconds = durationSeconds(t)
            return if (seconds != null) Command.SetTimer(seconds) else Command.Chat("How long should the timer be?")
        }
        if (TIME_PATTERN.matches(t)) return Command.TimeNow
        if (DATE_PATTERN.matches(t)) return Command.DateNow

        // ---------- messaging & calling ----------
        messagePatterns.forEach { pattern ->
            pattern.find(t)?.let { m ->
                val contact = cleanTarget(capture(original, m, 1))
                val body = capture(original, m, 2).trim()
                val channel = if (t.contains("whatsapp") || t.contains("whats app")) {
                    MessageChannel.WHATSAPP
                } else {
                    MessageChannel.SMS
                }
                if (contact.isNotEmpty()) return Command.Message(contact, body, channel)
            }
        }
        CALL_PATTERN.find(t)?.let { m ->
            val contact = cleanTarget(capture(original, m, 1))
            if (contact.isNotEmpty()) return Command.Call(contact)
        }

        // ---------- settings pages (before the generic "open" rules) ----------
        SETTINGS_PAGE_PATTERN.find(t)?.let { m ->
            return Command.OpenSettings(cleanTarget(capture(original, m, 1)))
        }
        if (t == "open settings" || t == "go to settings" || t == "settings") {
            return Command.OpenSettings(null)
        }

        // ---------- phone navigation (before generic "open") ----------
        when {
            NOTIFICATIONS_PATTERN.find(t) != null -> return Command.OpenNotifications
            QUICK_SETTINGS_PATTERN.find(t) != null -> return Command.OpenQuickSettings
            LOCK_PATTERN.find(t) != null -> return Command.LockScreen
            CAMERA_PATTERN.find(t) != null -> return Command.Camera
            SCREENSHOT_PATTERN.find(t) != null -> return Command.Screenshot
            READ_SCREEN_PATTERN.find(t) != null -> return Command.ReadScreen
            BACK_PATTERN.matches(t) -> return Command.Back
            HOME_PATTERN.matches(t) -> return Command.Home
            RECENTS_PATTERN.find(t) != null -> return Command.Recents
        }

        // ---------- toggles ----------
        val direction = directionIn(t)
        when {
            WIFI_PATTERN.find(t) != null -> return Command.ToggleSetting(PhoneSetting.WIFI, directionOf(direction, t))
            BLUETOOTH_PATTERN.find(t) != null && direction != null ->
                return Command.ToggleSetting(PhoneSetting.BLUETOOTH, directionOf(direction, t))
            AIRPLANE_PATTERN.find(t) != null ->
                return Command.ToggleSetting(PhoneSetting.AIRPLANE, directionOf(direction, t))
            MOBILE_DATA_PATTERN.find(t) != null ->
                return Command.ToggleSetting(PhoneSetting.MOBILE_DATA, directionOf(direction, t))
            FLASHLIGHT_PATTERN.find(t) != null ->
                return Command.Flashlight(directionOf(direction, t))
        }

        // ---------- sound, media, screen ----------
        if (VOLUME_PATTERN.find(t) != null) {
            volumeCommand(t)?.let { return it }
        }
        if (MUTE_PATTERN.matches(t)) return Command.Volume(VolumeAction.MUTE)
        if (UNMUTE_PATTERN.matches(t)) return Command.Volume(VolumeAction.UNMUTE)
        mediaCommand(t)?.let { return it }
        if (BRIGHTNESS_PATTERN.find(t) != null) {
            brightnessCommand(t)?.let { return it }
        }
        if (SCROLL_PATTERN.find(t) != null) {
            val up = Regex("\\bup\\b|\\btop\\b|\\babove\\b").containsMatchIn(t)
            return Command.Scroll(if (up) ScrollDirection.UP else ScrollDirection.DOWN)
        }

        // ---------- screen automation ----------
        TAP_PATTERN.find(t)?.let { m ->
            val target = cleanTarget(capture(original, m, 1))
            if (target.isNotEmpty()) return Command.Tap(target)
        }
        TYPE_PATTERN.find(t)?.let { m ->
            val value = capture(original, m, 1).trim()
            if (value.isNotEmpty()) return Command.TypeText(value)
        }

        // ---------- device info ----------
        if (BATTERY_PATTERN.find(t) != null) return Command.BatteryStatus

        // ---------- search / maps ----------
        if (YOUTUBE_PATTERN.find(t) != null) {
            PLAY_ON_YOUTUBE_PATTERN.find(t)?.let { m ->
                val index = if (m.groups[1]?.value?.isNotBlank() == true) 1 else 2
                val query = capture(original, m, index).trim()
                if (query.isNotEmpty()) return Command.Search(query, onYoutube = true)
            }
        }
        NAVIGATE_PATTERN.find(t)?.let { m ->
            val place = cleanTarget(capture(original, m, 1))
            if (place.isNotEmpty()) return Command.Navigate(place)
        }
        SEARCH_PATTERN.find(t)?.let { m ->
            val query = cleanTarget(capture(original, m, 1))
            if (query.isNotEmpty()) return Command.Search(query, onYoutube = false)
        }

        // ---------- apps & settings ----------
        OPEN_APP_PATTERN.find(t)?.let { m ->
            var app = cleanTarget(capture(original, m, 1))
            app = app.removePrefix("my ").removeSuffix(" app").trim()
            when {
                app.isEmpty() -> return Command.Chat("Which app should I open?")
                app.lowercase(Locale.US).startsWith("note") -> return Command.ReadNotes
                app == "camera" -> return Command.Camera
                app == "settings" -> return Command.OpenSettings(null)
            }
            return Command.OpenApp(app)
        }

        // ---------- conversation ----------
        return Command.Chat(original)
    }

    // ---------------------------------------------------------------- helpers

    private fun parseReminder(t: String, original: String, now: Long): Command {
        val inMatch = Regex("^remind\\s+me\\s+in\\s+(.+?)\\s+to\\s+(.+)$").find(t)
        if (inMatch != null) {
            val seconds = durationSeconds(inMatch.groupValues[1])
            val what = capture(original, inMatch, 2).trim()
            if (seconds != null && what.isNotEmpty()) {
                return Command.SetReminder(what, now + seconds * 1000L)
            }
        }
        val atMatch = Regex("^remind\\s+me\\s+to\\s+(.+?)\\s+(?:at|by|around)\\s+(.+)$").find(t)
        if (atMatch != null) {
            val what = capture(original, atMatch, 1).trim()
            val clock = clockTime(atMatch.groupValues[2])
            if (what.isNotEmpty() && clock != null) {
                return Command.SetReminder(what, nextOccurrence(clock.first, clock.second, now))
            }
        }
        val bare = Regex("^remind\\s+me\\s+(?:to\\s+)?(.+)$").find(t)
        if (bare != null) {
            return Command.SetReminder(capture(original, bare, 1).trim(), now + 10 * 60_000L)
        }
        return Command.Chat("What should I remind you about?")
    }

    /** Reads a matched group from the original sentence so capitalisation is preserved. */
    private fun capture(original: String, match: MatchResult, index: Int): String {
        val group = match.groups[index] ?: return ""
        val range = group.range
        return if (range.first >= 0 && range.last < original.length) {
            original.substring(range.first, range.last + 1)
        } else {
            group.value
        }
    }

    /** Returns (hour, minute) in 24h time, or null when no time was spoken. */
    internal fun clockTime(input: String): Pair<Int, Int>? {
        val t = input.lowercase(Locale.US)
        MERIDIAN_TIME.find(t)?.let { m ->
            return to24(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3])
        }
        MERIDIAN_HOUR.find(t)?.let { m ->
            return to24(m.groupValues[1].toIntOrNull() ?: return null, 0, m.groupValues[2])
        }
        COLON_TIME.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            return if (h in 0..23 && min in 0..59) h to min else null
        }
        BARE_HOUR.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            if (h in 0..23) return h to 0
        }
        return null
    }

    internal fun durationSeconds(input: String): Int? {
        val t = input.lowercase(Locale.US).replace("-", " ")
        DURATION.find(t)?.let { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].first()
            val multiplier = when (unit) {
                's' -> 1
                'm' -> 60
                else -> 3600
            }
            return value * multiplier
        }
        return when {
            t.contains("half an hour") || t.contains("half hour") -> 30 * 60
            t.contains("an hour") || t.contains("one hour") -> 60 * 60
            t.contains("a minute") || t.contains("one minute") -> 60
            t.contains("a second") -> 1
            else -> null
        }
    }

    private fun to24(hour: Int, minute: Int, meridian: String): Pair<Int, Int>? {
        if (minute !in 0..59) return null
        var h = hour
        if (h !in 0..23) return null
        val pm = meridian.replace(" ", "").replace(".", "").startsWith("p")
        val am = meridian.replace(" ", "").replace(".", "").startsWith("a")
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return h to minute
    }

    private fun nextOccurrence(hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun volumeCommand(t: String): Command? {
        Regex("(?:set\\s+)?volume\\s*(?:to|=|at)?\\s*(\\d{1,3})\\s*(?:%|percent)?").find(t)?.let {
            val level = it.groupValues[1].toIntOrNull()
            if (level != null && level in 0..100) return Command.Volume(VolumeAction.SET, level)
        }
        return when {
            t.contains("unmute") -> Command.Volume(VolumeAction.UNMUTE)
            t.contains("mute") -> Command.Volume(VolumeAction.MUTE)
            Regex("\\b(up|increase|raise|louder|higher)\\b").containsMatchIn(t) -> Command.Volume(VolumeAction.UP)
            Regex("\\b(down|decrease|lower|quieter)\\b").containsMatchIn(t) -> Command.Volume(VolumeAction.DOWN)
            else -> Command.Volume(VolumeAction.SET, 50)
        }
    }

    private fun brightnessCommand(t: String): Command? {
        Regex("brightness\\s*(?:to|=|at)?\\s*(\\d{1,3})").find(t)?.let {
            val level = it.groupValues[1].toIntOrNull()
            if (level != null && level in 0..100) return Command.Brightness(level, null)
        }
        return when {
            Regex("\\b(up|increase|raise|brighter|max|maximum)\\b").containsMatchIn(t) ->
                Command.Brightness(null, true)
            Regex("\\b(down|decrease|lower|dimmer|dim|min|minimum)\\b").containsMatchIn(t) ->
                Command.Brightness(null, false)
            else -> null
        }
    }

    private fun mediaCommand(t: String): Command? {
        if (t == "play music" || t == "play the music" || t == "resume music" || t == "resume the music") {
            return Command.Media(MediaAction.PLAY)
        }
        if (t == "pause music" || t == "pause the music" || t == "pause" || t == "pause the song") {
            return Command.Media(MediaAction.PAUSE)
        }
        return when (t) {
            "play", "resume", "play song", "play the song", "play track", "play video" ->
                Command.Media(MediaAction.PLAY)
            "next", "next song", "next track", "skip", "skip song", "skip track" ->
                Command.Media(MediaAction.NEXT)
            "previous", "previous song", "previous track", "last song", "go back a song" ->
                Command.Media(MediaAction.PREVIOUS)
            "toggle music", "toggle playback" -> Command.Media(MediaAction.TOGGLE)
            else -> null
        }
    }

    private fun directionOf(direction: ToggleDirection?, t: String): ToggleDirection {
        if (direction != null) return direction
        return if (t.contains("bluetooth")) ToggleDirection.TOGGLE else ToggleDirection.TOGGLE
    }

    private fun directionIn(t: String): ToggleDirection? = when {
        Regex("\\b(off|disable|stop|close|deactivate)\\b").containsMatchIn(t) -> ToggleDirection.OFF
        Regex("\\b(on|enable|start|open|activate)\\b").containsMatchIn(t) -> ToggleDirection.ON
        t.contains("toggle") || t.contains("switch") -> ToggleDirection.TOGGLE
        else -> null
    }

    private fun stripFillers(input: String): String {
        var text = input
        var changed = true
        while (changed) {
            changed = false
            for (filler in FILLERS) {
                if (text.startsWith(filler, ignoreCase = true)) {
                    text = text.substring(filler.length).trim()
                    changed = true
                }
            }
        }
        return text
    }

    private fun cleanTarget(value: String): String = value
        .trim()
        .trim('"', '\'', ' ', '.', ',', '!', '?', ':')
        .removePrefix("the ")
        .removeSuffix(" please")
        .removeSuffix(" now")
        .removeSuffix(" for me")
        .trim()

    private companion object {
        val FILLERS = listOf(
            "please ", "can you ", "could you ", "would you ", "will you ",
            "i want you to ", "i want to ", "i need you to ", "i need to ",
            "go ahead and ", "just ", "kindly ", "robo ", "hey robo "
        )

        val LEARN_PATTERNS = listOf(
            Regex("^(?:learn|remember)\\s+(?:that\\s+)?when\\s+i\\s+say\\s+(.+?)[,\\s]+(?:then\\s+)?(?:do|run|you\\s+should)\\s+(.+)$"),
            Regex("^when\\s+i\\s+say\\s+(.+?)[,\\s]+(?:then\\s+)?(?:do|run|you\\s+should)\\s+(.+)$"),
            Regex("^when\\s+i\\s+say\\s+(.+?)[,\\s]+((?:open|launch|start|call|text|message|whatsapp|play|pause|set|turn|go|scroll|tap|type|search|read|lock|take|remind|navigate)\\b.+)$"),
            Regex("^(?:learn|remember|teach\\s+you)\\s+that\\s+(.+?)\\s+(?:means|should\\s+do|should)\\s+(.+)$"),
            Regex("^(?:learn|remember)\\s+(.+?)\\s+(?:=|means)\\s+(.+)$"),
            Regex("^teach\\s+you\\s+(?:that\\s+)?(.+?)\\s+to\\s+(.+)$")
        )
        val forgetPattern = Regex("^(?:forget|delete|remove)\\s+(?:the\\s+)?(?:skill|command|mapping|rule)?\\s*(?:for\\s+|that\\s+)?(.+)$")

        val STOP_PATTERN = Regex("^(stop(?:\\s+(?:listening|for now|now))?|pause listening|go to sleep|sleep|goodbye|bye|bye bye|quiet|shut up|that'?s all)$")
        val START_PATTERN = Regex("^(wake up|start listening|listen|are you there|you there)$")
        val HELP_PATTERN = Regex("^(help|help me|what can you do|what can i say|commands|show commands|how do i use you)$")

        val READ_NOTES_PATTERN = Regex("^(?:read|show|open|list|what\\s+are)\\s+(?:me\\s+)?(?:my\\s+)?notes\\b.*$")
        val NOTE_PATTERN = Regex("^(?:take|make|write|add|create|save)\\s+(?:a\\s+)?note(?:\\s+that)?\\s*[:\\-]?\\s*(.+)$|^remember\\s+(?:that\\s+)?(.+)$")

        val ALARM_PATTERN = Regex("\\b(?:set|create|put|make|add)\\s+(?:an?\\s+)?alarm\\b|^alarm\\s+(?:for|at)\\b")
        val WAKE_ME_PATTERN = Regex("\\bwake\\s+me\\s+up\\b")
        val ALARM_LABEL_PATTERN = Regex("\\b(?:to|for)\\s+([a-z][a-z ]{2,30})$")
        val TIMER_PATTERN = Regex("\\b(?:set|start|create)?\\s*(?:a\\s+)?timer\\b")
        val TIME_PATTERN = Regex("^(?:what(?:'s| is)? the time|what time is it|time|tell me the time|current time)$")
        val DATE_PATTERN = Regex("^(?:what(?:'s| is)? (?:the date|today'?s date|today)|what day is (?:it|today)|date)$")

        val messagePatterns = listOf(
            Regex("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s?app|sms|text|message)\\s+(?:message\\s+)?to\\s+(.+?)\\s+(?:saying|that|with\\s+(?:the\\s+)?message|:)\\s*(.+)$"),
            Regex("^(?:send\\s+)?(?:a\\s+)?message\\s+to\\s+(.+?)\\s+(?:saying|that|:)\\s*(.+)$"),
            Regex("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s?app|sms|text|message)\\s+(?!to\\b)(.+?)\\s+(?:saying|that|:)\\s*(.+)$"),
            Regex("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s?app|sms|text|message)\\s+(?!to\\b)(.+?)\\s+(.+)$"),
            Regex("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp|whats\\s?app|sms|text|message)\\s+to\\s+(.+)$")
        )
        val CALL_PATTERN = Regex("^(?:call|dial|phone|ring)\\s+(?:up\\s+)?(.+)$")

        val NOTIFICATIONS_PATTERN = Regex("\\b(?:open|show|pull\\s+down|see|check)\\s+(?:my\\s+|the\\s+)?notifications?\\b")
        val QUICK_SETTINGS_PATTERN = Regex("\\b(?:open|show)\\s+(?:my\\s+|the\\s+)?(?:quick\\s+settings|control\\s+centre|control\\s+center)\\b")
        val LOCK_PATTERN = Regex("\\b(?:lock|secure)\\s+(?:the\\s+)?(?:phone|screen|device)\\b")
        val CAMERA_PATTERN = Regex("\\b(?:open\\s+(?:the\\s+)?camera|take\\s+(?:a\\s+)?(?:photo|picture|selfie)|click\\s+(?:a\\s+)?(?:photo|picture|selfie))\\b")
        val SCREENSHOT_PATTERN = Regex("\\b(?:take\\s+(?:a\\s+)?screenshot|screenshot|capture\\s+(?:the\\s+)?screen)\\b")
        val READ_SCREEN_PATTERN = Regex("\\b(?:read\\s+(?:me\\s+)?(?:the\\s+|my\\s+)?screen|what(?:'s| is) on (?:the\\s+|my\\s+)?screen|read\\s+(?:this|that)|what\\s+does\\s+(?:it|this)\\s+say)\\b")
        val BACK_PATTERN = Regex("^(?:go\\s+)?back$|^back$|^previous screen$")
        val HOME_PATTERN = Regex("^(?:go\\s+)?home$|^home screen$|^go to home screen$")
        val RECENTS_PATTERN = Regex("\\b(?:recent apps|recent tasks|show recents|open recents|app switcher)\\b")

        val WIFI_PATTERN = Regex("\\b(?:wi\\s?fi|wifi|wireless)\\b")
        val BLUETOOTH_PATTERN = Regex("\\bbluetooth\\b")
        val AIRPLANE_PATTERN = Regex("\\b(?:airplane|aeroplane|flight)\\s+mode\\b")
        val MOBILE_DATA_PATTERN = Regex("\\b(?:mobile data|data connection|cellular data)\\b")
        val FLASHLIGHT_PATTERN = Regex("\\b(?:flashlight|torch|flash light)\\b")

        val VOLUME_PATTERN = Regex("\\bvolume\\b|\\bmute\\b|\\bunmute\\b")
        val MUTE_PATTERN = Regex("^(?:mute|mute the (?:phone|volume|sound))$")
        val UNMUTE_PATTERN = Regex("^(?:unmute|unmute the (?:phone|volume|sound)|turn (?:the )?sound on)$")
        val BRIGHTNESS_PATTERN = Regex("\\b(?:brightness|screen brightness|dim the screen)\\b")
        val SCROLL_PATTERN = Regex("\\b(?:scroll|swipe)\\b")

        val TAP_PATTERN = Regex("^(?:tap|click|press|touch|hit)\\s+(?:on\\s+|the\\s+)?(.+)$")
        val TYPE_PATTERN = Regex("^(?:type|write|enter|input)\\s+(.+)$")
        val BATTERY_PATTERN = Regex("\\b(?:battery|charge level|how much charge)\\b")

        val YOUTUBE_PATTERN = Regex("\\byoutube\\b")
        val PLAY_ON_YOUTUBE_PATTERN = Regex("^(?:play|search)\\s+(.+?)\\s+on\\s+youtube$|^youtube\\s+(.+)$")
        val NAVIGATE_PATTERN = Regex("^(?:navigate|directions|route|take\\s+me|drive\\s+me|walk\\s+me)\\s+(?:to\\s+)?(.+)$")
        val SEARCH_PATTERN = Regex("^(?:search|google|look\\s+up|find|search for|google for)\\s+(?:for\\s+)?(.+?)(?:\\s+on\\s+(?:google|the\\s+web|internet))?$")

        val SETTINGS_PAGE_PATTERN = Regex(
            "^(?:open|go\\s+to|show)\\s+(wifi|wi-fi|internet|bluetooth|display|brightness|sound|volume|battery|accessibility|apps?|applications?|location|gps|mobile\\s+data|data|language|notifications?)\\s+settings$"
        )
        val OPEN_APP_PATTERN = Regex("^(?:open|launch|start|run|go\\s+to)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$")

        /** Words that only appear in an Indian-English mix, never in an English command. */
        val HINGLISH_MARKERS = listOf(
            "karo", "kar", "kro", "kijiye", "kijie", "karna", "kardo", "kar do",
            "kholo", "khol", "kholiye", "bhejo", "bhej", "lagao", "lagado", "lago",
            "chalao", "bajao", "batao", "dikhao", "mujhe", "mera", "meri", "baje", "yaar",
            "kripya", "abhi", "jaldi"
        )

        /** English command words, which must never be re-read as a foreign trigger. */
        val ENGLISH_COMMAND_WORDS = setOf(
            "open", "launch", "start", "run", "call", "dial", "phone", "ring", "message",
            "text", "sms", "send", "alarm", "timer", "reminder", "remind", "note", "notes",
            "play", "pause", "next", "previous", "skip", "mute", "unmute", "volume",
            "brightness", "flashlight", "torch", "screenshot", "camera", "search", "google",
            "find", "navigate", "directions", "lock", "home", "back", "battery", "time",
            "date", "help", "stop", "settings", "wifi", "bluetooth", "notifications",
            "music", "song", "photo", "picture", "read", "screen", "scroll", "tap", "type"
        )

        val WHATSAPP_WORDS = listOf(
            "whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप", "واٹس", "واتس", "হোয়াটস",
            "ਵਟਸਐਪ", "વોટ્સએપ", "வாட்ஸ்அப்", "వాట్సాప్", "ವಾಟ್ಸಾಪ್", "വാട്സാപ്പ്", "וואטסאפ", "whatsapp"
        )

        val MERIDIAN_TIME = Regex("(\\d{1,2})(?:\\s*[:.]\\s*|\\s)(\\d{2})?\\s*(a\\s?\\.?\\s?m\\.?|p\\s?\\.?\\s?m\\.?)")
        val MERIDIAN_HOUR = Regex("(\\d{1,2})\\s*(a\\s?\\.?\\s?m\\.?|p\\s?\\.?\\s?m\\.?)")
        val COLON_TIME = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})")
        val BARE_HOUR = Regex("(?:\\bat|\\bfor|\\baround)\\s+(\\d{1,2})\\b")
        val DURATION = Regex("\\b(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")
    }
}
