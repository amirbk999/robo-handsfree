package com.robo.handsfree

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.robo.handsfree.core.Command
import com.robo.handsfree.core.CommandParser
import com.robo.handsfree.core.MediaAction
import com.robo.handsfree.core.MessageChannel
import com.robo.handsfree.core.PhoneSetting
import com.robo.handsfree.core.ScrollDirection
import com.robo.handsfree.core.ToggleDirection
import com.robo.handsfree.core.VolumeAction
import com.robo.handsfree.core.similarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Turns a [Command] into something that actually happens on the phone, and returns the
 * sentence Robo should say back. Every branch is written to fail with a useful message
 * instead of throwing, so a missing permission never crashes the assistant.
 */
class ActionRunner(
    private val context: Context,
    private val skills: SkillStore
) {

    private val accessibility: RoboAccessibilityService?
        get() = RoboAccessibilityService.instance

    suspend fun run(command: Command): String = when (command) {
        is Command.OpenApp -> openApp(command.app)
        is Command.OpenSettings -> openSettings(command.page)
        is Command.Call -> call(command.contact)
        is Command.Message -> message(command)
        is Command.SetAlarm -> setAlarm(command)
        is Command.SetTimer -> setTimer(command.seconds)
        is Command.SetReminder -> setReminder(command)
        is Command.AddNote -> {
            skills.addNote(command.text)
            "Noted: ${command.text}"
        }
        Command.ReadNotes -> readNotes()
        is Command.Flashlight -> flashlight(command.direction)
        is Command.Volume -> volume(command)
        is Command.Media -> media(command.action)
        is Command.Brightness -> brightness(command)
        is Command.Scroll -> scroll(command.direction)
        Command.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.")
        Command.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Home.")
        Command.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.")
        Command.OpenNotifications -> global(
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "Opening notifications."
        )
        Command.OpenQuickSettings -> global(
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "Opening quick settings."
        )
        Command.LockScreen -> lockScreen()
        is Command.Tap -> tap(command.target)
        is Command.TypeText -> type(command.text)
        Command.ReadScreen -> readScreen()
        is Command.Search -> search(command.query, command.onYoutube)
        is Command.Navigate -> navigate(command.place)
        Command.BatteryStatus -> battery()
        Command.TimeNow -> "It's ${DateFormat.getTimeFormat(context).format(Date())}."
        Command.DateNow -> "Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())}."
        is Command.ToggleSetting -> toggleSetting(command.setting, command.direction)
        Command.Camera -> camera()
        Command.Screenshot -> screenshot()
        is Command.Learn -> learn(command)
        is Command.Forget -> forget(command.phrase)
        Command.Help -> help()
        Command.StopListening -> {
            Robo.voiceService?.pauseListening()
            "Okay, I'll wait. Tap the mic when you need me."
        }
        Command.StartListening -> {
            Robo.voiceService?.resumeListening()
            "I'm listening."
        }
        is Command.Chat, is Command.Unknown -> "I'm not sure what to do with that yet."
    }

    // ------------------------------------------------------------------- apps

    private suspend fun openApp(name: String): String = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = try {
            manager.queryIntentActivities(launcher, 0)
        } catch (t: Throwable) {
            emptyList()
        }

        val preferredPackage = PREFERRED_PACKAGES[com.robo.handsfree.core.normalizeText(name)]
        val preferred = installed.firstOrNull { it.activityInfo.packageName == preferredPackage }
        val best = preferred ?: installed.maxByOrNull { similarity(name, it.loadLabel(manager).toString()) }
        val score = best?.let { similarity(name, it.loadLabel(manager).toString()) } ?: 0.0

        if (best == null || (preferred == null && score < 0.55)) {
            return@withContext "I couldn't find an app called \"$name\" on this phone."
        }

        val label = best.loadLabel(manager).toString()
        val launchIntent = manager.getLaunchIntentForPackage(best.activityInfo.packageName)
        val opened = if (launchIntent != null) launch(launchIntent) else launch(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(best.activityInfo.packageName, best.activityInfo.name)
        )

        if (opened) "Opening $label." else "I found $label but couldn't open it."
    }

    private fun openSettings(page: String?): String {
        val action = when (page?.trim()?.lowercase(Locale.US)) {
            null, "" -> Settings.ACTION_SETTINGS
            "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps", "app", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "mobile data", "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "notification", "notifications" -> Settings.ACTION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return "Opening settings."
    }

    // ------------------------------------------------------------ calls & texts

    private suspend fun call(contact: String): String {
        val match = withContext(Dispatchers.IO) { resolveContact(contact) } ?: return noContact(contact)
        val number = Uri.encode(match.number)
        return if (Permissions.has(context, Manifest.permission.CALL_PHONE)) {
            if (launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))) {
                "Calling ${match.name}."
            } else {
                "I couldn't start the call."
            }
        } else {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            "I don't have call permission, so the dialler is open for ${match.name}. Tap call once, or allow phone access in Robo."
        }
    }

    private suspend fun message(command: Command.Message): String {
        val match = withContext(Dispatchers.IO) { resolveContact(command.contact) }
            ?: return noContact(command.contact)

        if (command.body.isBlank()) return "What should I say to ${match.name}?"

        if (command.channel == MessageChannel.WHATSAPP) {
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            val installed = packages.firstOrNull { isInstalled(it) }
                ?: return "WhatsApp isn't installed on this phone."
            val digits = match.number.filter { it.isDigit() }
            val url = "https://wa.me/$digits?text=${Uri.encode(command.body)}"
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(installed))) {
                return "I couldn't open WhatsApp."
            }
            delay(1600)
            val foreground = withContext(Dispatchers.Main) { accessibility?.currentPackage() }
            // Never press "Send" in some other app that happened to receive the link.
            val inWhatsApp = foreground == null || foreground.startsWith("com.whatsapp")
            val sent = inWhatsApp && withContext(Dispatchers.Main) {
                accessibility?.clickAny(listOf("Send", "send")) == true
            }
            return if (sent) {
                "WhatsApp message sent to ${match.name}."
            } else {
                "WhatsApp is open with your message for ${match.name}. Tap send once — I couldn't find the button."
            }
        }

        return if (Permissions.has(context, Manifest.permission.SEND_SMS)) {
            sendSms(match.number, command.body)
        } else {
            launch(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${match.number}"))
                    .putExtra("sms_body", command.body)
            )
            "I don't have SMS permission, so your message to ${match.name} is ready. Tap send once."
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, body: String): String = try {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        if (sms == null) {
            "I couldn't reach the SMS service."
        } else {
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, body, null, null)
            }
            "Message sent."
        }
    } catch (t: Throwable) {
        "I couldn't send the message: ${t.message ?: "unknown error"}"
    }

    // ------------------------------------------------------------ time helpers

    private fun setAlarm(command: Command.SetAlarm): String {
        val hour = command.hour ?: return "What time should I set the alarm for?"
        val minute = command.minute ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (command.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, command.label)
        }
        if (!launch(intent)) return "I couldn't reach your clock app."
        val label = if (command.label.isNotBlank()) " for ${command.label}" else ""
        return "Alarm set for ${formatClock(hour, minute)}$label."
    }

    private fun setTimer(seconds: Int): String {
        if (seconds <= 0) return "How long should the timer be?"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Robo timer")
        }
        if (!launch(intent)) return "I couldn't reach your clock app."
        return "Timer set for ${durationWords(seconds)}."
    }

    private fun setReminder(command: Command.SetReminder): String {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return "I couldn't reach the alarm service."
        val requestCode = (command.text.hashCode() and 0xffff)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TEXT, command.text)
            putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, command.atMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, command.atMillis, pending)
            }
        } catch (t: Throwable) {
            return "I couldn't schedule that reminder: ${t.message}"
        }
        val whenText = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault())
            .format(Date(command.atMillis))
        return "Reminder set for $whenText: ${command.text}."
    }

    private fun formatClock(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }

    private fun durationWords(seconds: Int): String {
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 && minutes % 60 > 0 -> "$hours hour${if (hours == 1) "" else "s"} and ${minutes % 60} minutes"
            hours > 0 -> "$hours hour${if (hours == 1) "" else "s"}"
            minutes > 0 -> "$minutes minute${if (minutes == 1) "" else "s"}"
            else -> "$seconds second${if (seconds == 1) "" else "s"}"
        }
    }

    // ------------------------------------------------------------------ notes

    private fun readNotes(): String {
        val notes = skills.notes()
        if (notes.isEmpty()) return "You don't have any notes yet. Say “take a note …”."
        val spoken = notes.take(5).mapIndexed { index, note -> "${index + 1}. $note" }.joinToString(". ")
        val more = if (notes.size > 5) " And ${notes.size - 5} more." else ""
        return "Your notes: $spoken.$more"
    }

    // --------------------------------------------------------------- hardware

    private fun flashlight(direction: ToggleDirection): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "I can't reach the flashlight."
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone doesn't have a flashlight."

            val turnOn = when (direction) {
                ToggleDirection.ON -> true
                ToggleDirection.OFF -> false
                ToggleDirection.TOGGLE -> !torchOn
            }
            manager.setTorchMode(cameraId, turnOn)
            torchOn = turnOn
            if (turnOn) "Flashlight on." else "Flashlight off."
        } catch (t: Throwable) {
            "I couldn't switch the flashlight: ${t.message ?: "camera busy"}"
        }
    }

    private fun volume(command: Command.Volume): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "I can't reach the volume controls."
        val stream = AudioManager.STREAM_MUSIC
        val max = manager.getStreamMaxVolume(stream)
        return when (command.action) {
            VolumeAction.UP -> {
                manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "Volume up."
            }
            VolumeAction.DOWN -> {
                manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "Volume down."
            }
            VolumeAction.MUTE -> {
                manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                "Muted."
            }
            VolumeAction.UNMUTE -> {
                manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                "Sound is back on."
            }
            VolumeAction.SET -> {
                val percent = (command.level ?: 50).coerceIn(0, 100)
                manager.setStreamVolume(stream, (max * percent / 100f).roundToInt(), 0)
                "Volume set to $percent percent."
            }
        }
    }

    private fun media(action: MediaAction): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "I can't reach the media controls."
        val key = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return when (action) {
            MediaAction.PLAY -> "Playing."
            MediaAction.PAUSE -> "Paused."
            MediaAction.TOGGLE -> "Toggled playback."
            MediaAction.NEXT -> "Next track."
            MediaAction.PREVIOUS -> "Previous track."
        }
    }

    private fun brightness(command: Command.Brightness): String {
        if (!Permissions.canWriteSettings(context)) {
            launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
            return "I need permission to change brightness. I've opened the setting — allow modifying system settings, then ask again."
        }
        val resolver = context.contentResolver
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val max = 255
        val target = when {
            command.percent != null -> max * command.percent / 100
            command.up == true -> current + max / 5
            else -> current - max / 5
        }.coerceIn(10, max)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return "Brightness ${target * 100 / max} percent."
    }

    private fun battery(): String {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (percent < 0) return "I couldn't read the battery level."
        return "Battery is $percent percent${if (charging) " and charging" else ""}."
    }

    // ---------------------------------------------------------- screen control

    private fun global(action: Int, done: String): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        return if (service.performGlobalAction(action)) done else "I couldn't do that on this screen."
    }

    private fun lockScreen(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "Locking the screen needs Android 9 or newer."
        }
        return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the phone.")
    }

    private suspend fun tap(target: String): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        var done = withContext(Dispatchers.Main) { service.clickText(target) }
        if (!done) {
            delay(500)
            done = withContext(Dispatchers.Main) { service.clickText(target) }
        }
        return if (done) "Tapped \"$target\"." else "I couldn't find \"$target\" on the screen."
    }

    private suspend fun type(text: String): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        val done = withContext(Dispatchers.Main) { service.typeText(text) }
        return if (done) {
            "Typed it."
        } else {
            "I couldn't find a text box. Tap the field once, then ask me to type again."
        }
    }

    private suspend fun readScreen(): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        val text = withContext(Dispatchers.Main) { service.screenText() }
        return if (text.isBlank()) {
            "I couldn't read anything on this screen."
        } else {
            "The screen says: $text"
        }
    }

    private fun scroll(direction: ScrollDirection): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        val forward = direction == ScrollDirection.DOWN
        return if (service.scroll(forward)) {
            if (forward) "Scrolling down." else "Scrolling up."
        } else {
            "I couldn't scroll this screen."
        }
    }

    private suspend fun toggleSetting(setting: PhoneSetting, direction: ToggleDirection): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        val labels = when (setting) {
            PhoneSetting.WIFI -> listOf("Wi-Fi", "WiFi", "Internet")
            PhoneSetting.BLUETOOTH -> listOf("Bluetooth")
            PhoneSetting.AIRPLANE -> listOf("Airplane mode", "Aeroplane mode", "Flight mode")
            PhoneSetting.MOBILE_DATA -> listOf("Mobile data", "Cellular data")
        }
        val pretty = when (setting) {
            PhoneSetting.WIFI -> "Wi-Fi"
            PhoneSetting.BLUETOOTH -> "Bluetooth"
            PhoneSetting.AIRPLANE -> "Airplane mode"
            PhoneSetting.MOBILE_DATA -> "Mobile data"
        }
        val desired = when (direction) {
            ToggleDirection.ON -> true
            ToggleDirection.OFF -> false
            ToggleDirection.TOGGLE -> null
        }

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(900)
        val handled = withContext(Dispatchers.Main) { service.clickQuickTile(labels, desired) }
        delay(250)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        if (!handled) {
            return "I opened quick settings but couldn't find the $pretty tile. Tap it once, please."
        }
        return "$pretty ${when (direction) {
            ToggleDirection.ON -> "on"
            ToggleDirection.OFF -> "off"
            ToggleDirection.TOGGLE -> "toggled"
        }}."
    }

    private suspend fun camera(): String {
        if (!launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))) return "I couldn't open the camera."
        delay(1600)
        val tapped = withContext(Dispatchers.Main) {
            accessibility?.clickAny(listOf("shutter", "take photo", "capture", "take picture")) == true
        }
        return if (tapped) {
            "Camera open, photo taken."
        } else {
            "Camera is open. Say “tap shutter” to take the photo."
        }
    }

    private suspend fun screenshot(): String {
        val service = accessibility ?: return NO_ACCESSIBILITY
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            service.captureScreenshot { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        } ?: return "I couldn't capture the screen. Screenshots need Android 11 or newer, with my accessibility service on."

        val saved = withContext(Dispatchers.IO) { saveScreenshot(bitmap) }
        return if (saved) "Screenshot saved to your gallery." else "I captured the screen but couldn't save it."
    }

    private fun saveScreenshot(bitmap: Bitmap): Boolean = try {
        val name = "Robo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Robo")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                true
            } ?: false
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Robo")
            if (!directory.exists()) directory.mkdirs()
            FileOutputStream(File(directory, name)).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            true
        }
    } catch (t: Throwable) {
        false
    }

    // -------------------------------------------------------------- web & maps

    private fun search(query: String, onYoutube: Boolean): String {
        val url = if (onYoutube) {
            "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        } else {
            "https://www.google.com/search?q=${Uri.encode(query)}"
        }
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            if (onYoutube) "Searching YouTube for $query." else "Searching the web for $query."
        } else {
            "I couldn't open a browser."
        }
    }

    private fun navigate(place: String): String {
        val geo = Uri.parse("geo:0,0?q=${Uri.encode(place)}")
        if (launch(Intent(Intent.ACTION_VIEW, geo))) return "Opening directions to $place."
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(place)}")
        return if (launch(Intent(Intent.ACTION_VIEW, web))) {
            "Opening maps for $place."
        } else {
            "I couldn't open maps."
        }
    }

    // ---------------------------------------------------------------- learning

    private fun learn(command: Command.Learn): String {
        if (command.phrase.isBlank() || command.action.isBlank()) return "What should I learn?"
        skills.learn(command.phrase, command.action)
        val understood = CommandParser(skills.learned()).parse(command.action)
        val known = understood !is Command.Chat && understood !is Command.Unknown
        return if (known) {
            "Got it. When you say \"${command.phrase}\", I'll ${command.action}."
        } else {
            "I'll remember that \"${command.phrase}\" means \"${command.action}\", but I'm not sure how to do that yet."
        }
    }

    private fun forget(phrase: String): String {
        if (phrase.equals("all", true) || phrase.contains("everything", true)) {
            skills.clearLearned()
            return "I forgot everything you taught me."
        }
        return if (skills.forget(phrase)) {
            "I forgot \"$phrase\"."
        } else {
            "I don't have a command for \"$phrase\"."
        }
    }

    private fun help(): String = "I can open apps, call or message people, set alarms, timers and reminders, " +
        "keep notes, control volume, brightness and flashlight, scroll, tap, type, read the screen, " +
        "toggle Wi-Fi or Bluetooth, search the web and give directions. You can also teach me: " +
        "“when I say music, open Spotify”."

    // ------------------------------------------------------------------ helpers

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (t: Throwable) {
        false
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: ActivityNotFoundException) {
        false
    } catch (t: SecurityException) {
        false
    } catch (t: Throwable) {
        false
    }

    private fun noContact(contact: String): String =
        "I couldn't find \"$contact\" in your contacts. Try the full name, or allow contacts access in Robo."

    private fun resolveContact(query: String): ContactMatch? {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 7) return ContactMatch(query, digits)

        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val matches = ArrayList<ContactMatch>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (cursor.moveToNext() && matches.size < 2000) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex)
                        ?.replace(Regex("[\\s()\\-]"), "")
                        ?.takeIf { it.isNotBlank() }
                        ?: continue
                    matches.add(ContactMatch(name, number))
                }
            }
        } catch (t: Throwable) {
            return null
        }

        val best = matches.maxByOrNull { similarity(query, it.name) } ?: return null
        return if (similarity(query, best.name) >= 0.6) best else null
    }

    private data class ContactMatch(val name: String, val number: String)

    private companion object {
        const val NO_ACCESSIBILITY =
            "I need my accessibility service switched on for that. Open Robo and tap the Accessibility row."

        @Volatile
        var torchOn = false

        val PREFERRED_PACKAGES = mapOf(
            "whatsapp" to "com.whatsapp",
            "whats app" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "telegram" to "org.telegram.messenger",
            "facebook" to "com.facebook.katana",
            "x" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "netflix" to "com.netflix.mediaclient",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user"
        )
    }
}
