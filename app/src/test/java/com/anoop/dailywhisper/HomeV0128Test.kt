package com.anoop.dailywhisper

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** v0.1.28 Home redesign (Anoop's chosen design): wordmark, date line, Latest quote, time-of-day groups, serif rows. */
@RunWith(RobolectricTestRunner::class)
class HomeV0128Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val settings get() = ctx.getSharedPreferences("settings", 0)

    @Before fun clean() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        settings.edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private fun home() = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { ShadowLooper.idleMainLooper() }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(m: MainActivity) = all(m.findViewById(R.id.notes)).filterIsInstance<TextView>().map { it.text.toString() }
    private fun addAt(text: String, hoursAgo: Double): Long {
        val s = NoteStore.get(ctx); val id = s.add(text)
        val t = System.currentTimeMillis() - (hoursAgo * 3600_000).toLong()
        s.writableDatabase.execSQL("UPDATE notes SET created = $t, updated = $t WHERE id = $id"); return id
    }

    @Test fun wordmarkIsSerifWithAGoldWhisperAndTheDateUnderIt() {
        val m = home()
        val w = m.findViewById<TextView>(R.id.wordmark)
        assertEquals("Daily Whisper", w.text.toString())
        val spans = (w.text as Spanned).getSpans(0, w.text.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(ctx.getColor(R.color.wordmark_gold), spans[0].foregroundColor)
        assertEquals("Whisper", w.text.subSequence((w.text as Spanned).getSpanStart(spans[0]), w.text.length).toString())
        assertNotNull(w.typeface)
        val date = m.findViewById<TextView>(R.id.subtitle)
        assertEquals(HomeFeed.dateLine(java.time.LocalDate.now()), date.text.toString())
        assertTrue("letter-spaced", date.letterSpacing > 0.1f)
        assertTrue(date.isAllCaps)
    }

    @Test fun latestQuoteThenGroupsWithCounts() {
        addAt("older one", 0.5); Thread.sleep(2)
        val newest = addAt("the newest thought", 0.0)
        val m = home()
        val t = texts(m)
        assertEquals("Latest", t.first())
        assertTrue(t.contains("\u201Cthe newest thought\u201D"))
        assertTrue("the latest has 'just now'", t.any { it.endsWith("· just now") })
        assertEquals("the newest isn't repeated below", 1, t.count { it.contains("the newest thought") })
        assertTrue(t.contains("older one"))
        assertTrue("a group label with the word", t.any { it.endsWith(" Whispers") })
        assertTrue("a count pill", t.contains("1"))
        // The quote is a note too: tagged, so tap/select work on it.
        val box = m.findViewById<ViewGroup>(R.id.notes)
        assertTrue((0 until box.childCount).any { box.getChildAt(it).tag == newest })
    }

    @Test fun latestCanBeTurnedOffAndTheWordChanged() {
        addAt("older one", 0.5); Thread.sleep(2); addAt("the newest thought", 0.0)
        settings.edit().putBoolean(Prefs.SHOW_LATEST.key, false).putString(Prefs.GROUP_WORD.key, "notes").commit()
        val t = texts(home())
        assertFalse(t.contains("Latest"))
        assertTrue(t.contains("the newest thought"))
        assertTrue(t.any { it.endsWith(" notes") })
        assertFalse(t.any { it.endsWith(" Whispers") })
    }

    @Test fun timeOfDayBoundsComeFromSettings() {
        val now = java.time.LocalTime.now().hour
        // Everything today is "Evening" when every other part starts later than now… simplest: make Evening start at 0.
        settings.edit().putInt(Prefs.MORNING_FROM.key, 23).putInt(Prefs.AFTERNOON_FROM.key, 23).putInt(Prefs.EVENING_FROM.key, 0)
            .putInt(Prefs.NIGHT_FROM.key, 23).commit()
        addAt("a", 0.0); Thread.sleep(2); addAt("b", 0.0)
        val t = texts(home())
        if (now < 23) assertTrue(t.toString(), t.contains("Evening Whispers"))
    }

    @Test fun rowsAreSerifWithSingleDotMeta() {
        val s = NoteStore.get(ctx)
        val id = s.add("a session note", NoteSchema.LONG); s.setDuration(id, 60_000)
        Thread.sleep(2); addAt("newest", 0.0)
        val m = home()
        val row = all(m.findViewById(R.id.notes)).first { it.tag == id }
        val meta = row.findViewById<TextView>(R.id.time).text.toString()
        assertTrue(meta, meta.startsWith("Session · ") && !meta.contains("  "))
        assertEquals(DwFonts.serif(m), row.findViewById<TextView>(R.id.text).typeface)
        assertEquals(17, settings.let { it[Prefs.NOTE_TEXT_SIZE] })
        assertEquals(3, settings.let { it[Prefs.PREVIEW_LINES] })
    }

    @Test fun serifTypefacesExistWithFallbacks() {
        for (t in listOf(DwFonts.serif(ctx), DwFonts.serifItalic(ctx))) assertNotNull(t)
        assertTrue(DwFonts.serifItalic(ctx) !== DwFonts.serif(ctx))
        assertTrue(DwFonts.serif(ctx) !== Typeface.DEFAULT)
    }

    @Test fun newHomeSettingsHaveTheBriefsDefaults() {
        assertEquals(5, Prefs.MORNING_FROM.def); assertEquals(12, Prefs.AFTERNOON_FROM.def)
        assertEquals(17, Prefs.EVENING_FROM.def); assertEquals(21, Prefs.NIGHT_FROM.def)
        assertEquals("Whispers", Prefs.GROUP_WORD.def); assertTrue(Prefs.SHOW_LATEST.def)
    }
}
