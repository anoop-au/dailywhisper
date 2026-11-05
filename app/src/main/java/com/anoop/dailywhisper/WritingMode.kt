package com.anoop.dailywhisper

import android.content.Context

/**
 * Short notes (quick thoughts, listed under Today) or Long sessions (keep talking until you stop).
 * Chosen once on first open, then remembered; the top toggle and Settings both update it.
 */
object WritingMode {
    const val SHORT = "short"
    const val LONG = "long"
    /** uiState flag: the first-open chooser has been answered (so it doesn't appear again). */
    const val KEY_CHOSEN = "mode_chosen"

    fun current(ctx: Context): String = ctx.settings()[Prefs.WRITING_MODE]
    fun isLong(ctx: Context): Boolean = current(ctx) == LONG
    fun hasChosen(ctx: Context): Boolean = ctx.uiState().getBoolean(KEY_CHOSEN, false)

    /** First-open pick: remember the mode and don't show the chooser again. */
    fun choose(ctx: Context, mode: String) {
        set(ctx, mode)
        ctx.uiState().edit().putBoolean(KEY_CHOSEN, true).apply()
    }

    /** Top toggle or Settings: remember the mode (the chooser is already done). */
    fun set(ctx: Context, mode: String) {
        val value = if (mode == LONG) LONG else SHORT
        ctx.settings().edit().putString(Prefs.WRITING_MODE.key, value).apply()
    }
}
