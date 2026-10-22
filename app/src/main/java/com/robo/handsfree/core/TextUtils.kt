package com.robo.handsfree.core

import java.util.Locale

/**
 * Folds a sentence for matching while keeping its length identical, so an index found in
 * the folded copy still points at the same character in what the user actually said.
 *
 * - lowercases letters (works for Cyrillic, Greek, Devanagari and friends)
 * - strips the accents people leave out when they type ("telephone" vs "téléphone")
 * - turns punctuation and whitespace into single spaces, one for one
 */
fun foldForSearch(value: String): String {
    val out = StringBuilder(value.length)
    for (ch in value) {
        val lower = ch.lowercaseChar()
        val folded = LATIN_FOLD[lower] ?: lower
        out.append(if (isWordCharacter(folded)) folded else ' ')
    }
    return out.toString()
}

/**
 * Letters, digits and the combining marks that make up words in Devanagari, Arabic, Thai and
 * friends. Treating a vowel sign as a separator would split "खोलो" into pieces.
 */
fun isWordCharacter(ch: Char): Boolean {
    if (ch.isLetterOrDigit()) return true
    val type = Character.getType(ch)
    return type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt()
}

/** Lowercase, strip accents and punctuation, collapse spaces. Used for matching and skill keys. */
fun normalizeText(value: String): String = foldForSearch(value)
    .replace(Regex("\\s+"), " ")
    .trim()

/** True when the span [start, start + length) is not glued to a longer word. */
fun isWordBoundary(folded: String, start: Int, length: Int): Boolean {
    val before = start <= 0 || !isWordCharacter(folded[start - 1])
    val end = start + length
    val after = end >= folded.length || !isWordCharacter(folded[end])
    return before && after
}

/** 0.0 (nothing alike) to 1.0 (identical) similarity, used to match spoken app and contact names. */
fun similarity(a: String, b: String): Double {
    val left = normalizeText(a)
    val right = normalizeText(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    if (left.startsWith(right) || right.startsWith(left)) return 0.9
    if (left.contains(right) || right.contains(left)) return 0.8

    val distance = levenshtein(left, right)
    val longest = maxOf(left.length, right.length)
    return (1.0 - distance.toDouble() / longest).coerceIn(0.0, 0.99)
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/**
 * The assistant's name as people actually say it around the world. A wake word that only
 * worked in English would make the app useless for most speakers.
 */
private val WAKE_NAMES = listOf(
    "robo", "robot", "robô", "rôbô", "robo",
    "रोबो", "रोबॉट", "रोबोट",
    "রোবো", "ਰੋਬੋ", "રોબો", "ரோபோ", "రోబో", "ರೋಬೋ", "റോബോ", "रोबो",
    "روبوت", "روبو", "رُوبو", "روبوت",
    "Ρομπό", "Ρομπότ", "Робо", "Робот", "Робот",
    "로보", "로봇", "ロボ", "ロボット", "机器人", "機器人", "โรโบ", "රෝබෝ",
    "robô", "róbô", "רובו", "רובוט", "روبوٹ"
)

private val WAKE_GREETINGS = listOf(
    "hey", "hi", "hello", "ok", "okay", "yo", "oi", "eh", "salut", "hola", "hallo",
    "привет", "привіт", "ciao", "olá", "مرحبا", "أهلا", "أهلين", "नमस्ते", "हे", "अरे",
    "হ্যালো", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ", "નમસ્તે", "வணக்கம்", "నమస్కారం", "ನಮಸ್ಕಾರ", "നമസ്കാരം",
    "こんにちは", "안녕", "你好", "สวัสดี", "merhaba", "γεια", "cześć", "ahoj", "saluton"
)

private val WAKE_REGEX = Regex(
    "^\\s*(?:(?:" + WAKE_GREETINGS.joinToString("|") { Regex.escape(it) } + ")\\s+)?" +
        "(?:" + WAKE_NAMES.joinToString("|") { Regex.escape(it) } + ")" +
        "(?=[\\s,!.?:;]|$)[\\s,!.?:;]*",
    RegexOption.IGNORE_CASE
)

/** Removes "Robo", "hey Robo", "रोबो", "ロボ" and similar openings from a sentence. */
fun stripWakeWord(value: String): String = value.replace(WAKE_REGEX, "").trim()

/** Whether the sentence was addressed to the assistant at all. */
fun containsWakeWord(value: String): Boolean {
    val folded = foldForSearch(value)
    return WAKE_NAMES.any { name ->
        val needle = foldForSearch(name).trim()
        if (needle.isEmpty()) return@any false
        var index = folded.indexOf(needle)
        while (index >= 0) {
            if (isWordBoundary(folded, index, needle.length)) return true
            index = folded.indexOf(needle, index + 1)
        }
        false
    }
}

/** Accent folding that never changes string length, so match offsets stay usable. */
private val LATIN_FOLD: Map<Char, Char> = buildMap {
    fun putAll(target: Char, vararg sources: Char) = sources.forEach { put(it, target) }
    putAll('a', 'á', 'à', 'â', 'ä', 'ã', 'å', 'ā', 'ă', 'ą', 'æ')
    putAll('c', 'ç', 'ć', 'č', 'ċ')
    putAll('d', 'ď', 'đ')
    putAll('e', 'é', 'è', 'ê', 'ë', 'ē', 'ė', 'ę', 'ě')
    putAll('g', 'ğ', 'ģ')
    putAll('i', 'í', 'ì', 'î', 'ï', 'ī', 'į', 'ı')
    putAll('l', 'ł', 'ľ', 'ĺ')
    putAll('n', 'ñ', 'ń', 'ň')
    putAll('o', 'ó', 'ò', 'ô', 'ö', 'õ', 'ø', 'ō', 'ő', 'œ')
    putAll('r', 'ř', 'ŕ')
    putAll('s', 'ś', 'š', 'ş', 'ș', 'ß')
    putAll('t', 'ť', 'ț', 'ţ')
    putAll('u', 'ú', 'ù', 'û', 'ü', 'ū', 'ů', 'ű')
    putAll('y', 'ý', 'ÿ')
    putAll('z', 'ź', 'ż', 'ž')
}

/** Locale tag to use for speech recognition and text-to-speech, or "auto". */
fun localeTagOf(languageTag: String): String {
    if (languageTag.isBlank() || languageTag.equals("auto", ignoreCase = true)) {
        return Locale.getDefault().toLanguageTag()
    }
    return languageTag
}
