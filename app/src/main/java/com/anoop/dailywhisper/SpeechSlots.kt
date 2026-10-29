package com.anoop.dailywhisper

/**
 * Live dictation is "bring your own key" with 4 ordered speech slots. Slot 1 is Deepgram, slot 2 is Sarvam AI (the
 * fallback, and the only one with Malayalam and most Indian languages), slot 3 is "On this phone" (Sherpa-ONNX, no key,
 * audio never leaves the phone, needs a downloaded speech pack), slot 4 is empty for now.
 * The mic uses the first slot that is turned on, has a key, knows the dictation language and is allowed to receive the
 * audio (Residency); if it can't connect, the next one is tried. With no internet only slot 3 is used. Pure Kotlin so
 * tools/ws-harness tests it.
 */
enum class SpeechProvider(val label: String, val online: Boolean = true) {
    NONE("Not set"), DEEPGRAM("Deepgram"), SARVAM("Sarvam AI"), ON_DEVICE("On this phone", online = false)
}

object SpeechSlots {
    const val COUNT = 4
    /** Which provider each slot holds. Fixed (Anoop: Deepgram first, Sarvam AI next, on this phone third, one empty). */
    val PROVIDERS: List<SpeechProvider> = listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.ON_DEVICE, SpeechProvider.NONE)

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

    /**
     * What the app knows about one slot when the mic is tapped. For "On this phone", [hasKey] is always true (no key)
     * and [installed] lists the speech packs on the phone (SpeechPackCatalog ids); it can dictate a language only
     * when one of them covers it.
     */
    data class Slot(val number: Int, val provider: SpeechProvider, val enabled: Boolean, val hasKey: Boolean, val residencyOk: Boolean,
                    val installed: Set<String> = emptySet())

    /** Whether [provider] knows [language] at all (for "On this phone": some pack, downloaded or not, covers it). */
    fun supports(provider: SpeechProvider, language: String): Boolean = when (provider) {
        SpeechProvider.DEEPGRAM -> LiveDictationConfig.LANGUAGES.any { it.first == language }
        SpeechProvider.SARVAM -> SarvamConfig.supports(language)
        SpeechProvider.ON_DEVICE -> SpeechPackCatalog.packsFor(language).isNotEmpty()
        SpeechProvider.NONE -> false
    }

    /** Whether [slot] can dictate [language] right now (for "On this phone": a pack for it is downloaded). */
    fun ready(slot: Slot, language: String): Boolean = when (slot.provider) {
        SpeechProvider.ON_DEVICE -> SpeechPackCatalog.choose(language, slot.installed) != null
        else -> supports(slot.provider, language)
    }

    /** Slots to try, in order, for [language]. */
    fun candidates(slots: List<Slot>, language: String): List<Slot> = slots.sortedBy { it.number }.filter {
        it.provider != SpeechProvider.NONE && it.enabled && it.hasKey && it.residencyOk && ready(it, language)
    }

    /** What Auto (Sarvam AI) falls back to when Sarvam AI can't be used: Deepgram's own multilingual detection. */
    const val AUTO_FALLBACK = "multi"

    /**
     * What the mic will do: the [slots] to try in order, the [language] the online ones are given, whether Auto fell
     * back to Deepgram, and whether it is "On this phone" only because there is no internet ([offline]).
     */
    data class Plan(val slots: List<Slot>, val language: String, val fellBack: Boolean, val offline: Boolean = false) {
        /** The on-device slot, when it is in the plan (always last: it is the fallback when the internet goes). */
        val onDevice: Slot? get() = slots.firstOrNull { it.provider == SpeechProvider.ON_DEVICE }
        val online: List<Slot> get() = slots.filter { it.provider.online }
    }

    /**
     * [candidates] plus two rules:
     * - Auto (Sarvam AI) with Sarvam AI off, keyless or not allowed goes to Deepgram (slot 1) with "multi" instead of
     *   blocking the mic (Anoop, 29 Oct 2026).
     * - No internet ([online] false): only "On this phone" (slot 3), when it has a pack for the language.
     * The order online stays Deepgram, Sarvam AI, then on this phone (Anoop, 29 Oct 2026).
     */
    fun plan(slots: List<Slot>, language: String, online: Boolean = true): Plan {
        val use = candidates(slots, language)
        val device = use.filter { !it.provider.online }
        if (!online) return Plan(device, language, false, offline = true)
        if (language != AUTO_SARVAM || use.any { it.provider == SpeechProvider.SARVAM }) return Plan(use, language, false)
        val dg = candidates(slots, AUTO_FALLBACK).filter { it.provider == SpeechProvider.DEEPGRAM }
        return if (dg.isEmpty()) Plan(device, language, false) else Plan(dg + device, AUTO_FALLBACK, true)
    }

    /** The short note shown once per app run when Auto (Sarvam AI) falls back to Deepgram. */
    const val AUTO_FALLBACK_NOTE = "Sarvam AI is off, using Deepgram"

    /** The short notes shown when the mic uses "On this phone" because the internet is missing or went away. */
    const val OFFLINE_NOTE = "No internet, using offline mode"
    const val DROPPED_NOTE = "Connection lost, using offline mode"
    const val NO_CONNECT_NOTE = "Couldn't connect, using offline mode"

    /** Why nothing can dictate [language] (shown when [plan] has no slots). [online] false: there is no internet. */
    fun whyNone(slots: List<Slot>, language: String, online: Boolean = true): String {
        if (!online) {
            val dev = slots.firstOrNull { it.provider == SpeechProvider.ON_DEVICE }
            val pack = SpeechPackCatalog.packsFor(language).firstOrNull()
            return when {
                dev == null || pack == null -> "No internet. Live dictation needs a connection for ${languageName(language)}"
                !dev.enabled -> "No internet. Turn on ${SpeechProvider.ON_DEVICE.label} in Settings to dictate offline"
                else -> "No internet. Download ${pack.name} in Settings → ${SpeechProvider.ON_DEVICE.label} to dictate offline"
            }
        }
        val why = whyNoneFor(slots, language)
        return if (language == AUTO_SARVAM) "$why, or set up Deepgram in Settings → Deepgram" else why
    }

    private fun whyNoneFor(slots: List<Slot>, language: String): String {
        val goal = if (language == AUTO_SARVAM) "use Auto (Sarvam AI)" else "dictate ${languageName(language)}"
        val online = slots.sortedBy { it.number }.filter { it.provider != SpeechProvider.NONE && it.provider.online }
        // Only when no online provider knows the language, point at the offline pack instead.
        val pack = SpeechPackCatalog.packsFor(language).firstOrNull()
        if (online.none { supports(it.provider, language) } && pack != null && slots.any { it.provider == SpeechProvider.ON_DEVICE })
            return "Download ${pack.name} in Settings → ${SpeechProvider.ON_DEVICE.label} to $goal"
        val real = online
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
