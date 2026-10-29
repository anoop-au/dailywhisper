package com.anoop.dailywhisper

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** The simpler Settings layout (Anoop's drawing, Oct 2026): main screen, provider pages, and nothing left out. */
@RunWith(RobolectricTestRunner::class)
class SettingsLayoutTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        ctx.setUserRegion("au")
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun main() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private fun page(id: String) = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, id)).setup().get()

    /** Every setting declared in [Prefs] (read by reflection, so a new one can't be forgotten) is on some screen, once. */
    @Test fun everySettingIsReachableOnce() {
        val declared = Prefs::class.java.declaredFields.filter { Pref::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(Prefs) as Pref }
        val shown = Prefs.sections.flatMap { it.second } + Prefs.providerPages.flatMap { it.prefs + it.residency } + Prefs.emptySlots
        assertEquals("shown once each", shown.size, shown.toSet().size)
        assertEquals("missing: " + (declared - shown.toSet()).map { it.key }, declared.toSet(), shown.toSet())
        assertTrue(declared.size >= 40)
    }

    @Test fun mainScreenOrderAndValues() {
        val t = texts(main().window.decorView)
        val labels = listOf("Dictation", "Speech providers · tried in order", "Notes", "Composer", "Translation", "Look & layout", "Advanced")
        assertEquals(labels, labels.sortedBy { t.indexOf(it) })
        assertTrue(labels.all { it in t })
        for ((title, value) in listOf("Mic button" to null, "Language" to "English", "Region" to "Australia", "Keep screen on" to null,
            "Sentence pause" to "1.3 s", "Text size" to "16 sp", "Preview length" to "5 lines", "Highlight colour" to null)) {
            assertTrue("$title on $t", title in t)
            if (value != null) assertEquals("$title value", value, t[t.indexOf(title) + 1 + t.drop(t.indexOf(title) + 1).indexOf(value)])
        }
        assertTrue("Reset stays", "Reset to defaults" in t)
        assertTrue(t.contains("Run first-time setup again") && t.contains("Opus-MT search width") && t.contains("Download on Wi-Fi only"))
    }

    @Test fun providerRowOpensItsPage() {
        val a = main()
        val row = all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && "Deepgram" in texts(it) && "Sarvam AI" !in texts(it) }
        row.performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals("deepgram", next.getStringExtra(SettingsActivity.EXTRA_PAGE))
    }

    @Test fun deepgramPageMatchesTheDrawing() {
        val t = texts(page("deepgram").window.decorView)
        for (s in listOf("Deepgram", "Use Deepgram", "API key", "Not set", "Model", "nova-3", "Smart formatting", "Punctuation and numbers",
            "Processing region", "Audio stays in Australia")) assertTrue("$s on $t", s in t)
        ctx.getSharedPreferences("secrets", 0).edit().putString(SecretStore.STT_API_KEY, "placeholder").commit()
        val k = texts(page("deepgram").window.decorView)
        assertTrue(k.toString(), "Saved on this phone" in k && "•••••" in k && "placeholder" !in k)
    }

    @Test fun sarvamPageHasItsSettingsAndConsentFooter() {
        val a = page("sarvam")
        val t = texts(a.window.decorView)
        for (s in listOf("Sarvam AI", "Use Sarvam AI", "API key", "Model", "Output", "Keep English words in English",
            "Off: needs your OK to send audio to India")) assertTrue("$s on $t", s in t)
        all(a.window.decorView).first { it is TextView && it.text.startsWith("Off: needs your OK") }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertTrue(ctx.sarvamConsent())
        assertTrue(texts(a.window.decorView).contains("Audio goes to India. You allowed this"))
    }

    @Test fun numberDialogSavesAndDefaultRestores() {
        val a = main()
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Sentence pause" }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val bar = all(d.window!!.decorView).first { it is android.widget.SeekBar } as android.widget.SeekBar
        bar.progress = bar.progress + 2 // +200 ms
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1500, ctx.settings()[Prefs.PAUSE_MS])
        assertTrue(texts(a.window.decorView).contains("1.5 s"))
        all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup && texts(it).firstOrNull() == "Sentence pause" }.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(SpeechSlots.DEFAULT_PAUSE_MS, ctx.settings()[Prefs.PAUSE_MS])
    }

    @Test fun addProviderExplainsEmptySlots() {
        val a = main()
        all(a.window.decorView).first { it is TextView && it.text == "Add provider" }.performClick()
        ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val msg = all(d.window!!.decorView).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }
        assertTrue(msg, msg.contains("Speech slot 3: not set") && msg.contains("Speech slot 4: not set"))
    }
}
