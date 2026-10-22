package com.robo.handsfree.core

import java.util.Locale

/**
 * Time and duration words in many languages.
 *
 * Speech recognisers usually return digits for times ("19:30", "7 baje"), so digits are the
 * main path. Word numbers and clock markers are handled for the languages where people
 * most often speak them out loud.
 */
object TimeLanguage {

    // ------------------------------------------------------------------ numbers

    /**
     * Rewrites every kind of digit and many spoken numbers into plain 0-9, keeping
     * punctuation such as "7:30" intact so clock parsing still works.
     */
    fun normalizeNumbers(value: String): String {
        val kept = StringBuilder(value.length)
        for (ch in value) {
            val lower = ch.lowercaseChar()
            if (lower.isDigit() && lower.code > '9'.code) {
                val decimal = Character.digit(lower, 10)
                kept.append(if (decimal >= 0) ('0' + decimal) else lower)
            } else {
                kept.append(lower)
            }
        }
        var text = kept.toString().replace(Regex("\\s+"), " ")
        text = replaceChineseNumbers(text)
        val tokens = text.split(' ')
        val rewritten = tokens.joinToString(" ") { token ->
            val key = normalizeText(token)
            val number = WORD_NUMBERS[key]
            number?.toString() ?: token
        }
        return rewritten
    }

    private fun replaceChineseNumbers(text: String): String =
        Regex("([一二三四五六七八九十]{1,3})(?=\\s*(?:点|點|時|时|分|秒|時半|点半))").replace(text) { match ->
            chineseNumber(match.groupValues[1]).toString()
        }

    private fun chineseNumber(value: String): Int {
        if (value == "十") return 10
        val tenIndex = value.indexOf('十')
        return if (tenIndex >= 0) {
            val tens = if (tenIndex == 0) 1 else chineseDigit(value[0])
            val units = if (tenIndex == value.lastIndex) 0 else chineseDigit(value[tenIndex + 1])
            tens * 10 + units
        } else {
            chineseDigit(value[0])
        }
    }

    private fun chineseDigit(ch: Char): Int = when (ch) {
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> 0
    }

    // --------------------------------------------------------------------- clock

    /** Returns (hour, minute) in 24 hour form, or null when no time can be found. */
    fun clockTime(value: String): Pair<Int, Int>? {
        val text = normalizeNumbers(value)

        COLON_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            return applyMeridian(hour, minute, text)
        }

