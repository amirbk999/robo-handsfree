package com.robo.handsfree.core

/**
 * A command meaning that is independent of the language it was spoken in.
 * The language packs map words from every supported language onto these.
 */
enum class Intent {
    OPEN, CALL, MESSAGE, ALARM, TIMER, REMINDER, NOTE, READ_NOTES,
    WIFI, BLUETOOTH, AIRPLANE, DATA, FLASHLIGHT,
    VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE,
    BRIGHTNESS_UP, BRIGHTNESS_DOWN,
    SCREENSHOT, CAMERA, SCROLL_DOWN, SCROLL_UP, BACK, HOME, RECENTS,
    READ_SCREEN, TAP, TYPE, BATTERY, TIME, DATE, LOCK,
    SEARCH, NAVIGATE, SETTINGS, NOTIFICATIONS, QUICK_SETTINGS,
    PLAY, PAUSE, NEXT, PREVIOUS, HELP, STOP, START
}

/** One language's vocabulary: which words trigger which intent, plus its grammar glue. */
class LanguagePack(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val tag: String,
    val triggers: List<Pair<String, Intent>>,
    val prefixes: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val connectors: List<String> = emptyList()
)

/** A word or phrase found in a sentence, with the characters it occupies. */
data class Hit(
    val pack: LanguagePack,
    val intent: Intent,
    val start: Int,
    val end: Int,
    val trigger: String
)

/**
 * Finds the command word in a sentence, in any supported language, and extracts the part
 * that matters ("WhatsApp", "Mum", "7 am") whichever side of the verb it sits on.
 *
 * It is deliberately forgiving: mixed sentences like "Mummy ko WhatsApp kholo" work because
 * every language's words are searched at once, and the longest match wins.
 */
class LanguageMatcher(private val languageTag: String = "auto") {

    /** Best match in [text], or null when nothing is recognised. */
    fun find(text: String): Hit? {
        val folded = foldForSearch(text)
        if (folded.isBlank()) return null

        var best: Hit? = null
        var bestScore = Int.MIN_VALUE

        for (pack in LanguagePacks.all) {
            for ((trigger, intent) in pack.triggers) {
                val start = locate(folded, trigger) ?: continue
                val end = start + trigger.length
                val languageBonus = if (prefers(pack)) 6 else 0
                val score = trigger.replace(" ", "").length * 10 + languageBonus - minOf(start, 9)
                if (score > bestScore) {
                    bestScore = score
                    best = Hit(pack, intent, start, end, trigger)
                }
            }
        }
        return best
    }

    /** All places where a specific intent is spoken, best first. */
    fun hits(text: String, intent: Intent): List<Hit> {
        val folded = foldForSearch(text)
        val found = mutableListOf<Hit>()
        for (pack in LanguagePacks.all) {
            for ((trigger, mapped) in pack.triggers) {
                if (mapped != intent) continue
                val start = locate(folded, trigger) ?: continue
                found += Hit(pack, intent, start, start + trigger.length, trigger)
            }
        }
        return found.sortedByDescending { it.end - it.start }
    }

    /**
     * The meaningful part of the sentence around a command word. Sentences put the object
     * before the verb in Hindi, Japanese, Korean and Turkish, and after it in English and
     * most European languages, so both sides are cleaned and the non-empty one wins.
     */
    fun slot(text: String, hit: Hit): String {
        val before = cleanSlot(text.substring(0, hit.start.coerceIn(0, text.length)), hit.pack)
        val after = cleanSlot(text.substring(hit.end.coerceIn(0, text.length)), hit.pack)
        return after.ifEmpty { before }
    }

    /** Text exactly as spoken after the command word, used for notes, typing and messages. */
    fun rawAfter(text: String, hit: Hit): String =
        text.substring(hit.end.coerceIn(0, text.length)).trim().trim(*TRIM_CHARS)

