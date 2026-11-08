package com.anoop.dailywhisper

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LiveTranslationTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val ml = "ഞാൻ ഇന്ന് ചന്തയിൽ പോയി"
    private val en = "I went to the market today"

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear()
            .putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
    }

    @After fun reset() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    // ---- language + guard ----

    @Test fun scriptDetection() {
        assertEquals("ml", LiveTranslation.Script.detect(ml))
        assertEquals("en", LiveTranslation.Script.detect(en))
        assertNull(LiveTranslation.Script.detect("मैं आज बाज़ार गया")) // Hindi
        assertNull(LiveTranslation.Script.detect("12 34 !!"))
        assertNull(LiveTranslation.Script.detect("hello ഞാൻ ഇന്ന് world again")) // mixed
    }

    @Test fun directionFollowsLiveTranslationInto() {
        assertEquals("en" to "ml", LiveTranslation.direction(en, "en-IN", LiveTranslation.ML))
        // Speaking the chosen language goes back to "I mostly write in" (English here).
        assertEquals("ml" to "en", LiveTranslation.direction(ml, "auto-sarvam", LiveTranslation.ML))
        assertEquals("ml" to "en", LiveTranslation.direction(ml, "ml", LiveTranslation.ML))
        assertEquals("ml" to "en", LiveTranslation.direction(ml, "multi", LiveTranslation.ML))
        assertNull("Off", LiveTranslation.direction(en, "en", LiveTranslation.OFF))
        // A dictation language the translators don't know: nothing, even with Latin letters.
        assertNull(LiveTranslation.direction("hola amigo", "es", LiveTranslation.ML))
        assertNull(LiveTranslation.direction("main aaj", "hi", LiveTranslation.ML))
        // One they know (any language now): the dictation language is the source.
        val known: (String) -> Boolean = { it in setOf("es", "hi", "en", "ml", "zh") }
        assertEquals("es" to "en", LiveTranslation.direction("hola amigo", "es", LiveTranslation.EN, known = known))
        assertEquals("hi" to "ml", LiveTranslation.direction("मैं आज बाज़ार गया", "hi", LiveTranslation.ML, known = known))
        assertEquals("en" to "es", LiveTranslation.direction(en, "en-AU", "es", known = known))
        // Target is the spoken language: nothing to do.
        assertNull(LiveTranslation.direction(en, "en", LiveTranslation.EN))
        assertEquals("en" to "ml", LiveTranslation.direction(en, "en", LiveTranslation.ML))
    }

    @Test fun guardDropsJunk() {
        assertTrue(LiveTranslation.sane(en, ml, "ml"))
        assertTrue(LiveTranslation.sane(ml, en, "en"))
        assertFalse(LiveTranslation.sane(en, null, "ml"))
        assertFalse(LiveTranslation.sane(en, "  ", "ml"))
        assertFalse(LiveTranslation.sane(en, en, "ml"))                 // echo
        assertFalse(LiveTranslation.sane(en, "I went to the shop", "ml")) // wrong script
        assertFalse(LiveTranslation.sane("Yes", ml.repeat(6), "ml"))     // runaway length
        assertFalse(LiveTranslation.sane(ml, "the the the the market", "en"))
        assertFalse(LiveTranslation.sane(ml, "I went home I went home I went home", "en"))
        assertTrue(LiveTranslation.loops("go go go go"))
        assertFalse(LiveTranslation.loops("I went to the market and then I went home"))
    }

    // ---- segments ----

    @Test fun segmentsStayInSpokenOrderWhateverOrderTranslationsArrive() {
        val s = LiveTranslation.Segments()
        val a = s.addFinal("one"); val b = s.addFinal("two"); val c = s.addFinal("three")
        s.setTranslation(c, "C"); s.setTranslation(a, "A")
        assertEquals("A C", s.translation())
        s.setTranslation(b, "B")
        assertEquals("A B C", s.translation())
        assertEquals("one two three", s.originals())
        assertFalse(s.setTranslation(99, "X"))
    }

    @Test fun partialIsReplacedByItsFinalAndStaleOnesAreIgnored() {
        val s = LiveTranslation.Segments(prefixOriginal = "earlier", prefixTranslation = "E")
        val g1 = s.setPartial("I went")
        val g2 = s.setPartial("I went to the")
        assertFalse(s.setPartialTranslation(g1, "old"))       // superseded
        assertTrue(s.setPartialTranslation(g2, "P"))
        assertEquals("E P", s.translation())
        assertEquals("E", s.finalTranslation())                // partials are never saved
        val f = s.addFinal("I went to the market")
        assertEquals("E", s.translation())                     // the final drops the partial
        assertFalse(s.setPartialTranslation(g2, "late"))
        s.setTranslation(f, "F")
        assertEquals("E F", s.finalTranslation())
        s.addFinal("   ")                                      // blank final adds nothing
        assertEquals("E F", s.translation())
    }

    @Test fun failedSegmentLeavesAGapNotJunk() {
        val s = LiveTranslation.Segments()
        val a = s.addFinal("one"); val b = s.addFinal("two")
        s.setTranslation(a, null); s.setTranslation(b, "B")
        assertEquals("B", s.translation())
    }

    // ---- settings ----

    @Test fun liveTranslationIsOffByDefaultAndFollowsTheChoice() {
        assertEquals(LiveTranslation.OFF, ctx.settings()[Prefs.LIVE_TR_INTO])
        assertFalse(LiveTranslator(ctx) {}.enabled)
        // Malayalam needs its offline model: chosen, it's on, but nothing can translate it yet.
        ctx.settings().edit().putString(Prefs.LIVE_TR_INTO.key, LiveTranslation.ML).commit()
        assertTrue(LiveTranslator(ctx) {}.enabled)
        assertFalse(LiveTranslation.usable(ctx))
        assertFalse(ctx.settings()[Prefs.LIVE_TR_PARTIALS])
        assertEquals(800, ctx.settings()[Prefs.LIVE_TR_PARTIAL_MS])
        assertEquals(600, ctx.settings()[Prefs.LIVE_TR_SIDE_DP])
    }

    @Test fun noPackMeansNoTranslationShown() {
        ctx.settings().edit().putString(Prefs.LIVE_TR_INTO.key, LiveTranslation.ML).commit()
        var shown = "unset"
        val lt = LiveTranslator(ctx) { shown = it }
        lt.begin()
        lt.onFinal(en)
        assertEquals("", shown)
        assertEquals("", lt.finalTranslation())
    }

    // ---- layout ----

    @Test fun stackedOnPhoneSideBySideWhenWide() {
        val pair = LinearLayout(ctx); val a = TextView(ctx); val b = TextView(ctx)
        pair.addView(a); pair.addView(b)
        val narrow = Configuration(ctx.resources.configuration).apply { screenWidthDp = 411 }
        val wide = Configuration(ctx.resources.configuration).apply { screenWidthDp = 840 }
        LiveTranslator.arrange(ctx, pair, a, b, narrow)
        assertEquals(LinearLayout.VERTICAL, pair.orientation)
        LiveTranslator.arrange(ctx, pair, a, b, wide)
        assertEquals(LinearLayout.HORIZONTAL, pair.orientation)
        assertEquals(1f, (a.layoutParams as LinearLayout.LayoutParams).weight)
        b.visibility = View.GONE // nothing to show: the original keeps the full width
        LiveTranslator.arrange(ctx, pair, a, b, wide)
        assertEquals(LinearLayout.VERTICAL, pair.orientation)
    }

    // ---- storage ----

    @Test fun translationIsSavedAndShownAgainWhenTheNoteIsReopened() {
        val store = NoteStore.get(ctx)
        val id = store.add("I went to the market today", NoteSchema.SHORT, "ഞാൻ ഇന്ന് ചന്തയിൽ പോയി")
        assertEquals("ഞാൻ ഇന്ന് ചന്തയിൽ പോയി", store.get(id)!!.translation)
        val plain = store.add("typed only")
        assertNull(store.get(plain)!!.translation)
        store.setTranslation(plain, "  ")
        assertNull(store.get(plain)!!.translation)

        val note = Robolectric.buildActivity(NoteActivity::class.java,
            android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()
        val tr = note.findViewById<TextView>(R.id.note_tr)
        assertEquals(View.VISIBLE, tr.visibility)
        assertEquals("ഞാൻ ഇന്ന് ചന്തയിൽ പോയി", tr.text.toString())
        val other = Robolectric.buildActivity(NoteActivity::class.java,
            android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, plain)).setup().get()
        assertEquals(View.GONE, other.findViewById<View>(R.id.note_tr).visibility)
    }

    @Test fun existingV3NotesUpgradeWithNoTranslation() {
        val file = ctx.getDatabasePath("notes.db")
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        NoteSchema.CREATE_V3.forEach(db::execSQL)
        NoteSchema.ftsObjects("simple").forEach { runCatching { db.execSQL(it) } }
        db.execSQL("INSERT INTO notes (text, created, updated, status, kind, duration_ms) VALUES ('a long talk', 1000, 2000, 'active', 'long', 65000)")
        db.execSQL("PRAGMA user_version = 3")
        db.close()
        val store = NoteStore.get(ctx)
        val n = store.active(NoteSchema.LONG).single()
        assertEquals("a long talk", n.text)
        assertEquals(65000L, n.durationMs)
        assertNull(n.translation)
        store.setTranslation(n.id, "ഒരു നീണ്ട സംസാരം")
        assertEquals("ഒരു നീണ്ട സംസാരം", store.get(n.id)!!.translation)
        assertEquals(n.id, store.search("long talk", includeArchived = false).single().id)
    }

    @Test fun composerHasNoTranslationLineUntilThereIsOne() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.GONE, main.findViewById<View>(R.id.composer_tr).visibility)
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.composer).visibility)
    }

    // ---- Chinese ----

    @Test fun chineseIsDetectedAndTranslatedBothWays() {
        assertEquals("zh", LiveTranslation.Script.detect("我今天去了市场"))
        assertEquals("zh", LiveTranslation.Script.detect("我今天去了 market")) // a borrowed word doesn't flip it
        assertEquals("en" to "zh", LiveTranslation.direction(en, "en", LiveTranslation.ZH))
        assertEquals("zh" to "en", LiveTranslation.direction("我今天去了市场", "zh-CN", LiveTranslation.ZH)) // speaking Chinese → English
        assertEquals("zh" to "en", LiveTranslation.direction("我今天去了市场", "multi", LiveTranslation.EN))
        assertNull(LiveTranslation.direction("我今天去了市场", "ja", LiveTranslation.ZH))
    }

    @Test fun speakingTheChosenLanguageGoesBack() {
        assertEquals("zh", LiveTranslation.target("en", "zh", "en"))
        assertEquals("en", LiveTranslation.target("zh", "zh", "en"))      // speaking the target goes back
        assertEquals("ml", LiveTranslation.target("zh", "zh", "ml"))      // ... to "I mostly write in"
        assertEquals("es", LiveTranslation.target("en", "es", "en"))      // any language now
        assertNull(LiveTranslation.target("en", LiveTranslation.OFF, "en"))
        assertNull(LiveTranslation.target("en", "en", "en"))
        assertEquals(listOf("en" to "zh", "zh" to "en"), LiveTranslation.expectedPairs("zh", "en"))
        assertEquals(listOf("en" to "ml", "ml" to "en"), LiveTranslation.expectedPairs("ml", "en"))
        assertTrue(LiveTranslation.expectedPairs(LiveTranslation.OFF, "en").isEmpty())
    }

    @Test fun chineseGuard() {
        assertTrue(LiveTranslation.sane(en, "我今天去了市场", "zh"))                  // much shorter is normal for Chinese
        assertTrue(LiveTranslation.sane("我今天去了市场", en, "en"))                  // and much longer coming back
        assertFalse(LiveTranslation.sane(en, "我我我我我", "zh"))
        assertFalse(LiveTranslation.sane(en, "市场市场市场", "zh"))
        assertFalse(LiveTranslation.sane(en, ml, "zh"))                    // wrong script
        assertFalse(LiveTranslation.sane("我今天去了市场", "我今天去了市场", "en"))
    }

    @Test fun chineseSegmentsJoinWithoutSpaces() {
        val s = LiveTranslation.Segments()
        val a = s.addFinal("I went to the market."); val b = s.addFinal("It was busy.")
        s.setTranslation(a, "我去了市场。"); s.setTranslation(b, "很忙。")
        assertEquals("我去了市场。很忙。", s.translation())
        assertEquals("one two", LiveTranslation.join(listOf("one", "two")))
    }

    @Test fun chineseCarriesOverFromTheOldDefault() {
        // v0.1.19 with notes English → Chinese and the switch never touched: on, into Chinese.
        ctx.uiState().edit().putInt("settings_version", 5).commit()
        ctx.getSharedPreferences("settings", 0).edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "zh").commit()
        assertEquals(LiveTranslation.ZH, ctx.settings()[Prefs.LIVE_TR_INTO])
        assertTrue(LiveTranslation.usable(ctx))
        assertTrue(ctx.uiState().getBoolean(SettingsMigration.LIVE_TR_CARRIED, false))
    }

    /** The cause of "nothing shows": with notes set to English → Malayalam the old build only kept the notes models,
     *  so choosing Malayalam live translation must also keep (and fetch) the Malayalam ones it needs. */
    @Test fun liveTranslationKeepsTheMalayalamModelsItNeeds() {
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "zh").commit()
        assertEquals(listOf("en-zh"), OpusPacks.wanted(ctx))
        ctx.settings().edit().putString(Prefs.LIVE_TR_INTO.key, LiveTranslation.ML).commit()
        assertEquals(listOf("en-zh", "en-ml", "ml-en"), OpusPacks.wanted(ctx))
        // Carried over automatically (not picked by hand): no big download on its own.
        ctx.uiState().edit().putBoolean(SettingsMigration.LIVE_TR_CARRIED, true).commit()
        assertEquals(listOf("en-zh"), OpusPacks.wanted(ctx))
        ctx.uiState().edit().remove(SettingsMigration.LIVE_TR_CARRIED).commit()
        // Chinese live translation needs no extra Opus-MT download (ML Kit fills in until notes models are there).
        ctx.settings().edit().putString(Prefs.LIVE_TR_INTO.key, LiveTranslation.ZH).commit()
        assertEquals(listOf("en-zh"), OpusPacks.wanted(ctx))
    }
}
