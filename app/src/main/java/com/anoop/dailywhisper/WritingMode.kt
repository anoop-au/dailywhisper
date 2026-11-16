package com.anoop.dailywhisper

/**
 * Short notes (quick thoughts) and Long sessions (keep talking until you stop). Since v0.1.27 there is no Short/Long
 * switch or first-run choice: Home is one feed of both, Short is how you write by default, and a session starts from the
 * composer (hold the mic) or Home ⋮ → Start a session. [Prefs.WRITING_MODE] is kept, unread, so going back to an older
 * version still finds it.
 */
object WritingMode {
    const val SHORT = "short"
    const val LONG = "long"
    /** uiState flag from the old first-open chooser (no longer shown); kept for downgrade. */
    const val KEY_CHOSEN = "mode_chosen"
}
