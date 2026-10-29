package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Live dictation's "Keep English words in English" switch: Sarvam AI's code-mixed output for Auto (Sarvam AI). */
@RunWith(RobolectricTestRunner::class)
class SarvamCodemixTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val title = "Keep English words in English"

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private fun fakeKey(name: String) = ctx.getSharedPreferences("secrets", 0).edit().putString(name, "placeholder").commit()
    private fun lang(code: String) = ctx.settings().edit().putString(Prefs.DG_LANGUAGE.key, code).commit()
    private fun codemix(on: Boolean) = ctx.settings().edit().putBoolean(Prefs.SARVAM_CODEMIX.key, on).commit()
    private fun setUp(language: String) {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang(language)
    }
    private fun sarvamUrl() = StreamingLiveDictation.protocols(ctx).first { it.provider == SpeechProvider.SARVAM }.url
    private fun find(v: View, text: String): TextView? = when (v) {
        is TextView -> v.takeIf { it.text.toString() == text }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), text) }
        else -> null
    }
    /** The Settings row holding [title]: title TextView -> texts column -> row. */
    private fun row(a: SettingsActivity): View = find(a.window.decorView, title)!!.parent.parent as View
    private fun sarvamPage(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java,
        android.content.Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "sarvam")).setup().get()

    @Test fun defaultIsOff() {
        assertFalse(Prefs.SARVAM_CODEMIX.def)
        assertFalse(ctx.settings()[Prefs.SARVAM_CODEMIX])
        assertTrue(Prefs.SARVAM_CODEMIX.summary.contains("Off by default"))
        assertTrue(Prefs.providerPages.first { it.provider == SpeechProvider.SARVAM }.prefs.contains(Prefs.SARVAM_CODEMIX))
    }

    @Test fun offSendsNormalMode() {
        setUp(SpeechSlots.AUTO_SARVAM)
        val u = sarvamUrl()
        assertTrue(u, u.contains("language_code=auto") && u.contains("mode=transcribe") && !u.contains("codemix"))
    }

    @Test fun onSendsCodemixForAutoSarvam() {
        setUp(SpeechSlots.AUTO_SARVAM); codemix(true)
        val u = sarvamUrl()
        assertTrue(u, u.contains("language_code=auto") && u.contains("&mode=codemix&") && !u.contains("mode=transcribe"))
    }

    @Test fun onWorksWithV4Too() {
        setUp(SpeechSlots.AUTO_SARVAM); codemix(true)
        ctx.settings().edit().putString(Prefs.SARVAM_MODEL.key, "saaras:v4").commit()
        val u = sarvamUrl()
        assertTrue(u, u.contains("model=saaras%3Av4") && u.contains("mode=codemix"))
    }

    @Test fun onDoesNotTouchOtherLanguagesOrDeepgram() {
        setUp("hi"); codemix(true)
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM), ps.map { it.provider })
        assertFalse(ps[0].url, ps[0].url.contains("mode=") || ps[0].url.contains("codemix"))
        assertTrue(ps[1].url, ps[1].url.contains("mode=transcribe"))
        codemix(false)
        assertEquals(ps[0].url, StreamingLiveDictation.protocols(ctx)[0].url) // Deepgram identical on or off
    }

    @Test fun slot2OutputStillAppliesWhenSwitchOff() {
        setUp(SpeechSlots.AUTO_SARVAM)
        ctx.settings().edit().putString(Prefs.SARVAM_MODE.key, "translit").commit()
        assertTrue(sarvamUrl().contains("mode=translit"))
        codemix(true)
        assertTrue(sarvamUrl().contains("mode=codemix"))
    }

    @Test fun livesOnTheSarvamPage() {
        lang("en")
        val main = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertNull("not on the main screen", find(main.window.decorView, title))
        val b = sarvamPage()
        assertEquals(View.VISIBLE, row(b).visibility)
        assertNotNull(find(b.window.decorView, Prefs.SARVAM_CODEMIX.summary))
    }

    @Test fun tappingSavesIt() {
        setUp(SpeechSlots.AUTO_SARVAM)
        val a = sarvamPage()
        row(a).performClick()
        assertTrue(ctx.settings()[Prefs.SARVAM_CODEMIX])
        assertTrue(sarvamUrl().contains("mode=codemix"))
        val b = sarvamPage()
        row(b).performClick()
        assertFalse(ctx.settings()[Prefs.SARVAM_CODEMIX])
    }

    @Test fun resetToDefaultsTurnsItOff() {
        codemix(true)
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        find(a.window.decorView, "Reset to defaults")!!.performClick()
        assertFalse(ctx.settings()[Prefs.SARVAM_CODEMIX])
    }

    @Test fun modeFunction() {
        val a = SpeechSlots.AUTO_SARVAM
        assertEquals("codemix", SarvamConfig.mode("transcribe", true, a))
        assertEquals("transcribe", SarvamConfig.mode("transcribe", false, a))
        assertEquals("transcribe", SarvamConfig.mode("transcribe", true, "ml"))
        assertEquals("transcribe", SarvamConfig.mode("transcribe", true, "multi"))
    }
}
