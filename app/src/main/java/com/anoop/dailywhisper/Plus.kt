package com.anoop.dailywhisper

import android.content.Context

/**
 * Daily Whisper Plus: who may use Plus features (today only the Weekly review). v0.1.27 has no payment of any kind:
 * [LocalPlus] reads one switch on this phone (Settings → Daily Whisper Plus). A store purchase later only needs another
 * [Entitlement] set in [Plus.source]; nothing else asks how Plus was got.
 */
interface Entitlement {
    /** Plus features may be used now. */
    val active: Boolean
    /** Where it comes from, for Settings ("local" for the switch). */
    val source: String
}

/** The switch on this phone (default off). No account, no network. */
class LocalPlus(private val c: Context) : Entitlement {
    override val active: Boolean get() = c.settings()[Prefs.PLUS]
    override val source: String get() = "local"
    companion object { const val KEY = "plus_on" }
}

object Plus {
    /** Makes the entitlement for a context: the local switch until a real one is plugged in. */
    @Volatile var source: (Context) -> Entitlement = { LocalPlus(it.applicationContext) }
    fun entitlement(c: Context): Entitlement = source(c)
}
