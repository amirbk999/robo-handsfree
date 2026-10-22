package com.robo.handsfree.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One command per language family, spoken the way a native speaker would say it.
 * These run on the JVM, so the whole language layer is verified without a phone.
 */
class MultilingualTest {

    private val now = 1_735_689_600_000L // 2025-01-01 00:00 UTC

    private fun parse(text: String): Command = CommandParser().parse(text, now)

    // ------------------------------------------------------------------- Hindi

    @Test
    fun hindiOpensAnApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("व्हाट्सएप खोलो"))
    }

    @Test
    fun hindiCallsContactWithDativeParticle() {
        assertEquals(Command.Call("मम्मी"), parse("मम्मी को फोन करो"))
    }

    @Test
    fun hindiSendsMessageWithConnector() {
        assertEquals(
            Command.Message("मम्मी", "मैं देर से आऊंगा", MessageChannel.SMS),
            parse("मम्मी को मैसेज भेजो कि मैं देर से आऊंगा")
        )
    }

    @Test
    fun hindiSetsAlarmFromWordNumber() {
        assertEquals(Command.SetAlarm(7, 0, ""), parse("सात बजे अलार्म लगाओ"))
    }

    @Test
    fun hindiTurnsTorchOff() {
        assertEquals(Command.Flashlight(ToggleDirection.OFF), parse("टॉर्च बंद करो"))
    }

    @Test
    fun hindiRaisesVolume() {
        assertEquals(Command.Volume(VolumeAction.UP), parse("आवाज बढ़ाओ"))
    }

    @Test
    fun hindiSetsTimerFromMinutes() {
        assertEquals(Command.SetTimer(600), parse("10 मिनट का टाइमर लगाओ"))
    }

    @Test
    fun hindiHelpAndWakeWord() {
        assertEquals(Command.Help, parse("मदद करो"))
        assertEquals(Command.OpenApp("WhatsApp"), parse("रोबो, व्हाट्सएप खोलो"))
    }

    // ------------------------------------------------------------------ Spanish

    @Test
    fun spanishOpensAnApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("abre WhatsApp"))
    }

    @Test
    fun spanishCallsWithPreposition() {
        assertEquals(Command.Call("mamá"), parse("llama a mamá"))
    }

    @Test
    fun spanishAlarmWithTime() {
        assertEquals(Command.SetAlarm(7, 30, ""), parse("pon una alarma para las 7:30"))
    }

    @Test
    fun spanishFlashlightOff() {
        assertEquals(Command.Flashlight(ToggleDirection.OFF), parse("apaga la linterna"))
    }

    @Test
    fun spanishTimerWithHours() {
        assertEquals(Command.SetTimer(7200), parse("pon un temporizador de 2 horas"))
    }

    // ------------------------------------------------------------------- French

    @Test
    fun frenchOpensYouTube() {
        assertEquals(Command.OpenApp("YouTube"), parse("ouvre YouTube"))
    }

    @Test
    fun frenchCallsContact() {
        assertEquals(Command.Call("maman"), parse("appelle maman"))
    }

    // ------------------------------------------------------------------- German

    @Test
    fun germanOpensAnApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("öffne WhatsApp"))
    }

    @Test
    fun germanAlarmWithUhr() {
        assertEquals(Command.SetAlarm(7, 0, ""), parse("stelle einen Wecker für 7 Uhr"))
    }

    @Test
    fun germanTurnsTorchOff() {
        assertEquals(Command.Flashlight(ToggleDirection.OFF), parse("mach die Taschenlampe aus"))
    }

    // ------------------------------------------------------------------ Russian

    @Test
    fun russianCallsContact() {
        assertEquals(Command.Call("маме"), parse("позвони маме"))
    }

    @Test
    fun russianOpensYouTubeAlias() {
        assertEquals(Command.OpenApp("YouTube"), parse("открой ютуб"))
    }

    // --------------------------------------------------------------- Portuguese

    @Test
    fun portugueseOpensAnAppWithoutArticle() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("abre o WhatsApp"))
    }

    // ------------------------------------------------------------------ Italian

    @Test
    fun italianOpensAnApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("apri WhatsApp"))
    }

    // ------------------------------------------------------------------- Arabic

    @Test
    fun arabicOpensCamera() {
        assertEquals(Command.Camera, parse("افتح الكاميرا"))
    }

    // ------------------------------------------------------------------ Turkish

    @Test
    fun turkishRaisesVolumeWithoutConfusingOpen() {
        assertEquals(Command.Volume(VolumeAction.UP), parse("sesi aç"))
    }

    // ------------------------------------------------------------------ Chinese

    @Test
    fun chineseOpensCamera() {
        assertEquals(Command.Camera, parse("打开相机"))
    }

    // ----------------------------------------------------------------- Japanese

    @Test
    fun japaneseOpensCamera() {
        assertEquals(Command.Camera, parse("カメラを開いて"))
    }

    // ------------------------------------------------------------------- Korean

    @Test
    fun koreanMessagesContactWithAttachedParticle() {
        assertEquals(
            Command.Message("엄마", "", MessageChannel.SMS),
            parse("엄마에게 메시지 보내")
        )
    }

    @Test
    fun koreanRaisesVolume() {
        assertEquals(Command.Volume(VolumeAction.UP), parse("볼륨 올려"))
    }

    // --------------------------------------------------------------------- Thai

    @Test
    fun thaiOpensCamera() {
        assertEquals(Command.Camera, parse("เปิดกล้อง"))
    }

    // ------------------------------------------------- Indian + other languages

    @Test
    fun bengaliOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("হোয়াটসঅ্যাপ খোলো"))
    }

    @Test
    fun tamilOpensCamera() {
        assertEquals(Command.Camera, parse("கேமரா திற"))
    }

    @Test
    fun urduOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("واٹس ایپ کھولو"))
    }

    @Test
    fun ukrainianOpensYouTube() {
        assertEquals(Command.OpenApp("YouTube"), parse("відкрий YouTube"))
    }

    @Test
    fun greekOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("άνοιξε WhatsApp"))
    }

    @Test
    fun hebrewOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("פתח וואטסאפ"))
    }

    @Test
    fun dutchOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("open WhatsApp"))
    }

    @Test
    fun indonesianOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("buka WhatsApp"))
    }

    @Test
    fun vietnameseOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("mở WhatsApp"))
    }

    @Test
    fun swahiliOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("fungua WhatsApp"))
    }

    @Test
    fun polishOpensWhatsApp() {
        assertEquals(Command.OpenApp("WhatsApp"), parse("otwórz WhatsApp"))
    }

    // -------------------------------------------------------- wake word coverage

    @Test
    fun wakeWordIsRecognisedInManyScripts() {
        assertTrue(containsWakeWord("Robo, open WhatsApp"))
        assertTrue(containsWakeWord("रोबो, व्हाट्सएप खोलो"))
        assertTrue(containsWakeWord("ロボ、カメラを開いて"))
        assertTrue(containsWakeWord("로보 메시지 보내"))
        assertTrue(containsWakeWord("روبوت افتح الكاميرا"))
        assertTrue(!containsWakeWord("open WhatsApp"))
    }

    // ------------------------------------------------- English must be untouched

    @Test
    fun englishKeepsWorking() {
        assertEquals(Command.Call("mom"), parse("call mom"))
        assertEquals(Command.ReadNotes, parse("read my notes"))
        assertEquals(Command.SetAlarm(7, 0, ""), parse("set an alarm for 7 am"))
        assertEquals(Command.OpenApp("WhatsApp"), parse("open WhatsApp"))
        assertEquals(Command.Screenshot, parse("take a screenshot"))
    }

    @Test
    fun hinglishIsUnderstood() {
        assertEquals(Command.Call("mummy"), parse("mummy ko call karo"))
        assertEquals(Command.OpenApp("WhatsApp"), parse("open karo WhatsApp"))
    }

    @Test
    fun englishChatIsNotHijackedByForeignTriggers() {
        assertEquals("Chat", parse("I feel a bit lonely today")::class.simpleName)
        assertEquals("Chat", parse("how are you today")::class.simpleName)
    }

    @Test
    fun everyLanguagePackHasVocabulary() {
        assertTrue(LanguagePacks.spokenLanguages.size >= 40)
        LanguagePacks.spokenLanguages.forEach { pack ->
            assertTrue("${pack.englishName} has no commands", pack.triggers.isNotEmpty())
        }
    }

    /** Proves the lexicon of every single language can actually open an app. */
    @Test
    fun everyLanguageCanOpenAnApp() {
        val broken = mutableListOf<String>()
        LanguagePacks.spokenLanguages.forEach { pack ->
            val verb = pack.triggers.firstOrNull { it.second == Intent.OPEN }?.first
            if (verb == null) {
                broken += "${pack.code}: no open verb"
                return@forEach
            }
            val command = parse("$verb WhatsApp")
            if (command != Command.OpenApp("WhatsApp")) broken += "${pack.code}: $command"
        }
        assertTrue(broken.joinToString(", "), broken.isEmpty())
    }
}
