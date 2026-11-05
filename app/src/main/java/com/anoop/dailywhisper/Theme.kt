package com.anoop.dailywhisper

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.content.res.Configuration

/**
 * Light / dark / follow-system, framework only (no AppCompat): each screen overrides its own uiMode
 * night bits before resources load, so values-night colours and themes apply. Works on minSdk 30.
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

abstract class ThemedActivity : Activity() {
    private var appliedTheme: String? = null

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyChrome()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applyChrome()
    }

    /** Velvet page wash + Inter / Noto Sans Malayalam on the tree built so far. */
    private fun applyChrome() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = if (content.childCount > 0) content.getChildAt(0) else content
        root.background = VelvetDrawable(getColor(R.color.bg), getColor(R.color.glow_a), getColor(R.color.glow_b))
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val mode = newBase.settings()[Prefs.THEME]
        appliedTheme = mode
        val night = when (mode) {
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        val base = newBase.resources.configuration.uiMode
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (base and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    /** Screens left in the back stack pick up a theme change made elsewhere. */
    override fun onResume() {
        super.onResume()
        if (settings()[Prefs.THEME] != appliedTheme) recreate()
    }

    val isNight: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Header toggle: flip to the opposite of what's showing now (overrides System). */
    fun toggleTheme() {
        settings().edit().putString(Prefs.THEME.key, if (isNight) ThemeMode.LIGHT else ThemeMode.DARK).apply()
        recreate()
    }
}
