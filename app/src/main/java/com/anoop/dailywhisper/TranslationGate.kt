package com.anoop.dailywhisper

import android.content.Context

/**
 * When a note shows any translation control (the 译 switch, the translation beside the text, tap-to-translate). Since
 * v0.1.27 they show only when translation is set up AND useful for this note:
 *  - a "Translate into" language is set (not Off),
 *  - the note's language differs from it (a Chinese note with Translate into 中文 shows nothing), and
 *  - something can do it: the phone's own translator knows both languages, or an online translator that is switched
 *    on, has its key, and supports the pair (connected or not, so the switch doesn't flicker on a train).
 * Translation switched off is not "not useful": the switch then shows "Off" so it can be turned back on.
 */
object TranslationGate {
    fun useful(target: String?, noteLang: String, onPhone: Boolean, onlineReady: Boolean): Boolean =
        !target.isNullOrBlank() && target != LiveTranslation.OFF && noteLang != target && (onPhone || onlineReady)
}

/** [TranslationGate.useful] for [text] with this phone's settings and translators. */
fun Context.translationUseful(text: String): Boolean {
    val target = translateInto()
    val source = noteLanguage(text)
    if (target.isBlank() || target == LiveTranslation.OFF || source == target) return false
    val onPhone = NoteTranslator(this).supports(source, target)
    val onlineReady = translationOrder().any { it.online && it.supports(source, target) && it.usable(this, source, target) }
    return TranslationGate.useful(target, source, onPhone, onlineReady)
}
