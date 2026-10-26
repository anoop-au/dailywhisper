package com.anoop.dailywhisper

/**
 * Offline translation (Google ML Kit, on the phone): the language list and text chunking.
 * Pure Kotlin (no Android imports) so tools/translate-harness tests exactly this code on the box.
 */
object TranslateConfig {
    const val DEFAULT_SOURCE = "en"
    const val DEFAULT_TARGET = "es"

    /** One language: [code] is ML Kit's code (TranslateLanguage in translate 17.0.3), [name] what the app shows. */
    data class Lang(val code: String, val name: String)

    /** ML Kit's 59 on-device languages, by English name. */
    val LANGUAGES: List<Lang> = listOf(
        Lang("af", "Afrikaans"),
        Lang("sq", "Albanian"),
        Lang("ar", "Arabic"),
        Lang("be", "Belarusian"),
        Lang("bn", "Bengali"),
        Lang("bg", "Bulgarian"),
        Lang("ca", "Catalan"),
        Lang("zh", "Chinese (Simplified)"),
        Lang("hr", "Croatian"),
        Lang("cs", "Czech"),
        Lang("da", "Danish"),
        Lang("nl", "Dutch"),
        Lang("en", "English"),
        Lang("eo", "Esperanto"),
        Lang("et", "Estonian"),
        Lang("tl", "Filipino (Tagalog)"),
        Lang("fi", "Finnish"),
        Lang("fr", "French"),
        Lang("gl", "Galician"),
        Lang("ka", "Georgian"),
        Lang("de", "German"),
        Lang("el", "Greek"),
        Lang("gu", "Gujarati"),
        Lang("ht", "Haitian Creole"),
        Lang("he", "Hebrew"),
        Lang("hi", "Hindi"),
        Lang("hu", "Hungarian"),
        Lang("is", "Icelandic"),
        Lang("id", "Indonesian"),
        Lang("ga", "Irish"),
        Lang("it", "Italian"),
        Lang("ja", "Japanese"),
        Lang("kn", "Kannada"),
        Lang("ko", "Korean"),
        Lang("lv", "Latvian"),
        Lang("lt", "Lithuanian"),
        Lang("mk", "Macedonian"),
        Lang("ms", "Malay"),
        Lang("mt", "Maltese"),
        Lang("mr", "Marathi"),
        Lang("no", "Norwegian"),
        Lang("fa", "Persian"),
        Lang("pl", "Polish"),
        Lang("pt", "Portuguese"),
        Lang("ro", "Romanian"),
        Lang("ru", "Russian"),
        Lang("sk", "Slovak"),
        Lang("sl", "Slovenian"),
        Lang("es", "Spanish"),
        Lang("sw", "Swahili"),
        Lang("sv", "Swedish"),
        Lang("ta", "Tamil"),
        Lang("te", "Telugu"),
        Lang("th", "Thai"),
        Lang("tr", "Turkish"),
        Lang("uk", "Ukrainian"),
        Lang("ur", "Urdu"),
        Lang("vi", "Vietnamese"),
        Lang("cy", "Welsh"),
    )

    fun lang(code: String): Lang? = LANGUAGES.firstOrNull { it.code == code }
    fun languageName(code: String): String = lang(code)?.name ?: code

    /** All languages as (code, name) for pickers. */
    fun options(): List<Pair<String, String>> = LANGUAGES.map { it.code to it.name }

    /** ML Kit is given a line (or a few sentences) at a time, so the note's layout comes back exactly. */
    const val CHUNK_CHARS = 2000

    // ---- chunking ----
    /** A piece of the note: [text] to translate (may be blank: kept as is) followed by [sep] (kept as is). */
    data class Piece(val text: String, val sep: String)

    /**
     * Splits [text] into pieces of at most [max] chars: one per line (paragraph structure is kept exactly), and a
     * long line is cut at sentence ends, else at a space. Joining every text + sep gives back the original exactly.
     */
    fun chunks(text: String, max: Int): List<Piece> {
        val out = ArrayList<Piece>()
        var last = 0
        fun line(t: String, sep: String) {
            if (t.length <= max) { out += Piece(t, sep); return }
            val parts = splitLong(t, max)
            parts.forEachIndexed { j, p -> out += if (j == parts.lastIndex) Piece(p.text, p.sep + sep) else p }
        }
        for (m in Regex("\\s*\n\\s*").findAll(text)) { line(text.substring(last, m.range.first), m.value); last = m.range.last + 1 }
        line(text.substring(last), "")
        return out.filter { it.text.isNotEmpty() || it.sep.isNotEmpty() }
    }

    private fun splitLong(s: String, max: Int): List<Piece> {
        val out = ArrayList<Piece>()
        var start = 0
        while (s.length - start > max) {
            val window = s.substring(start, start + max)
            var cut = Regex("[.!?。！？](\\s+)").findAll(window).lastOrNull()?.let { it.range.first + 1 }
            if (cut == null || cut < max / 3) cut = window.lastIndexOf(' ').takeIf { it > max / 3 }
            if (cut == null) cut = max
            val end = start + cut
            var sepEnd = end
            while (sepEnd < s.length && s[sepEnd] == ' ') sepEnd++
            out += Piece(s.substring(start, end), s.substring(end, sepEnd))
            start = sepEnd
        }
        out += Piece(s.substring(start), "")
        return out
    }

    /** Trims stray leading/trailing whitespace from a translated piece (the original separators are kept separately). */
    fun cleanReply(reply: String): String = reply.trim()
}
