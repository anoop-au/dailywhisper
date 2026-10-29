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
     * "Auto (Sarvam AI)": Sarvam's own adaptive language detection (language_code=auto). Only Sarvam AI takes it as is;
     * when Sarvam AI can't be used, [plan] hands it to Deepgram as "multi". Distinct from "multi", which is Deepgram's
     * nova-3 multilingual mode (Sarvam stays its "auto" fallback).
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

    /** What Auto (Sarvam AI) falls back to when Sarvam AI can't be used: Deepgram's own multilingual detection. */
    const val AUTO_FALLBACK = "multi"

    /** What the mic will do: the [slots] to try in order, the [language] they are given, and whether Auto fell back. */
    data class Plan(val slots: List<Slot>, val language: String, val fellBack: Boolean)

    /**
     * [candidates] plus one rule: Auto (Sarvam AI) with Sarvam AI off, keyless or not allowed goes to Deepgram (slot 1)
     * with "multi" instead of blocking the mic (Anoop, 29 Oct 2026). Every other language is unchanged.
     */
    fun plan(slots: List<Slot>, language: String): Plan {
        val use = candidates(slots, language)
        if (use.isNotEmpty() || language != AUTO_SARVAM) return Plan(use, language, false)
        val dg = candidates(slots, AUTO_FALLBACK).filter { it.provider == SpeechProvider.DEEPGRAM }
        return if (dg.isEmpty()) Plan(dg, language, false) else Plan(dg, AUTO_FALLBACK, true)
    }

    /** The short note shown once per app run when Auto (Sarvam AI) falls back to Deepgram. */
    const val AUTO_FALLBACK_NOTE = "Sarvam AI is off, using Deepgram"

    /** Why nothing can dictate [language] (shown when [plan] has no slots). */
    fun whyNone(slots: List<Slot>, language: String): String {
        val why = whyNoneFor(slots, language)
        return if (language == AUTO_SARVAM) "$why, or set up Deepgram in Settings → Deepgram" else why
    }

    private fun whyNoneFor(slots: List<Slot>, language: String): String {
        val goal = if (language == AUTO_SARVAM) "use Auto (Sarvam AI)" else "dictate ${languageName(language)}"
        val real = slots.sortedBy { it.number }.filter { it.provider != SpeechProvider.NONE }
        val knows = real.filter { supports(it.provider, language) }
        if (knows.isEmpty()) return "None of the speech providers can $goal. Pick another language in Settings → Dictation"
        knows.firstOrNull { it.enabled && it.hasKey && !it.residencyOk }?.let {
            return "Live dictation is off until you allow ${it.provider.label} in Settings → ${it.provider.label}"
        }
        knows.firstOrNull { it.enabled && !it.hasKey }?.let {
            return "Add your ${it.provider.label} API key in Settings → ${it.provider.label} to $goal"
        }
        return "Turn on ${knows.first().provider.label} in Settings → ${knows.first().provider.label} to $goal"
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
