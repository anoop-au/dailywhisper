package com.anoop.dailywhisper

/**
 * Settings carried over when the app's settings change shape, so nobody's choices are lost. Pure Kotlin: takes the saved
 * settings as a map and says what to write and what to remove (Settings.kt applies it once, by SETTINGS_VERSION).
 */
object SettingsMigration {
    /** What to write and remove in "settings", and flags for "state". */
    class Change(val put: Map<String, Any>, val remove: Set<String>, val state: Map<String, Any> = emptyMap())

    /** Old slot switches (v0.1.19 and before) and the provider each now belongs to. */
    val SLOT_SWITCHES: List<Pair<String, SpeechProvider>> = listOf(
        "slot1_on" to SpeechProvider.DEEPGRAM, "slot2_on" to SpeechProvider.SARVAM, "slot3_on" to SpeechProvider.ON_DEVICE,
    )
    /** "Use first" on the On this phone page: gone, the phone is only ever the fallback now. */
    const val OLD_DEVICE_FIRST = "od_first"

    /** v6: each slot's on/off moves to its provider's own switch; "Use first" goes. */
    fun toProviders(saved: Map<String, *>): Change {
        val put = LinkedHashMap<String, Any>()
        val remove = LinkedHashSet<String>()
        for ((old, provider) in SLOT_SWITCHES) {
            val v = saved[old] as? Boolean
            if (v != null && !saved.containsKey(provider.useKey)) put[provider.useKey] = v
            if (saved.containsKey(old)) remove += old
        }
        if (saved.containsKey(OLD_DEVICE_FIRST)) remove += OLD_DEVICE_FIRST
        return Change(put, remove)
    }

    // ---- v6: live translation is one question, "Live translation into" (a language, or Off) ----

    const val OLD_LIVE_TR = "live_tr"               // the old switch (only stored once touched)
    const val OLD_LIVE_TR_TARGET = "live_tr_target" // notes / zh / auto / en / ml
    const val LIVE_TR_INTO = "live_tr_into"
    /** In "state": the value was carried over from an untouched switch, so it never starts a big Opus-MT download. */
    const val LIVE_TR_CARRIED = "live_tr_into_carried"

    /** The language the old settings translated into when on (before the "speaking it goes back" rule). */
    fun liveTarget(saved: Map<String, *>): String {
        val target = saved[OLD_LIVE_TR_TARGET] as? String ?: LiveTranslation.NOTES
        val notesSource = saved["tr_source"] as? String ?: TranslateConfig.DEFAULT_SOURCE
        val notesTarget = saved["tr_target"] as? String ?: TranslateConfig.DEFAULT_TARGET
        return when (target) {
            LiveTranslation.EN, LiveTranslation.ML, LiveTranslation.ZH -> target
            LiveTranslation.AUTO -> if (notesSource == LiveTranslation.ML) LiveTranslation.EN else LiveTranslation.ML
            else -> notesTarget // "Same as notes"
        }
    }

    /**
     * The old switch and target become "Live translation into". Turned off by hand: Off. Turned on by hand: its language.
     * Never touched: on (its language) only where the old default was on: an English, Malayalam or Chinese target that
     * [usable] says the phone can translate now; else Off. A value already saved wins.
     */
    fun toLiveTranslation(saved: Map<String, *>, usable: (String) -> Boolean): Change {
        val remove = setOf(OLD_LIVE_TR, OLD_LIVE_TR_TARGET).filter { saved.containsKey(it) }.toSet()
        if (saved.containsKey(LIVE_TR_INTO)) return Change(emptyMap(), remove)
        val switch = saved[OLD_LIVE_TR] as? Boolean
        val into = liveTarget(saved)
        val on = switch ?: (into in LiveTranslation.LANGS && usable(into))
        if (!on) return Change(emptyMap(), remove)
        return Change(mapOf(LIVE_TR_INTO to into), remove, if (switch == null) mapOf(LIVE_TR_CARRIED to true) else emptyMap())
    }

    /**
     * v7: OpenRouter's "Use before the phone's translators" goes (the translation order replaces it, and the phone is
     * always the last resort); its "Give up after" becomes the one for every online translator. Pure.
     */
    fun toTranslationOrder(saved: Map<String, *>): Change {
        val put = mutableMapOf<String, Any>()
        (saved[OpenRouterTranslation.OLD_KEY_TIMEOUT] as? Int)?.let { put[TR_TIMEOUT] = it }
        return Change(put, setOf(OpenRouterTranslation.OLD_KEY_FIRST, OpenRouterTranslation.OLD_KEY_TIMEOUT))
    }
    const val TR_TIMEOUT = "tr_timeout_s"
}
