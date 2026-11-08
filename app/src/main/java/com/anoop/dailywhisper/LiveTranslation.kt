package com.anoop.dailywhisper

/**
 * Live translation while dictating, into the language picked in Settings ("Live translation into", or Off). Pure
 * Kotlin (no Android imports) so the unit tests run exactly this code. Which engine translates is decided per pair by
 * TranslationRegistry (OpenRouter, Opus-MT, ML Kit).
 *
 * - [source] decides the spoken language: from the words themselves (Malayalam script, Han characters, Latin) when the
 *   dictation language is English, Malayalam, Chinese or an automatic one; else the dictation language itself.
 * - [target] / [direction] pick source → target, or null when there's nothing sensible to do (Off, same language).
 * - [sane] is the OpusGuard-style check on what came back: wrong script, echo, runaway length or a loop is dropped
 *   rather than shown, so a bad translation shows nothing instead of junk.
 * - [Segments] keeps finals in the order they were spoken, each with its translation when it arrives (translations
 *   can only fill their own slot, never reorder), plus at most one live partial that a final replaces.
 */
object LiveTranslation {
    const val EN = "en"
    const val ML = "ml"
    const val ZH = "zh"
    /** The languages live translation knows. */
    val LANGS = setOf(EN, ML, ZH)

    /** "Live translation into": Off. */
    const val OFF = "off"
    /** Old (v0.1.19) "Translate dictation into" values, only read by SettingsMigration: same as notes / the other one. */
    const val NOTES = "notes"
    const val AUTO = "auto"

    object Script {
        private fun isMalayalam(c: Char) = c in '\u0D00'..'\u0D7F'
        private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F'
        fun isHan(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

        /** "ml", "zh" or "en" when at least [share] of the letters are that script, else null (another script, or mixed). */
        fun detect(text: String, share: Double = 0.7): String? {
            var ml = 0; var han = 0; var lat = 0; var other = 0
            for (c in text) when {
                isMalayalam(c) -> ml++
                isHan(c) -> han += 3 // one character is a word or so; weigh it like a few Latin letters
                isLatin(c) -> lat++
                c.isLetter() -> other++
            }
            val total = ml + han + lat + other
            if (total == 0) return null
            return when {
                ml >= share * total -> ML
                han >= share * total -> ZH
                lat >= share * total -> EN
                else -> null
            }
        }
    }

    /**
     * Dictation [language] codes that may be English, Malayalam or Chinese speech. Others (Hindi, Spanish…) get no
     * translation even if the letters look Latin.
     */
    fun spokenMayBeLive(language: String): Boolean {
        val l = language.lowercase()
        return l == ML || l == EN || l.startsWith("en-") || l == ZH || l.startsWith("zh-") ||
            l == "multi" || l.startsWith("auto") // Deepgram's Multilingual, Auto (Sarvam AI)
    }

    /**
     * The spoken language of [text]: what the words look like when [dictationLanguage] is English, Malayalam, Chinese
     * or automatic (people mix them); otherwise the dictation language's base code, if [known] (a language the app can
     * translate). Null: can't tell.
     */
    fun source(text: String, dictationLanguage: String, known: (String) -> Boolean = { it in LANGS }): String? {
        val l = dictationLanguage.trim()
        if (spokenMayBeLive(l)) return Script.detect(text)
        return l.substringBefore('-').lowercase().takeIf { it.isNotEmpty() && known(it) }
    }

    /**
     * The language to translate [src] into, for "Live translation into" [into]. Speaking [into] itself goes back to
     * [notesSource] ("I mostly write in"), or English. Null: Off, or nothing to do.
     */
    fun target(src: String, into: String, notesSource: String = EN): String? {
        if (into == OFF || into.isBlank()) return null
        val t = when {
            into != src -> into
            notesSource != src -> notesSource
            src != EN -> EN
            else -> return null
        }
        return t.takeIf { it != src }
    }

    /** Source → target for [text], or null (Off, can't tell the language, or the same language). */
    fun direction(text: String, dictationLanguage: String, into: String, notesSource: String = EN,
                  known: (String) -> Boolean = { it in LANGS }): Pair<String, String>? {
        if (into == OFF) return null
        val src = source(text, dictationLanguage, known) ?: return null
        val tgt = target(src, into, notesSource) ?: return null
        return src to tgt
    }

    /** The pairs someone with these settings will most likely need (what to download, and whether it's usable now). */
    fun expectedPairs(into: String, notesSource: String): List<Pair<String, String>> {
        if (into == OFF) return emptyList()
        val out = LinkedHashSet<Pair<String, String>>()
        for (s in linkedSetOf(notesSource, EN)) {
            val t = target(s, into, notesSource) ?: continue
            out += s to t
            target(t, into, notesSource)?.let { out += t to it }
        }
        return out.toList()
    }

    /** Languages written in Latin letters (what [Script.detect] calls "en"), for the guard's script check. */
    private val LATIN = setOf("af", "ca", "cs", "cy", "da", "de", "en", "eo", "es", "et", "fi", "fr", "ga", "gl", "hr", "ht",
        "hu", "id", "is", "it", "lt", "lv", "ms", "mt", "nl", "no", "pl", "pt", "ro", "sk", "sl", "sq", "sv", "sw", "tl", "tr", "vi")