    /** Splits "message John saying see you" into the part before and after the connector. */
    fun connectorSplit(text: String, pack: LanguagePack? = null): Pair<String, String>? {
        val folded = foldForSearch(text)
        val candidates = if (pack == null) CONNECTORS else CONNECTORS + pack.connectors
        var bestStart = -1
        var bestEnd = -1
        for (connector in candidates) {
            val start = locateFolded(folded, connector) ?: continue
            val end = start + connector.length
            val length = connector.length
            if (length > bestEnd - bestStart) {
                bestStart = start
                bestEnd = end
            }
        }
        if (bestStart < 0) return null
        val head = text.substring(0, bestStart).trim()
        val body = text.substring(bestEnd).trim().trim(*TRIM_CHARS)
        return if (body.isEmpty()) null else head to body
    }

    fun cleanSlot(raw: String, pack: LanguagePack? = null): String {
        var value = raw.trim().trim(*TRIM_CHARS)
        if (value.isEmpty()) return ""
        var words = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        while (words.size > 1 && (isPrefix(words.first()) || packHas(pack?.prefixes, words.first()))) {
            words = words.drop(1)
        }
        while (words.size > 1 && (isSuffix(words.last()) || packHas(pack?.suffixes, words.last()))) {
            words = words.dropLast(1)
        }
        if (words.isNotEmpty()) {
            words = words.dropLast(1) + stripAttached(words.last())
        }
        value = words.filter { it.isNotBlank() }.joinToString(" ")
        return value.trim(*TRIM_CHARS).trim()
    }

    /** Korean, Japanese and Hindi glue their particles onto the noun: 엄마에게 → 엄마. */
    private fun stripAttached(word: String): String {
        for (particle in ATTACHED_PARTICLES) {
            if (word.length > particle.length + 1 && word.endsWith(particle)) {
                return word.dropLast(particle.length)
            }
        }
        return word
    }

    /** On / off meaning for toggles and the flashlight. */
    fun direction(text: String): ToggleDirection? {
        val folded = foldForSearch(text)
        val off = OFF_WORDS.any { containsPhrase(folded, it) }
        val on = ON_WORDS.any { containsPhrase(folded, it) }
        return when {
            off -> ToggleDirection.OFF
            on -> ToggleDirection.ON
            else -> null
        }
    }

    // -------------------------------------------------------------- internals

    private fun isPrefix(word: String): Boolean = PREFIX_WORDS.contains(normalizeText(word))

    private fun isSuffix(word: String): Boolean = SUFFIX_WORDS.contains(normalizeText(word))

    private fun packHas(words: List<String>?, word: String): Boolean =
        words != null && words.contains(normalizeText(word))

    private fun prefers(pack: LanguagePack): Boolean {
        if (languageTag.isBlank() || languageTag.equals("auto", ignoreCase = true)) return false
        return pack.tag.equals(languageTag, ignoreCase = true) ||
            pack.tag.substringBefore('-').equals(languageTag.substringBefore('-'), ignoreCase = true) ||
            pack.code.equals(languageTag.substringBefore('-'), ignoreCase = true)
    }

    private fun locate(folded: String, trigger: String): Int? = locateFolded(folded, trigger)

    private fun locateFolded(folded: String, trigger: String): Int? {
        if (trigger.isEmpty()) return null
        var index = folded.indexOf(trigger)
        if (isSpaceless(trigger)) return if (index >= 0) index else null
        while (index >= 0) {
            if (isWordBoundary(folded, index, trigger.length)) return index
            index = folded.indexOf(trigger, index + 1)
        }
        return null
    }

    private fun containsPhrase(folded: String, phrase: String): Boolean {
        val needle = phrase.trim()
        if (needle.isEmpty()) return false
        if (isSpaceless(needle)) return folded.contains(needle)
        val padded = " $folded "
        return padded.contains(" $needle ")
    }