        CJK_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            return applyMeridian(hour, minute, text)
        }

        MARKED_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val half = match.groupValues[3].isNotBlank() ||
                HALF_WORDS.any { text.contains(it) }
            return applyMeridian(hour, if (half) 30 else minute, text)
        }

        BARE_HOUR.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            if (hour !in 0..23) return@let
            val minute = if (HALF_WORDS.any { text.contains(it) }) 30 else 0
            return applyMeridian(hour, minute, text)
        }
        return null
    }

    private fun applyMeridian(hour: Int, minute: Int, text: String): Pair<Int, Int>? {
        if (minute !in 0..59 || hour !in 0..23) return null
        var h = hour
        val afternoon = AFTERNOON_WORDS.any { containsWord(text, it) }
        val morning = MORNING_WORDS.any { containsWord(text, it) }
        if (afternoon && h in 1..11) h += 12
        if (morning && h == 12) h = 0
        return h to minute
    }

    // ------------------------------------------------------------------ duration

    /** Reads "10 minutes", "zwei Stunden", "20 मिनट", "半小时" into seconds. */
    fun durationSeconds(value: String): Int? {
        val text = normalizeNumbers(value)
        var best: Int? = null

        NUMBER_UNIT.findAll(text).forEach { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@forEach
            val unitWord = match.groupValues[2]
            val multiplier = unitMultiplier(unitWord) ?: return@forEach
            best = amount * multiplier
        }
        if (best != null) return best

        val hasHalfHour = HALF_HOUR_PHRASES.any { text.contains(it) }
        if (hasHalfHour) return 30 * 60

        val quarterHour = QUARTER_HOUR_PHRASES.any { text.contains(it) }
        if (quarterHour) return 15 * 60

        return null
    }

    private fun unitMultiplier(raw: String): Int? {
        val word = foldForSearch(raw).trim()
        return when {
            HOUR_STEMS.any { word.startsWith(it) } -> 3600
            MINUTE_STEMS.any { word.startsWith(it) } -> 60
            SECOND_STEMS.any { word.startsWith(it) } -> 1
            else -> null
        }
    }

    private fun containsWord(text: String, word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return false
        val index = text.indexOf(trimmed)
        if (index < 0) return false
        val beforeOk = index == 0 || !isWordCharacter(text[index - 1])
        val after = index + trimmed.length
        val afterOk = after >= text.length || !isWordCharacter(text[after])
        return beforeOk && afterOk
    }

    // -------------------------------------------------------------------- tables

    private val COLON_TIME = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})")
    private val CJK_TIME = Regex("(\\d{1,2})\\s*(?:点|點|時|时|시)\\s*(?:(\\d{1,2})\\s*(?:分|분))?")
    private val MARKED_TIME = Regex(
        "(\\d{1,2})\\s*(?:बजे|बजकर|वाजता|ਵਜੇ|টা|টায়|ಗಂಟೆ|ಗಂಟೆಗೆ|గంట|മണി|மணிக்கு|baje|bajkar|" +
            "uhr|heures?|h|ore|horas?|час(?:а|ов)?|годин(?:и)?|ساعة|الساعة|घंटे|kl(?:ockan)?|" +
            "klokka|jam|giờ|ชั่วโมง|นาฬิกา|órakor|ora)\\s*(?:(\\d{1,2})\\s*(?:मिनट|minuten|minutes?|" +
            "minutos?|minuti|دقيقة|دقائق|分|분|นาที|minut|minuty)\\s*(half|et demie|y media|halb|e mezza|e meia|개)?)?"
    )
    private val BARE_HOUR = Regex("(?:^|\\s)(\\d{1,2})(?=\\s|$)")

    private val NUMBER_UNIT = Regex("(\\d{1,4})\\s*([\\p{L}\\p{M}]+)")

    private val HALF_WORDS = listOf("half", "et demie", "y media", "halb", "e mezza", "e meia", "half past")
    private val HALF_HOUR_PHRASES = listOf(
        "half an hour", "half hour", "halbe stunde", "demi heure", "media hora", "meia hora",
        "mezz'ora", "mezza ora", "полчаса", "نصف ساعة", "आधा घंटा", "آدھا گھنٹہ", "半小时", "半小時",
        "30分", "30 분", "setengah jam", "yarım saat", "nusu saa", "setengah jam", "முக்கால் மணி நேரம்"
    )
    private val QUARTER_HOUR_PHRASES = listOf(
        "quarter of an hour", "quarter hour", "viertelstunde", "quart d'heure", "cuarto de hora",
        "quarto d'ora", "четверть часа", "ربع ساعة", "पंद्रह मिनट", "一刻钟"
    )

    private val AFTERNOON_WORDS = listOf(
        "pm", "p m", "afternoon", "evening", "night", "nachmittag", "abend", "nacht", "apres midi",
        "soir", "tarde", "noche", "pomeriggio", "sera", "notte", "tarde", "noite", "вечера", "дня",
        "ночи", "вечора", "مساء", "المساء", "بعد الظهر", "شام", "शाम", "रात", "दोपहर", "সন্ধ্যা", "রাত",
        "ਸ਼ਾਮ", "रात", "સાંજ", "ராத்திரி", "మధ్యాహ్నం", "సాయంత్రం", "ಸಂಜೆ", "വൈകുന്നേരം", "下午", "晚上",
        "傍晚", "午後", "夜", "오후", "저녁", "chiều", "tối", "öğleden sonra", "akşam", "gece", "sore",
        "malam", "petang", "ตอนเย็น", "กลางคืน", "popodne", "navečer", "po podne", "tarde", "seara"
    )
    private val MORNING_WORDS = listOf(
        "am", "a m", "morning", "morgens", "früh", "matin", "mañana", "de la mañana", "mattina",
        "mattino", "manhã", "da manhã", "утра", "утром", "ранку", "вранці", "صباح", "الصباح", "صبح",
        "सुबह", "সকাল", "ਸਵੇਰ", "સવાર", "காலை", "ఉదయం", "ಬೆಳಗ್ಗೆ", "രാവിലെ", "上午", "早上", "早晨",
        "午前", "朝", "오전", "아침", "sáng", "sabah", "pagi", "ตอนเช้า", "ujutro", "zjutraj", "dimineața"
    )

    private val HOUR_STEMS = listOf(
        "hour", "hr", "stunde", "hora", "heure", "ora", "ore", "час", "годин", "ساعة", "ساعات",
        "घंटा", "घंटे", "ঘণ্টা", "ਘੰਟਾ", "કલાક", "மணி", "గంట", "ಗಂಟೆ", "മണിക്കൂ", "小时", "小時",
        "時間", "시간", "jam", "giờ", "tiếng", "ชั่วโมง", "saat", "órá", "uur", "timme", "time",
        "tunnin", "tunnin", "godzin", "hodin", "sat", "sat"
    )
    private val MINUTE_STEMS = listOf(
        "minute", "minuten", "minutos", "minuti", "minuto", "минут", "хвилин", "دقيق", "دقائق",
        "मिनट", "মিনিট", "ਮਿੰਟ", "મિનિટ", "நிமிட", "నిమిష", "ನಿಮಿಷ", "മിനിറ്റ", "분", "分", "นาที",
        "menit", "minit", "phút", "dakika", "λεπτ", "דקות", "minut", "minuut", "minuutt", "minutt",
        "perc", "min"
    )
    private val SECOND_STEMS = listOf(
        "second", "sec", "sekund", "segund", "second", "secondi", "секунд", "сек", "ثاني", "सेकंड",
        "সেকেন্ড", "ਸਕਿੰਟ", "સેકન્ડ", "வினாடி", "సెకను", "ಸೆಕೆಂಡ್", "സെക്കൻഡ്", "秒", "초", "detik",
        "saat", "giây", "วินาที", "saniye", "δευτε", "שני", "sekunt", "sekundy", "sekunda"
    )

    private val WORD_NUMBERS: Map<String, Int> = buildMap {
        // English
        listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen"
        ).forEachIndexed { index, word -> put(word, index + 1) }
        listOf("twenty", "thirty", "forty", "fifty").forEachIndexed { index, word ->
            put(word, 20 + index * 10)
        }
        put("half", 30)

        // Hindi / Urdu
        listOf("ek", "do", "teen", "char", "paanch", "chhe", "saat", "aath", "nau", "das", "gyarah", "barah")
            .forEachIndexed { index, word -> put(word, index + 1) }
        listOf("एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ", "दस", "ग्यारह", "बारह")
            .forEachIndexed { index, word -> put(word, index + 1) }
        listOf("ایک", "دو", "تین", "چار", "پانچ", "چھ", "سات", "آٹھ", "نو", "دس")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Bengali
        listOf("ek", "dui", "tin", "char", "panch", "choy", "shat", "aat", "noy", "dosh")
            .forEachIndexed { index, word -> put(word, index + 1) }
        listOf("এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়", "দশ")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Spanish
        listOf(
            "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce", "trece", "catorce", "quince"
        ).forEachIndexed { index, word -> put(word, index + 1) }

        // French
        listOf("un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // German
        listOf("eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwolf")
            .forEachIndexed { index, word -> put(word, index + 1) }
        put("fünf", 5)
        put("zölf", 12)

        // Italian
        listOf("uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci", "undici", "dodici")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Portuguese
        listOf("um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Russian / Ukrainian
        listOf("один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Turkish
        listOf("bir", "iki", "uc", "dort", "bes", "alti", "yedi", "sekiz", "dokuz", "on", "on bir", "on iki")
            .forEachIndexed { index, word -> put(word, index + 1) }
        listOf("üç", "dört", "beş").forEachIndexed { index, word -> put(word, index + 3) }

        // Arabic
        listOf("واحد", "اثنين", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Indonesian / Malay
        listOf("satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan", "sepuluh")
            .forEachIndexed { index, word -> put(word, index + 1) }

        // Kiswahili
        listOf("moja", "mbili", "tatu", "nne", "tano", "sita", "saba", "nane", "tisa", "kumi")
            .forEachIndexed { index, word -> put(word, index + 1) }
    }

}
