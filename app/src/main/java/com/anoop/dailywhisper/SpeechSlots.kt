package com.anoop.dailywhisper

/**
 * Routing for live dictation. Each provider in [SpeechRegistry] is one slot, numbered in the order the mic tries them:
 * the online providers in the user's order (Settings → Providers), then "On this phone" (Sherpa-ONNX, no key, audio
 * never leaves the phone) as the fallback only. The mic uses the first slot that is turned on, has a key, knows the
 * dictation language and is allowed to receive the audio (Residency); if it can't connect, the next one is tried, and
 * the phone takes over last. With no internet, or when no online provider can do the language, only the phone is used.
 * Pure Kotlin so tools/ws-harness tests it.
 */
object SpeechSlots {
    /** "Pause before a sentence ends": silence before the provider closes a sentence. Deepgram's own default is ~10 ms. */
    const val DEFAULT_PAUSE_MS = 1300
    const val MIN_PAUSE_MS = 300
    const val MAX_PAUSE_MS = 5000

    /**
     * "Auto (Indian languages)": Sarvam AI's adaptive language detection (language_code=auto). Only Sarvam AI takes it as
     * is; when it can't be used, [plan] hands it to the next provider that does "multi" (any language as you speak).
     */
    const val AUTO_SARVAM = "auto-sarvam"

    /** "Mixed languages": any language as you speak (Deepgram nova-3 "multi"; AssemblyAI without steering). */
    const val AUTO_FALLBACK = "multi"

    /**
     * What the app knows about one provider when the mic is tapped. For "On this phone", [hasKey] is always true (no
     * key) and [installed] lists the speech packs on the phone (SpeechPackCatalog ids); it can dictate a language only
     * when one of them covers it.
     */
    data class Slot(val number: Int, val provider: SpeechProvider, val enabled: Boolean, val hasKey: Boolean, val residencyOk: Boolean,
                    val installed: Set<String> = emptySet())

    /** Whether [provider] knows [language] at all (for "On this phone": some pack, downloaded or not, covers it). */
    fun supports(provider: SpeechProvider, language: String): Boolean = provider.supports(language)

    /** Whether [slot] can dictate [language] right now (for "On this phone": a pack for it is downloaded). */
    fun ready(slot: Slot, language: String): Boolean =
        if (slot.provider.online) slot.provider.supports(language) else SpeechPackCatalog.choose(language, slot.installed) != null

    /** Slots to try, in order, for [language]. */
    fun candidates(slots: List<Slot>, language: String): List<Slot> = slots.sortedBy { it.number }.filter {
        it.enabled && it.hasKey && it.residencyOk && ready(it, language)
    }

    /**
     * What the mic will do: the [slots] to try in order, the [language] the online ones are given, whether Auto fell
     * back to a "multi" provider, and whether it is "On this phone" only because there is no internet ([offline]).
     */
    data class Plan(val slots: List<Slot>, val language: String, val fellBack: Boolean, val offline: Boolean = false) {
        /** The on-device slot, when it is in the plan (always last: it is the fallback). */
        val onDevice: Slot? get() = slots.firstOrNull { !it.provider.online }
        val online: List<Slot> get() = slots.filter { it.provider.online }
    }

    /**
     * [candidates] in the user's order, with the phone always last, plus two rules:
     * - Auto (Indian languages) with Sarvam AI off, keyless or not allowed goes to the first provider that takes "multi"
     *   (Deepgram first by default) instead of blocking the mic (Anoop, 29 Oct 2026).
     * - No internet ([online] false): only "On this phone", when it has a pack for the language.
     */
    fun plan(slots: List<Slot>, language: String, online: Boolean = true): Plan {
        val use = candidates(slots, language)
        val device = use.filter { !it.provider.online }
        if (!online) return Plan(device, language, false, offline = true)
        val cloud = use.filter { it.provider.online }
        if (language != AUTO_SARVAM || cloud.any { it.provider == SpeechProvider.SARVAM }) return Plan(cloud + device, language, false)
        val multi = candidates(slots, AUTO_FALLBACK).filter { it.provider.online }
        return if (multi.isEmpty()) Plan(device, language, false) else Plan(multi + device, AUTO_FALLBACK, true)
    }

    /** Which engines dictate [language] now (with internet), e.g. "Uses Sarvam AI, then On this phone". */
    fun engineLine(slots: List<Slot>, language: String): String {
        val p = plan(slots, language, true)
        if (p.slots.isEmpty()) return "Not set up yet"
        return "Uses " + p.slots.joinToString(", then ") { it.provider.label }
    }

    /**
     * The language list as Settings shows it now: [LANGUAGES], with every language that has a speech pack of its own on
     * the phone moved up under the two automatic choices and marked "(on this phone)", so a downloaded pack is easy to
     * find. Codes never change.
     */
    fun languageChoices(slots: List<Slot>): List<Pair<String, String>> {
        val own = ownPacks(slots)
        if (own.isEmpty()) return LANGUAGES
        val head = LANGUAGES.take(2)
        val mine = LANGUAGES.drop(2).filter { it.first in own }.map { (c, n) -> c to "$n (on this phone)" }
        return head + mine + LANGUAGES.drop(2).filter { it.first !in own }
    }

