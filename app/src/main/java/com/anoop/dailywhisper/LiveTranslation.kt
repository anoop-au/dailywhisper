package com.anoop.dailywhisper

/**
 * Live translation while dictating: English ↔ Malayalam only (the Opus-MT pairs on the phone). Pure Kotlin (no Android
 * imports) so the unit tests run exactly this code.
 *
 * - [Script.detect] decides the spoken language from the words themselves (Malayalam script vs Latin letters).
 * - [direction] picks source → target, or null when there's nothing sensible to do (another language, same language).
 * - [sane] is the OpusGuard-style check on what came back: wrong script, echo, runaway length or a loop is dropped
 *   rather than shown, so a bad translation shows nothing instead of junk.
 * - [Segments] keeps finals in the order they were spoken, each with its translation when it arrives (translations
 *   can only fill their own slot, never reorder), plus at most one live partial that a final replaces.
 */
object LiveTranslation {
    const val EN = "en"
    const val ML = "ml"
    /** Target setting: the other one of English and Malayalam. */
    const val AUTO = "auto"

    object Script {
        private fun isMalayalam(c: Char) = c in '\u0D00'..'\u0D7F'
        private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F'

        /** "ml" or "en" when at least [share] of the letters are that script, else null (another script, or mixed). */
        fun detect(text: String, share: Double = 0.7): String? {
            var ml = 0; var lat = 0; var other = 0
            for (c in text) when {
                isMalayalam(c) -> ml++
                isLatin(c) -> lat++
                c.isLetter() -> other++
            }
            val total = ml + lat + other
            if (total == 0) return null
            return when {
                ml >= share * total -> ML
                lat >= share * total -> EN
                else -> null
            }
        }
    }

    /**
     * Dictation [language] codes that may be English or Malayalam speech. Others (Hindi, Tamil…) get no translation even
     * if the letters look Latin.
     */
    fun spokenMayBeEnOrMl(language: String): Boolean {
        val l = language.lowercase()
        return l == ML || l == EN || l.startsWith("en-") || l == "multi" || l == SpeechSlots.AUTO_SARVAM || l == "auto"
    }

    /** Source → target for [text], or null if it's not English/Malayalam or the target is the same language. */
    fun direction(text: String, dictationLanguage: String, target: String): Pair<String, String>? {
        if (!spokenMayBeEnOrMl(dictationLanguage)) return null
        val src = Script.detect(text) ?: return null
        val tgt = when (target) { EN, ML -> target; else -> if (src == EN) ML else EN }
        return if (tgt == src) null else src to tgt
    }

    /** The translation looks like a real one: right script, not an echo, a plausible length, no repeated loop. */
    fun sane(source: String, translation: String?, target: String): Boolean {
        val t = translation?.trim().orEmpty()
        val s = source.trim()
        if (t.isEmpty() || s.isEmpty()) return false
        if (t.equals(s, ignoreCase = true)) return false
        if (Script.detect(t, share = 0.6) != target) return false
        val ratio = t.length.toDouble() / s.length
        if (ratio > 4.0 || (s.length >= 12 && ratio < 0.2)) return false
        return !loops(t)
    }

    /** The same word 4+ times in a row, or a 2–6 word run three times in a row: a decoder loop. */
    fun loops(text: String): Boolean {
        val w = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var run = 1
        for (i in 1 until w.size) { run = if (w[i] == w[i - 1]) run + 1 else 1; if (run >= 4) return true }
        for (k in 2..6) for (i in 0..w.size - 3 * k) {
            if ((0 until k).all { j -> w[i + j] == w[i + k + j] && w[i + j] == w[i + 2 * k + j] }) return true
        }
        return false
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
            return parts.joinToString(" ")
        }

        /** Only finished segments (what gets saved with the note). */
        fun finalTranslation(): String {
            val parts = ArrayList<String>()
            if (prefixT.isNotEmpty()) parts += prefixT
            finals.sortedBy { it.seq }.forEach { s -> s.translation?.let { parts += it } }
            return parts.joinToString(" ")
        }

        fun hasAny(): Boolean = translation().isNotEmpty()
        fun pending(): Int = finals.count { it.translation == null }
        fun originals(): String = (listOfNotNull(prefixO.takeIf { it.isNotEmpty() }) + finals.map { it.original }).joinToString(" ")
    }
}

