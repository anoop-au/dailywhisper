package com.anoop.dailywhisper

/**
 * Settings carried over when the app's settings change shape, so nobody's choices are lost. Pure Kotlin: takes the saved
 * settings as a map and says what to write and what to remove (Settings.kt applies it once, by SETTINGS_VERSION).
 */
object SettingsMigration {
    class Change(val put: Map<String, Any>, val remove: Set<String>)

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
}
