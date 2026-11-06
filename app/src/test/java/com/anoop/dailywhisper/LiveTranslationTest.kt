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

    @Test fun directionIsEnglishMalayalamOnly() {
        assertEquals("en" to "ml", LiveTranslation.direction(en, "en-IN", LiveTranslation.AUTO))
        assertEquals("ml" to "en", LiveTranslation.direction(ml, "ml", LiveTranslation.AUTO))
        assertEquals("ml" to "en", LiveTranslation.direction(ml, "multi", LiveTranslation.AUTO))
        // Dictating Hindi or Spanish: nothing, even with Latin letters.
        assertNull(LiveTranslation.direction("hola amigo", "es", LiveTranslation.AUTO))
        assertNull(LiveTranslation.direction("main aaj", "hi", LiveTranslation.AUTO))
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

    @Test fun liveTranslationIsOffWithoutAPackAndFollowsTheSwitchOnceTouched() {
        assertFalse(LiveTranslation.packInstalled(ctx))
        assertFalse(ctx.boolPref(Prefs.LIVE_TR))
        ctx.settings().edit().putBoolean(Prefs.LIVE_TR.key, true).commit()
        assertTrue(ctx.boolPref(Prefs.LIVE_TR))
        assertFalse(ctx.settings()[Prefs.LIVE_TR_PARTIALS])
        assertEquals(800, ctx.settings()[Prefs.LIVE_TR_PARTIAL_MS])
        assertEquals(600, ctx.settings()[Prefs.LIVE_TR_SIDE_DP])
    }

    @Test fun noPackMeansNoTranslationShown() {
        ctx.settings().edit().putBoolean(Prefs.LIVE_TR.key, true).commit()
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
}