    private fun ownPacks(slots: List<Slot>): Set<String> {
        val dev = slots.firstOrNull { !it.provider.online } ?: return emptySet()
        return SpeechPackCatalog.ownPackLanguages(dev.installed)
    }

    /** Hints under the language list entries: the automatic modes' own, plus who dictates each on-pack language. */
    fun languageHints(slots: List<Slot>): Map<String, String> =
        LANGUAGE_HINTS + ownPacks(slots).filter { c -> LANGUAGES.any { it.first == c } }.associateWith { engineLine(slots, it) }

    /** The short note when a session starts on [engine] and the last one used another (says which is in use). */
    fun engineNote(engine: SpeechProvider, pack: String? = null): String =
        if (pack != null) "Using ${engine.label} · $pack" else "Using ${engine.label}"

    /** The short note shown once per app run when Auto (Indian languages) falls back to [engine]. */
    fun autoFallbackNote(engine: SpeechProvider): String = "Sarvam AI is off, using ${engine.label}"

    /** The short notes shown when the mic uses "On this phone" because the internet is missing or went away. */
    const val OFFLINE_NOTE = "No internet, using offline mode"
    const val DROPPED_NOTE = "Connection lost, using offline mode"
    const val NO_CONNECT_NOTE = "Couldn't connect, using offline mode"

    private fun pagePath(p: SpeechProvider) = "Settings → Providers → ${p.label}"

    /** Why nothing can dictate [language] (shown when [plan] has no slots). [online] false: there is no internet. */
    fun whyNone(slots: List<Slot>, language: String, online: Boolean = true): String {
        val dev = slots.firstOrNull { !it.provider.online }
        if (!online) {
            val pack = SpeechPackCatalog.packsFor(language).firstOrNull()
            return when {
                dev == null || pack == null -> "No internet. Live dictation needs a connection for ${languageName(language)}"
                !dev.enabled -> "No internet. Turn on ${dev.provider.label} in ${pagePath(dev.provider)} to dictate offline"
                else -> "No internet. Download ${pack.name} in ${pagePath(dev.provider)} to dictate offline"
            }
        }
        val why = whyNoneFor(slots, language)
        val multi = slots.sortedBy { it.number }.firstOrNull { it.provider.online && it.provider.supports(AUTO_FALLBACK) }
        return if (language == AUTO_SARVAM && multi != null) "$why, or set up ${multi.provider.label} in ${pagePath(multi.provider)}" else why
    }

    private fun whyNoneFor(slots: List<Slot>, language: String): String {
        val goal = if (language == AUTO_SARVAM) "use ${languageName(language)}" else "dictate ${languageName(language)}"
        val online = slots.sortedBy { it.number }.filter { it.provider.online }
        // Only when no online provider knows the language, point at the offline pack instead.
        val dev = slots.firstOrNull { !it.provider.online }
        val pack = SpeechPackCatalog.packsFor(language).firstOrNull()
        if (online.none { it.provider.supports(language) } && pack != null && dev != null)
            return "Download ${pack.name} in ${pagePath(dev.provider)} to $goal"
        val knows = online.filter { it.provider.supports(language) }
        if (knows.isEmpty()) return "None of the speech providers can $goal. Pick another language in Settings → I dictate in"
        knows.firstOrNull { it.enabled && it.hasKey && !it.residencyOk }?.let {
            return "Live dictation is off until you allow ${it.provider.label} in ${pagePath(it.provider)}"
        }
        knows.firstOrNull { it.enabled && !it.hasKey }?.let {
            return "Add your ${it.provider.label} API key in ${pagePath(it.provider)} to $goal"
        }
        return "Turn on ${knows.first().provider.label} in ${pagePath(knows.first().provider)} to $goal"
    }

    /**
     * The dictation language list, in plain words (no provider names): everything Deepgram (nova-3) knows plus the
     * languages only another provider adds (SpeechProvider.extraLanguages, e.g. Malayalam from Sarvam AI). Stored by
     * code; Malayalam is "ml". Order: the two automatic choices, the English variants, then the rest by name. Codes never
     * change, so saved choices keep working.
     */
    val LANGUAGES: List<Pair<String, String>> by lazy {
        val dg = LiveDictationConfig.LANGUAGES
        val known = dg.map { it.first }.toMutableSet()
        val extra = SpeechRegistry.online.flatMap { it.extraLanguages }.filter { known.add(it.first) }
        val head = listOf(AUTO_FALLBACK to "Mixed languages (auto)", AUTO_SARVAM to "Auto (Indian languages)") + dg.subList(1, 8)
        head + (dg.drop(8) + extra).sortedBy { it.second }
    }

    /** One-line hints shown under a choice in Settings, so the two automatic modes aren't confused. */
    val LANGUAGE_HINTS: Map<String, String> = mapOf(
        AUTO_FALLBACK to "Follows a mix of major languages as you speak",
        AUTO_SARVAM to "Detects Malayalam, English and other Indian languages as you speak",
    )

    fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code
}
