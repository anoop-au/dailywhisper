package com.anoop.dailywhisper

import android.os.Build
import android.os.Bundle
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Shown once after first-run setup (and once for people updating): pick Short or Long.
 * The choice is remembered; the top toggle and Settings can change it later.
 */
class ModeChooserActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WritingMode.hasChosen(this)) { finish(); return }
        setContentView(R.layout.activity_mode_chooser)
        edgeToEdge(findViewById(R.id.root)) { settings()[Prefs.MAX_WIDTH] }
        findViewById<View>(R.id.choice_short).setOnClickListener { pick(WritingMode.SHORT) }
        findViewById<View>(R.id.choice_long).setOnClickListener { pick(WritingMode.LONG) }
        // Back shouldn't skip the pick: MainActivity will reopen this until a mode is chosen.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { })
        }
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        // Stay here until Short or Long is picked.
    }

    private fun pick(mode: String) {
        WritingMode.choose(this, mode)
        finish()
    }
}
