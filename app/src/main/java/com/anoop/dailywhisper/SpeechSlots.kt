package com.anoop.dailywhisper

/**
 * Live dictation is "bring your own key" with 4 ordered speech slots. Slot 1 is Deepgram, slot 2 is Sarvam AI (the
 * fallback, and the only one with Malayalam and most Indian languages), slots 3 and 4 are reserved and empty for now.
 * The mic uses the first slot that is turned on, has a key, knows the dictation language and is allowed to receive the
 * audio (Residency); if it can't connect, the next one is tried. Pure Kotlin so tools/ws-harness tests it.
 */
enum class SpeechProvider(val label: String) { NONE("Not set"), DEEPGRAM("Deepgram"), SARVAM("Sarvam AI") }

object SpeechSlots {
    const val COUNT = 4
    /** Which provider each slot holds. Fixed for now (Anoop: Deepgram first, Sarvam AI next, two empty). */
    val PROVIDERS: List<SpeechProvider> = listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.NONE, SpeechProvider.NONE)

    /** "Pause before a sentence ends": silence before the provider closes a sentence. Deepgram's own default is ~10 ms. */
    const val DEFAULT_PAUSE_MS = 1300
    const val MIN_PAUSE_MS = 300
    const val MAX_PAUSE_MS = 5000

    /**
     * "Auto (Sarvam AI)": Sarvam's own adaptive language detection (language_code=auto). Only Sarvam AI takes it; Deepgram
     * never does. Distinct from "multi", which is Deepgram's nova-3 multilingual mode (Sarvam stays its "auto" fallback).
     */
    const val AUTO_SARVAM = "auto-sarvam"

    /** What the app knows about one slot when the mic is tapped. */
    data class Slot(val number: Int, val provider: SpeechProvider, val enabled: Boolean, val hasKey: Boolean, val residencyOk: Boolean)

    fun supports(provider: SpeechProvider, language: String): Boolean = when (provider) {
        SpeechProvider.DEEPGRAM -> LiveDictationConfig.LANGUAGES.any { it.first == language }
        SpeechProvider.SARVAM -> SarvamConfig.supports(language)
        SpeechProvider.NONE -> false
    }

    /** Slots to try, in order, for [language]. */
    fun candidates(slots: List<Slot>, language: String): List<Slot> = slots.sortedBy { it.number }.filter {
        it.provider != SpeechProvider.NONE && it.enabled && it.hasKey && it.residencyOk && supports(it.provider, language)
    }

    /** Why nothing can dictate [language] (shown when [candidates] is empty). */
    fun whyNone(slots: List<Slot>, language: String): String {
        val goal = if (language == AUTO_SARVAM) "use Auto (Sarvam AI)" else "dictate ${languageName(language)}"
        val real = slots.sortedBy { it.number }.filter { it.provider != SpeechProvider.NONE }
        val knows = real.filter { supports(it.provider, language) }
        if (knows.isEmpty()) return "None of the speech slots can $goal. Pick another language in Settings → Live dictation"
        knows.firstOrNull { it.enabled && it.hasKey && !it.residencyOk }?.let {
            return "Live dictation is off until you allow ${it.provider.label} (speech slot ${it.number}) in Settings → Where your voice goes"
        }
        knows.firstOrNull { it.enabled && !it.hasKey }?.let {
            return "Add your ${it.provider.label} API key in Settings → Speech slot ${it.number} to $goal"
        }
        return "Turn on speech slot ${knows.first().number} (${knows.first().provider.label}) in Settings to $goal"
    }

    /**
     * The dictation language list: everything Deepgram (nova-3) knows plus the Indian languages only Sarvam AI has,
     * marked "(Sarvam AI)". Stored by code. Malayalam is "ml". Order: Deepgram's Multilingual, Auto (Sarvam AI), the
     * English variants, then the rest by name. Codes never change, so saved choices keep working; only labels are new.
     */
    val LANGUAGES: List<Pair<String, String>> = run {
        val dg = LiveDictationConfig.LANGUAGES
        val dgBases = dg.map { it.first }.toSet()
        val sarvamOnly = listOf(
            "ml" to "Malayalam", "or" to "Odia", "kok" to "Konkani", "ks" to "Kashmiri", "sd" to "Sindhi", "sa" to "Sanskrit",
            "sat" to "Santali", "mni" to "Manipuri", "brx" to "Bodo", "mai" to "Maithili", "doi" to "Dogri",
        ).filter { it.first !in dgBases }.map { it.first to "${it.second} (Sarvam AI)" }
        val head = listOf(dg[0], AUTO_SARVAM to "Auto (Sarvam AI)") + dg.subList(1, 8) // multi, auto, English variants on top
        head + (dg.drop(8) + sarvamOnly).sortedBy { it.second }
    }

    /** One-line hints shown under a choice in Settings, so the two automatic modes aren't confused. */
    val LANGUAGE_HINTS: Map<String, String> = mapOf(
        "multi" to "Deepgram follows a mix of major languages as you speak",
        AUTO_SARVAM to "Detects Malayalam, English and other Indian languages as you speak",
    )

    fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second?.removeSuffix(" (Sarvam AI)") ?: code
}
