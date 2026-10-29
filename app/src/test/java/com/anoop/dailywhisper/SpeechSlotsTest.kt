package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The 4 speech slots, the pause setting and the welcome time, through the real settings on Robolectric. */
@RunWith(RobolectricTestRunner::class)
class SpeechSlotsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    /** SecretStore.has() only looks for the entry (Robolectric has no Android Keystore), so a placeholder is enough. */
    private fun fakeKey(name: String) = ctx.getSharedPreferences("secrets", 0).edit().putString(name, "placeholder").commit()
    private fun region(code: String) = ctx.setUserRegion(code)
    private fun lang(code: String) = ctx.settings().edit().putString(Prefs.DG_LANGUAGE.key, code).commit()
    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun defaults() {
        val p = ctx.settings()
        assertEquals(1300, p[Prefs.PAUSE_MS])
        assertEquals(2500, p[Prefs.WELCOME_MS])
        assertTrue(p[Prefs.SLOT1_ON]); assertTrue(p[Prefs.SLOT2_ON])
        assertEquals("saaras:v3-realtime", p[Prefs.SARVAM_MODEL]); assertEquals("transcribe", p[Prefs.SARVAM_MODE])
        assertEquals(4, ctx.speechSlots().size)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.NONE, SpeechProvider.NONE),
            ctx.speechSlots().map { it.provider })
    }

    @Test fun deepgramFirstThenSarvam() {
        region("in")
        fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("hi")
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM), ps.map { it.provider })
        assertTrue(ps[0].url, ps[0].url.startsWith("wss://api.in.deepgram.com/v1/listen?") && ps[0].url.contains("language=hi") &&
            ps[0].url.contains("endpointing=1300") && ps[0].url.contains("mip_opt_out=true"))
        assertTrue(ps[1].url, ps[1].url.startsWith("wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=hi-IN") &&
            ps[1].url.contains("silence_duration_ms=1300"))
    }

    @Test fun pauseSettingReachesBothProviders() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("ta")
        ctx.settings().edit().putInt(Prefs.PAUSE_MS.key, 2000).commit()
        val ps = StreamingLiveDictation.protocols(ctx)
        assertTrue(ps[0].url.contains("endpointing=2000")); assertTrue(ps[1].url.contains("silence_duration_ms=2000"))
    }

    @Test fun malayalamSkipsDeepgram() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("ml")
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.SARVAM), ps.map { it.provider })
        assertTrue(ps[0].url.contains("language_code=ml-IN"))
    }

    @Test fun malayalamWithoutSarvamKeySaysWhat() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); lang("ml")
        val msg = runCatching { StreamingLiveDictation.protocols(ctx) }.exceptionOrNull()?.message ?: ""
        assertTrue(msg, msg.contains("Sarvam AI API key") && msg.contains("Malayalam"))
    }

    @Test fun noDeepgramKeyFallsToSarvam() {
        region("in"); fakeKey(SecretStore.SLOT2_API_KEY); lang("en")
        assertEquals(listOf(SpeechProvider.SARVAM), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    @Test fun slotSwitchedOffIsSkipped() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("en")
        ctx.settings().edit().putBoolean(Prefs.SLOT1_ON.key, false).commit()
        assertEquals(listOf(SpeechProvider.SARVAM), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    @Test fun sarvamOutsideIndiaNeedsConsent() {
        region("au"); fakeKey(SecretStore.SLOT2_API_KEY); lang("ml")
        assertFalse(ctx.sarvamAllowed())
        val msg = runCatching { StreamingLiveDictation.protocols(ctx) }.exceptionOrNull()?.message ?: ""
        assertTrue(msg, msg.contains("allow Sarvam AI"))
        ctx.setSarvamConsent(true)
        assertEquals(listOf(SpeechProvider.SARVAM), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    @Test fun micShowsForSarvamOnlyWithConsent() {
        region("nz"); ctx.setDgConsent("") // Deepgram not allowed
        val m1 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.GONE, m1.findViewById<View>(R.id.mic).visibility)
        fakeKey(SecretStore.SLOT2_API_KEY)
        val m2 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("Sarvam set up but India not allowed", View.GONE, m2.findViewById<View>(R.id.mic).visibility)
        ctx.setSarvamConsent(true)
        val m3 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, m3.findViewById<View>(R.id.mic).visibility)
    }

    @Test fun settingsShowsFourSlots() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val t = texts(a.window.decorView)
        for (s in listOf("SPEECH SLOT 1 · DEEPGRAM", "SPEECH SLOT 2 · SARVAM AI", "SPEECH SLOTS 3 & 4", "Speech slot 3", "Speech slot 4",
            "Pause before a sentence ends", "Welcome screen time", "Sarvam AI API key", "Deepgram API key"))
            assertTrue("$s on $t", t.contains(s))
        assertTrue(t.contains("1300 ms")); assertTrue(t.contains("2500 ms"))
        assertEquals("two keys and slots 3 and 4 show Not set", 4, t.count { it == "Not set" })
    }

    // Robolectric runs the welcome's 450 ms fade during setup(), so the timer starts up to ~0.5 s before setup() returns.
    @Test fun welcomeTimeIsASetting() {
        ctx.uiState().edit().remove(SetupActivity.KEY_DONE).commit()
        ctx.settings().edit().putInt(Prefs.WELCOME_MS.key, 1000).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertTrue("still welcome", texts(a.window.decorView).contains("Say it softly. Keep it close."))
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS) // 1 s: a 2.5 s welcome would still be showing
        assertTrue(texts(a.window.decorView).toString(), texts(a.window.decorView).contains("Which language do you usually write or speak your notes in?"))
    }

    @Test fun welcomeDefaultIs2500() {
        ctx.uiState().edit().remove(SetupActivity.KEY_DONE).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper(1900, TimeUnit.MILLISECONDS) // ~2-2.4 s since it appeared (the old 1.75 s would be gone)
        assertTrue("still welcome " + texts(a.window.decorView), texts(a.window.decorView).contains("Say it softly. Keep it close."))
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        assertFalse(texts(a.window.decorView).contains("Say it softly. Keep it close."))
    }

    // ---- "Auto (Sarvam AI)": Sarvam's own language detection ----

    @Test fun autoSarvamGoesOnlyToSarvamWithAuto() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang(SpeechSlots.AUTO_SARVAM)
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.SARVAM), ps.map { it.provider })
        assertTrue(ps[0].url, ps[0].url.startsWith("wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=auto&") &&
            ps[0].url.contains("silence_duration_ms=1300") && !ps[0].url.contains("placeholder"))
    }

    @Test fun autoSarvamWithoutSarvamKeyFallsBackToDeepgramMulti() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); lang(SpeechSlots.AUTO_SARVAM)
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM), ps.map { it.provider })
        assertTrue(ps[0].url, ps[0].url.contains("language=multi") && !ps[0].url.contains("auto-sarvam"))
    }

    @Test fun autoSarvamSwitchedOffFallsBackToDeepgramMulti() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang(SpeechSlots.AUTO_SARVAM)
        ctx.settings().edit().putBoolean(Prefs.SLOT2_ON.key, false).putBoolean(Prefs.SARVAM_CODEMIX.key, true).commit()
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM), ps.map { it.provider })
        assertTrue(ps[0].url, ps[0].url.startsWith("wss://api.in.deepgram.com/v1/listen?") && ps[0].url.contains("language=multi") &&
            !ps[0].url.contains("codemix"))
    }

    @Test fun autoSarvamNotAllowedFallsBackToDeepgramMulti() {
        region("au"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang(SpeechSlots.AUTO_SARVAM)
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM), ps.map { it.provider })
        assertTrue(ps[0].url.contains("language=multi"))
        ctx.setSarvamConsent(true) // once India is allowed, Auto goes back to Sarvam AI
        assertEquals(listOf(SpeechProvider.SARVAM), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    @Test fun autoSarvamNeitherUsableSaysSo() {
        region("in"); lang(SpeechSlots.AUTO_SARVAM)
        ctx.settings().edit().putBoolean(Prefs.SLOT2_ON.key, false).commit()
        val msg = runCatching { StreamingLiveDictation.protocols(ctx) }.exceptionOrNull()?.message ?: ""
        assertEquals("Turn on speech slot 2 (Sarvam AI) in Settings to use Auto (Sarvam AI), or set up Deepgram in Settings → Speech slot 1", msg)
    }

    @Test fun savedMultiStillDeepgramFirst() {
        region("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("multi")
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM), ps.map { it.provider })
        assertTrue(ps[0].url.contains("language=multi")); assertTrue(ps[1].url.contains("language_code=auto"))
    }

    @Test fun languageLabelsInSettings() {
        lang("multi")
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.contains("Multilingual (Deepgram)\nDeepgram follows a mix of major languages as you speak"))
        lang(SpeechSlots.AUTO_SARVAM)
        val b = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(texts(b.window.decorView).contains("Auto (Sarvam AI)\nDetects Malayalam, English and other Indian languages as you speak"))
        lang("en")
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue("plain choices have no hint", texts(c.window.decorView).contains("English"))
    }
}
