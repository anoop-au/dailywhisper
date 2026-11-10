package com.anoop.dailywhisper

import java.util.Locale

/**
 * Which language is which, now there is one "Translate into" (v0.1.21). Pure Kotlin (tests).
 *
 * "I mostly write in" is no longer shown; its saved value is only a fallback. The language a note is in ("from") is,
 * in order: the note's own script (Malayalam, Chinese; Latin falls through, it can't tell English from Spanish), the
 * dictation language when it is a real language (not Mixed or Auto), the saved "I mostly write in", then English.
 */
object TranslationLanguages {
    const val EN = "en"

    /** [dictation] ("I dictate in") as a translation language: null for Mixed / Auto or one no translator knows. */
    fun realDictation(dictation: String?): String? {
        val d = dictation?.trim()?.lowercase().orEmpty()
        if (d.isEmpty() || d == "multi" || d.startsWith("auto")) return null
        return d.substringBefore('-').takeIf { TranslationRegistry.knows(it) }
    }

    /** The user's own language when there's no text to look at (model downloads, speaking the "into" language back). */
    fun home(dictation: String?, savedSource: String?): String =
        realDictation(dictation) ?: savedSource?.takeIf { it.isNotBlank() && TranslationRegistry.knows(it) } ?: EN

    /** The language [text] is in (see the class comment). */
    fun from(text: String, dictation: String?, savedSource: String?): String {
        when (LiveTranslation.Script.detect(text)) {
            LiveTranslation.ML -> return LiveTranslation.ML
            LiveTranslation.ZH -> return LiveTranslation.ZH
        }
        return home(dictation, savedSource)
    }

    /**
     * The language Insights are shown in: the translation language when Translation is [on] and it differs from the
     * note's, else the note's own [noteLanguage].
     */
    fun insightsLanguage(on: Boolean, into: String, noteLanguage: String): String =
        if (on && into.isNotBlank() && into != LiveTranslation.OFF && into != noteLanguage) into else noteLanguage

    /** A short name in the language itself, for the note-screen switch: 中文, മലയാളം, Español. */
    fun nativeName(code: String): String {
        val l = Locale.forLanguageTag(code)
        val n = l.getDisplayLanguage(l)
        return if (n.isBlank() || n.equals(code, true)) TranslationRegistry.languageName(code)
        else n.replaceFirstChar { it.titlecase(l) }
    }
}