    /** The script a translation into [target] must be in ([Script.detect]'s codes), or null when the guard can't tell. */
    fun expectedScript(target: String): String? = when (target) {
        ML -> ML; ZH -> ZH; in LATIN -> EN; else -> null
    }

    /** The translation looks like a real one: right script, not an echo, a plausible length, no repeated loop. */
    fun sane(source: String, translation: String?, target: String): Boolean {
        val t = translation?.trim().orEmpty()
        val s = source.trim()
        if (t.isEmpty() || s.isEmpty()) return false
        if (t.equals(s, ignoreCase = true)) return false
        val got = Script.detect(t, share = 0.6)
        val want = expectedScript(target)
        // A known script must match; for others (Hindi, Japanese…) it at least mustn't be Latin or Malayalam.
        if (if (want != null) got != want else got == EN || got == ML) return false
        // Chinese and Japanese pack a word into a character: much shorter going in, much longer coming out.
        val srcZh = Script.detect(s) == ZH
        val (lo, hi) = when { target == ZH || target == "ja" -> 0.08 to 4.0; srcZh -> 0.2 to 12.0; else -> 0.2 to 4.0 }
        val ratio = t.length.toDouble() / s.length
        if (ratio > hi || (s.length >= 12 && ratio < lo)) return false
        return !loops(t)
    }

    /** The same word 4+ times in a row, a 2–6 word run three times in a row, or (Chinese) a 1–8 character run 4/3 times. */
    fun loops(text: String): Boolean {
        val w = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var run = 1
        for (i in 1 until w.size) { run = if (w[i] == w[i - 1]) run + 1 else 1; if (run >= 4) return true }
        for (k in 2..6) for (i in 0..w.size - 3 * k) {
            if ((0 until k).all { j -> w[i + j] == w[i + k + j] && w[i + j] == w[i + 2 * k + j] }) return true
        }
        val c = text.filter { Script.isHan(it) }
        for (k in 1..8) {
            val times = if (k == 1) 4 else 3
            for (i in 0..c.length - times * k) {
                if ((1 until times).all { r -> c.regionMatches(i, c, i + r * k, k) }) return true
            }
        }
        return false
    }

    /** Joins translated pieces: a space between words, none between Chinese characters. */
    fun join(parts: List<String>): String {
        fun cjk(c: Char) = Script.isHan(c) || c in "。！？，、；：「」"
        val sb = StringBuilder()
        for (p in parts) {
            if (p.isEmpty()) continue
            if (sb.isNotEmpty() && !(cjk(sb.last()) && cjk(p.first()))) sb.append(' ')
            sb.append(p)
        }
        return sb.toString()
    }

    /**
     * Ordered live segments. [addFinal] returns a sequence number; [setTranslation] fills that slot only (late or stale
     * results can't move text around). A partial carries a generation: only the newest partial's translation shows, and
     * any final drops the partial and its translation.
     */
    class Segments(prefixOriginal: String = "", prefixTranslation: String = "") {
        private class Seg(val seq: Int, val original: String) { var translation: String? = null }
        private val prefixT = prefixTranslation.trim()
        private val prefixO = prefixOriginal.trim()
        private val finals = ArrayList<Seg>()
        private var nextSeq = 0
        var partial: String = ""; private set
        private var partialGen = 0
        private var partialTranslation: String? = null

        fun addFinal(text: String): Int {
            val seq = nextSeq++
            val t = text.trim()
            if (t.isNotEmpty()) finals += Seg(seq, t)
            clearPartial()
            return seq
        }

        /** True if [seq] is still waiting for its translation (it was added and not filled). */
        fun setTranslation(seq: Int, translation: String?): Boolean {
            val s = finals.firstOrNull { it.seq == seq } ?: return false
            s.translation = translation?.trim()?.takeIf { it.isNotEmpty() }
            return true
        }

        /** A new partial; returns its generation (pass it back with the translation). */
        fun setPartial(text: String): Int {
            if (text.trim() != partial) { partial = text.trim(); partialTranslation = null; partialGen++ }
            return partialGen
        }

        /** Kept only if [gen] is still the current partial. */
        fun setPartialTranslation(gen: Int, translation: String?): Boolean {
            if (gen != partialGen || partial.isEmpty()) return false
            partialTranslation = translation?.trim()?.takeIf { it.isNotEmpty() }
            return true
        }

        fun clearPartial() { partial = ""; partialTranslation = null; partialGen++ }

        /** The translation of everything so far, in spoken order; segments still being translated are left out. */
        fun translation(): String {
            val parts = ArrayList<String>()
            if (prefixT.isNotEmpty()) parts += prefixT
            finals.sortedBy { it.seq }.forEach { s -> s.translation?.let { parts += it } }
            partialTranslation?.let { parts += it }
            return join(parts)
        }

        /** Only finished segments (what gets saved with the note). */
        fun finalTranslation(): String {
            val parts = ArrayList<String>()
            if (prefixT.isNotEmpty()) parts += prefixT
            finals.sortedBy { it.seq }.forEach { s -> s.translation?.let { parts += it } }
            return join(parts)
        }

        fun hasAny(): Boolean = translation().isNotEmpty()
        fun pending(): Int = finals.count { it.translation == null }
        fun originals(): String = (listOfNotNull(prefixO.takeIf { it.isNotEmpty() }) + finals.map { it.original }).joinToString(" ")
    }
}

