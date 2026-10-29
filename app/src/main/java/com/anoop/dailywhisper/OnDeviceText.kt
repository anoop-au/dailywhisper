package com.anoop.dailywhisper

/**
 * Tidies what the on-device speech packs write. The English pack writes LIKE THIS WITH NO PUNCTUATION (LibriSpeech
 * style); the sentence packs write plain lower-case text. Finished sentences get a capital and a full stop, the way
 * Deepgram's smart formatting would. Pure Kotlin so the JVM tests check it.
 */
object OnDeviceText {
    private val END = setOf('.', '?', '!', '…', '।', '|')

    fun tidy(raw: String, final: Boolean, english: Boolean = false): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val shouting = t.any { it.isLetter() } && t.none { it.isLowerCase() } && t.any { it in 'A'..'Z' }
        if (shouting) t = t.lowercase()
        // English "i" on its own, and its contractions (i'm, i'll, i'd, i've).
        if (english) t = t.replace(Regex("(?<![\\p{L}'])i(?=$|[\\s'’,.?!])"), "I")
        if (final) {
            t = t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            if (t.last() !in END) t += "."
        }
        return t
    }
}