    companion object {
        private val TRIM_CHARS = charArrayOf(
            '"', '\'', '.', ',', '!', '?', ':', ';', '-', '—', '،', '。', '、', '؟', '“', '”', '‘', '’', ')', '('
        )

        val PREFIX_WORDS: Set<String> = buildSet {
            val words = listOf(
                "the", "a", "an", "to", "on", "in", "at", "of", "for", "my", "me", "please",
                "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "mein", "meine", "zum", "zur",
                "le", "la", "les", "un", "une", "des", "mon", "ma", "mes", "au", "aux",
                "el", "la", "los", "las", "un", "una", "mi", "mis", "al", "del",
                "il", "lo", "gli", "i", "le", "una", "mio", "mia", "al", "allo",
                "o", "os", "as", "um", "uma", "meu", "minha", "no", "na", "ao",
                "de", "het", "een", "op", "aan", "mijn", "naar",
                "på", "til", "min", "mitt", "till", "och",
                "к", "во", "на", "до", "для", "в", "с", "мой", "мою", "моя",
                "до", "у", "з", "для", "мій", "мою",
                "do", "na", "dla", "moj", "moją", "proszę",
                "إلى", "الى", "على", "علي", "في", "من", "عن", "يا", "لي", "ل",
                "به", "را", "برای", "از", "تو", "در",
                "को", "का", "की", "के", "में", "पर", "से", "मेरी", "मेरा",
                "کے", "کو", "کی", "کا", "میں", "پر", "سے", "میرا",
                "কে", "এর", "আমার", "তে", "এ",
                "ਨੂੰ", "ਦਾ", "ਦੀ", "ਦੇ", "ਵਿੱਚ", "ਤੇ", "ਮੇਰਾ",
                "ને", "નો", "ની", "ને", "માં", "પર", "મારું",
                "க்கு", "ஐ", "என்", "இல்", "மேல்",
                "కి", "ను", "నా", "లో", "మీద",
                "ಗೆ", "ಅನ್ನು", "ನನ್ನ", "ನಲ್ಲಿ", "ಮೇಲೆ",
                "ക്ക്", "എന്റെ", "ഇൽ", "മേൽ",
                "に", "を", "へ", "で", "は", "が", "の",
                "에게", "을", "를", "에", "내", "나의",
                "的", "把", "给", "給", "我的",
                "ที่", "ให้", "ของ", "ไปที่",
                "ke", "di", "untuk", "pada", "kepada", "saya", "aku",
                "cho", "đến", "của", "tôi", "giúp",
                "να", "σε", "στον", "στην", "του", "της", "για", "μου",
                "a", "para", "por", "favor", "mim", "minha", "meu",
                "i", "iz", "u", "na", "za", "moj", "moja",
                "na", "kwa", "ya", "yangu",
                "om", "te", "op", "vir", "my",
                "a", "na", "do", "da"
            )
            words.forEach { add(normalizeText(it)) }
        }

        val SUFFIX_WORDS: Set<String> = buildSet {
            val words = listOf(
                "please", "now", "for me", "thanks", "thank you",
                "karo", "kar", "karna", "kijiye", "kijie", "kro", "kardo", "kar do", "kar dena",
                "laga", "lagao", "lagado", "lago", "lagana",
                "bhejo", "bhej", "bhejna", "bhejiye", "bhejdo",
                "chalao", "chala", "bajao", "baja", "batao", "bata", "dikhao", "dikha",
                "do", "de", "dena", "dijiye", "lo", "lijiye", "le", "lena",
                "ko", "ka", "ki", "ke", "se", "mein", "par", "mujhe", "mera",
                "کرو", "کریں", "کر", "لگاؤ", "بھیجو", "چلاؤ", "بتاؤ", "دو", "کو", "کی", "کا", "سے", "مجھے",
                "করো", "কর", "করুন", "পাঠাও", "চালাও", "বলো", "দাও", "কে", "আমার",
                "ਕਰੋ", "ਭੇਜੋ", "ਚਲਾਓ", "ਦੱਸੋ", "ਦਿਓ", "ਨੂੰ",
                "કરો", "મોકલો", "ચલાવો", "કહો", "આપો", "ને",
                "செய்", "செய்யுங்கள்", "அனுப்பு", "இயக்கு", "சொல்", "கொடு", "க்கு",
                "చెయ్యి", "చేయి", "పంపు", "ప్లే", "చెప్పు", "ఇవ్వు", "కి",
                "ಮಾಡು", "ಕಳುಹಿಸು", "ಹಾಕು", "ಹೇಳು", "ಕೊಡು", "ಗೆ",
                "ചെയ്യൂ", "അയക്കൂ", "വയ്ക്കൂ", "പറയൂ", "തരൂ", "ക്ക്",
                "bitte", "jetzt", "mal", "für mich", "doch",
                "s'il te plaît", "s'il vous plaît", "stp", "maintenant", "pour moi", "svp",
                "por favor", "porfa", "ahora", "para mí", "ya",
                "per favore", "per piacere", "adesso", "per me", "ti prego",
                "por favor", "agora", "para mim", "já",
                "alsjeblieft", "alstublieft", "nu", "voor mij", "even",
                "snälla", "nu", "åt mig", "tack",
                "venligst", "nu", "for mig", "tak",
                "vær så snill", "nå", "for meg", "takk",
                "kiitos", "nyt", "minulle", "ole hyvä",
                "te rog", "acum", "pentru mine", "vă rog",
                "kérlek", "most", "nekem", "legyen",
                "пожалуйста", "сейчас", "мне", "будь ласка", "зараз", "мені",
                "proszę", "teraz", "mi", "prosím", "teď", "pro mě",
                "molim", "sada", "meni", "моля", "сега", "на мен",
                "من فضلك", "لو سمحت", "الآن", "لي", "بليز",
                "لطفا", "الان", "برای من", "لطفاً",
                "lütfen", "şimdi", "benim için", "lutfen",
                "בבקשה", "עכשיו", "לי",
                "παρακαλώ", "τώρα", "για μένα",
                "tolong", "sekarang", "untuk saya", "sila",
                "làm ơn", "ngay bây giờ", "giúp tôi", "đi",
                "กรุณา", "ตอนนี้", "ให้ฉัน", "หน่อย", "ด้วย",
                "paki", "ngayon", "para sa akin", "nga",
                "请", "现在", "帮我", "一下", "吧",
                "ください", "お願いします", "今", "私に", "を",
                "주세요", "지금", "나에게", "해줘", "좀",
                "tafadhali", "sasa", "kwa ajili yangu",
                "asseblief", "nou", "vir my",
                "molim", "sada", "za mene"
            )
            words.forEach { add(normalizeText(it)) }
        }

        private val ATTACHED_PARTICLES = listOf(
            "에게", "한테", "께서", "으로", "에서", "부터", "まで", "から", "には", "とは",
            "을", "를", "은", "는", "이", "가", "에", "로", "와", "과", "의", "도", "만",
            "を", "へ", "で", "は", "が", "の", "को", "ને", "কে", "வை", "க்கு"
        )

        /**
         * English connectors plus each pack's own connectors (see [LanguagePack.connectors]).
         * Message nouns are deliberately not connectors: they name the command, not the body.
         */
        private val CONNECTORS: List<String> = listOf(
            "saying", "saying that", "that says", "with the message", "with message", "message saying",
            "that", "tell them", "tell her", "tell him"
        ).map { normalizeText(it) }

        private val OFF_WORDS = listOf(
            "off", "disable", "deactivate", "stop", "turn off", "switch off",
            "aus", "deaktivieren", "ausschalten", "abschalten",
            "éteins", "eteins", "éteindre", "désactiver", "desactiver", "coupe",
            "apagar", "apaga", "desactivar", "desactiva", "desligar", "desliga", "off",
            "spegni", "spegnere", "disattiva", "off",
            "desligue", "desligar", "desativa",
            "uit", "uitschakelen", "uitzetten",
            "av", "stäng av", "slå av", "inaktivera",
            "sluk", "slå fra", "deaktiver",
            "av", "slå av", "skru av", "deaktiver",
            "pois", "sammuta", "sulje",
            "oprește", "opreste", "dezactivează", "închide",
            "ki", "kikapcsol", "kikapcsolás",
            "выключи", "выключить", "отключи", "отключить", "выкл",
            "вимкни", "вимкнути", "відключи",
            "wyłącz", "wylacz", "wyłączyć", "wyłącz",
            "vypni", "vypnout", "vypni",
            "isključi", "iskljuci", "ugasni",
            "изключи", "изключване",
            "اطفئ", "أطفئ", "أغلق", "اوقف", "أوقف", "إيقاف", "اقفل",
            "خاموش", "ببند", "قطع کن",
            "kapat", "kapatmak", "durdur", "devre dışı",
            "כבה", "לכבות", "סגור",
            "κλείσε", "απενεργοποίηση", "σβήσε",
            "matikan", "matikan", "nonaktifkan", "tutup",
            "matikan", "tutup", "nyahaktif",
            "tắt", "tắt đi", "ngắt",
            "ปิด", "ปิดใช้งาน",
            "patayin", "i-off", "isara",
            "关", "關", "关闭", "關閉", "关掉",
            "消して", "オフ", "切って", "止めて",
            "꺼", "끄기", "끄세요", "잠금 해제",
            "zima", "zima", "funga",
            "af", "skakel af", "verduister",
            "isključi", "ugasni", "zatvori",
            "बंद", "बन्द", "ऑफ", "ओफ",
            "بند", "آف",
            "বন্ধ", "অফ",
            "ਬੰਦ", "ਆਫ",
            "બંધ", "ઓફ",
            "அணை", "ஆஃப்", "நிறுத்து",
            "ఆఫ్", "ఆపు", "బంద్",
            "ಆಫ್", "ನಿಲ್ಲಿಸು", "ಬಂದ್",
            "ഓഫ്", "നിർത്തൂ", "ബന്ധ്"
        ).map { normalizeText(it) }

        private val ON_WORDS = listOf(
            "on", "enable", "activate", "turn on", "switch on",
            "an", "einschalten", "aktivieren", "anschalten",
            "allume", "allumer", "active", "activer", "ouvre",
            "encender", "activar", "prender", "enciende",
            "accendi", "accendere", "attiva", "attivare",
            "ligar", "ligue", "ativar", "acender",
            "aan", "inschakelen", "aanzetten",
            "på", "slå på", "aktivera", "tänd",
            "tænd", "slå til", "aktiver",
            "på", "slå på", "skru på", "aktiver",
            "päälle", "kytke", "avaa",
            "pornește", "porneste", "activează", "deschide",
            "be", "bekapcsol", "kapcsold be",
            "включи", "включить", "вруби", "вкл",
            "увімкни", "увімкнути", "включи",
            "włącz", "wlacz", "włączyć",
            "zapni", "zapnout", "zapni",
            "uključi", "ukljuci", "upali",
            "включи", "включване",
            "شغل", "أشعل", "افتح", "تشغيل",
            "روشن", "باز کن", "فعال کن",
            "aç", "açık", "etkinleştir", "yak",
            "הדלק", "הפעל", "פתח",
            "άναψε", "ενεργοποίηση", "άνοιξε",
            "nyalakan", "hidupkan", "aktifkan", "buka",
            "hidupkan", "nyalakan", "buka",
            "bật", "mở", "bật lên",
            "เปิด", "เปิดใช้งาน",
            "i-on", "buksan", "ilawan",
            "开", "開", "打开", "開啟", "开灯",
            "つけて", "オン", "開いて", "点けて",
            "켜", "켜기", "켜세요", "열어",
            "washa", "fungua", "washe",
            "aan", "skakel aan",
            "uključi", "upali", "otvori",
            "चालू", "ऑन", "ओन", "जलाओ",
            "چالو", "آن", "جلاؤ",
            "চালু", "অন", "জ্বালাও",
            "ਚਾਲੂ", "ਆਨ",
            "ચાલુ", "ઓન",
            "இயக்கு", "ஆன்",
            "ఆన్", "ఆన్ చెయ్", "వెలిగించు",
            "ಆನ್", "ಹಚ್ಚು",
            "ഓൺ", "തെളിക്കൂ"
        ).map { normalizeText(it) }

        /** Words to drop when looking for an app or contact name. */
        val STOP_WORDS: Set<String> = PREFIX_WORDS + SUFFIX_WORDS
    }
}

/** True for scripts written without spaces, where word boundaries do not help. */
internal fun isSpaceless(text: String): Boolean = text.any { ch ->
    val code = ch.code
    (code in 0x3040..0x30FF) || // hiragana + katakana
        (code in 0x3400..0x9FFF) || // CJK
        (code in 0xAC00..0xD7AF) || // hangul
        (code in 0x0E00..0x0E7F) || // thai
        (code in 0x0E80..0x0EFF) // lao
}
