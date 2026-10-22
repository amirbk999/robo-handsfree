# Robo — hands-free phone control, by voice

An Android app that lets you use your phone without touching it. Talk, and Robo opens apps,
calls and messages people, sets alarms and reminders, writes notes, scrolls, taps, types,
reads the screen aloud, and keeps you company when there is nobody else around.

- **Install file:** [`dist/Robo-1.1-debug.apk`](dist/Robo-1.1-debug.apk) (Android 8.0+)
- **Languages:** understands commands in **44 languages**, including Hindi, Bengali, Tamil, Telugu,
  Marathi, Punjabi, Urdu, Arabic, Persian, Turkish, Russian, Spanish, French, German, Portuguese,
  Italian, Dutch, Polish, Indonesian, Vietnamese, Thai, Filipino, Chinese, Japanese, Korean and more.
  Pick yours in **Voice settings → Language I speak to you**.
- **Everything stays on the phone.** Robo has no `INTERNET` permission.

---

## 1. Install

1. Copy `dist/Robo-1.1-debug.apk` to the phone (USB, Drive, or `adb install dist/Robo-1.1-debug.apk`).
2. Open the file. Android will ask to allow installs from this source — allow it, install, then open **Robo**.
3. Open **Voice settings → Language I speak to you** and choose your language (or leave *Automatic*
   to use the phone's own language).
4. This is a debug-signed build for personal use. Contact me if you want a Play-ready signed release.

## 2. Turn on what Robo needs

The home screen has a tap-to-fix checklist. All of these are worth doing once:

| Permission | Why | How |
|---|---|---|
| **Microphone** | To hear you at all | Tap the row, choose *Allow* |
| **Calls, contacts, SMS** | “Call Aunty”, “message Rahul saying I'm late” | Tap the row, allow all three |
| **Accessibility service** | The real hands: tapping buttons, typing, scrolling, reading the screen, quick-settings toggles | Tap the row → *Robo hands-free control* → On. Confirm the warning. |
| **Notifications** | Shows the ongoing “Robo is listening” status | Tap the row, allow |
| **Display over other apps** | Optional floating mic bubble | Tap the row, allow, then switch the bubble on in Voice settings |
| **Battery freedom** | Stops Android killing the listener in the background | Tap the row, choose *Don't optimise* for Robo |

> The accessibility service is powerful — it can read the screen and press buttons in any app.
> Only enable it for apps you trust, and feel free to read the source here first. Robo never
> uploads anything: the app has no internet permission at all.

## 3. Use it

1. Tap the big purple mic **once**.
2. Talk. By default Robo only acts when you start with the wake word **“Robo”**:
   *“Robo, open WhatsApp.”*
3. It answers out loud and listens again automatically. To stop, say *“stop listening”* or tap the mic.
4. You can turn the wake word off in **Voice settings** if you would rather have everything you say
   treated as a command. The floating bubble (optional) wakes or pauses Robo from any app.

There is also a text box and one-tap chips for noisy places, and a live transcript of everything said.

### Speaking another language

Choose your language once in **Voice settings**. Speech recognition, the command words Robo listens
for, the wake word and the spoken voice all follow it. Commands work in the same shapes as English:

| Language | Opening an app | Calling | Alarm |
|---|---|---|---|
| हिन्दी | “व्हाट्सएप खोलो” | “मम्मी को फोन करो” | “सात बजे अलार्म लगाओ” |
| Hinglish | “open karo WhatsApp” | “mummy ko call karo” | “7 baje alarm lagao” |
| Español | “abre WhatsApp” | “llama a mamá” | “pon una alarma para las 7:30” |
| Deutsch | “öffne WhatsApp” | “ruf Mama an” | “stelle einen Wecker für 7 Uhr” |
| Français | “ouvre YouTube” | “appelle maman” | “mets un réveil à 7 h” |
| العربية | “افتح الكاميرا” | “اتصل بأمي” | “ضع منبه الساعة 7” |
| Русский | “открой ютуб” | “позвони маме” | “поставь будильник на 7” |
| 中文 | “打开相机” | “打电话给妈妈” | “设置七点闹钟” |
| 日本語 | “カメラを開いて” | “ママに電話して” | “7時に目覚まし” |
| 한국어 | “카메라 열어” | “엄마에게 전화해” | “7시 알람” |

Mixed sentences work too, because every language's vocabulary is searched at once: *“Mummy ko
WhatsApp kholo”* opens WhatsApp. You can also speak with English app names inside any language.
The screen and the small-talk replies remain English for now.

### Try saying

**Apps and settings**
- “Robo, open WhatsApp” · “open YouTube” · “launch the camera”
- “open wifi settings” · “open accessibility settings”

**Calls and messages**
- “Robo, call Aunty” · “dial 9876543210”
- “message mom saying I'll be late” · “text Priya Good morning”
- “send a WhatsApp to Rahul saying Reaching in ten minutes”

**Time**
- “set an alarm for 7:30 am to wake up” · “wake me up at 6”
- “set a timer for 10 minutes”
- “remind me to take medicine at 9 pm” · “remind me in 20 minutes to stretch”
- “what time is it” · “what's the date”

**Notes and memory**
- “take a note buy milk” · “read my notes”
- “when I say music, open Spotify” — Robo remembers it (long-press a learned command to forget it)

**Hands-free screen control**
- “read the screen” · “scroll down” · “go back” · “go home” · “recent apps”
- “tap on Send” · “type hello world” · “open notifications” · “open quick settings”
- “take a screenshot” (Android 11+, saved to Pictures/Robo) · “lock the phone”

**Phone controls**
- “turn on the flashlight” · “volume up” · “set volume to 30” · “mute”
- “brightness up” · “set brightness to 60”
- “turn on Wi-Fi” · “switch off bluetooth” · “turn on airplane mode”
- “play music” · “pause” · “next song” · “what's the battery”

**Out and about**
- “search best chai near me” · “play lofi beats on youtube” · “navigate to India Gate”

**Company**
- “how are you” · “tell me a joke” · “I'm tired” · “good night” · “who are you”

## 4. What is verified, and what is not

Verified in this workspace:

- Clean build: `:app:assembleDebug` → `dist/Robo-1.1-debug.apk`, debug-signed, valid manifest,
  launchable activity, foreground microphone service and accessibility service declared.
- **71 automated tests, all passing:**
  - 46 multilingual tests: commands in 30+ languages, multilingual clock and duration parsing,
    attached particles (엄마에게 → 엄마), Hinglish, wake words in six scripts, and 44 populated packs.
  - 15 English parser tests covering every command family, politeness and taught commands.
  - 6 companion-reply tests, 2 fuzzy app/contact matching tests.
  - 2 Robolectric tests that actually inflate the main screen and run a command end to end into the transcript.

Not verified — no phone or emulator was available in this environment:

- Real speech-recognition accuracy, microphone behaviour and battery drain on your specific phone.
- OEM differences: quick-settings tile labels (Wi-Fi vs Internet), clock-app alarm intents, WhatsApp's Send button.
- Permission dialogs and the accessibility-service toggle on a physical device.

Expect to adjust the first run or two on a real device; the app always speaks a clear message instead of
crashing when something is unavailable.

## 5. Honest limits

- **Understanding is multilingual; conversation is not.** Commands in 44 languages are understood and
  executed, and the voice follows your chosen language, but the on-screen text, spoken confirmations and
  small-talk replies are English. Accents, slang and dialect words outside the vocabulary will fall back
  to English parsing or a friendly “I didn't catch that”.
- **No money-making, trading or payment automation.** Robo will not move money, place orders or invent
  earnings. That needs a regulated account, explicit confirmation and real risk controls; asking a
  voice assistant to trade is a fast way to lose money. It will happily save you time on everything else.
- Wake-word listening depends on Android's speech recogniser, which on many phones uses Google's
  online service, so the microphone audio may leave the device through Google — Robo itself never sends it.
  Continuous listening also costs battery; the floating bubble and pause button exist for that reason.
- Quick toggles work by opening quick settings and pressing the tile. If your phone labels it differently,
  Robo tells you to tap it once rather than guessing. On Android 12+ some phones open a small Wi-Fi/Internet
  panel instead of toggling directly; check the panel that appears.
- Reminders do not survive a phone reboot (there is no boot receiver); re-set them after restarting.
- WhatsApp messages are prepared and sent by pressing the on-screen Send button through accessibility.
  If that button cannot be found, the message stays ready for one tap.
- Accessibility typing uses the standard “set text” action; a few apps with custom text fields may refuse it.
- The screenshot feature needs Android 11+ and the accessibility service enabled.

## 6. How it is built

```
app/src/main/java/com/robo/handsfree/
  core/                     pure Kotlin, unit-tested, no Android imports
    Command.kt              every command as a sealed type
    CommandParser.kt        English rules first, then the multilingual matcher, → Command
    Language.kt             intents, language packs, matcher, slot extraction, on/off words
    LanguagePacks.kt        44 languages of vocabulary + localised app names
    TimeLanguage.kt         digits, number words, clock markers and durations across languages
    ChatCompanion.kt        friendly conversation, jokes, honest answers
    TextUtils.kt            Unicode folding, wake words in many scripts, fuzzy name matching
  VoiceService.kt           foreground listening loop, wake word, TTS replies, notification controls
  RoboAccessibilityService  real taps, text entry, scrolling, screen reading, screenshots, quick toggles
  ActionRunner.kt           executes commands with intents, AlarmManager, AudioManager, CameraManager, contacts
  CommandEngine.kt          speech/text → parser → action → spoken reply
  BubbleService.kt          draggable floating mic
  ReminderReceiver.kt       reminder notification and spoken reminder
  SkillStore.kt             taught commands + notes, stored in SharedPreferences on the device
  RoboSettings.kt           user preferences
  MainActivity.kt           screen, checklist, transcript, learned skills, voice settings
```

Design choices worth knowing:

- **Parser and actions are separate.** The parser is pure Kotlin and fully testable; the executor is the
  only part that touches Android. Adding a new command means one rule, one sealed type, one executor branch.
- **Learning is explicit and inspectable.** “When I say X, do Y” stores a mapping in normalised text and
  shows it on the home screen. Nothing is inferred silently, and long-press deletes it.
- **Failure is spoken, not crashed.** Missing permissions, unavailable contacts or OEM quirks return a
  sentence telling you what to do next.

## 7. Build from source

Requirements: JDK 17 and Android SDK 34 (Android Studio works; `ANDROID_HOME` or `local.properties` needed).

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 71 tests
```

For a rootless Linux/CI box, [`tools/setup-toolchain.sh`](tools/setup-toolchain.sh) downloads a portable
JDK, Android command-line tools and Gradle into `~/toolchain` — that is exactly how this APK was built.

APK SHA-256: `c310877004d5f6a4bf7802dd716292653b5a2c632ea66ea44cdd672f38afbbc1`
