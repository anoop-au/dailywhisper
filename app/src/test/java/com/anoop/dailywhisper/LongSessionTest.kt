package com.anoop.dailywhisper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LongSessionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: NoteStore

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear()
            .putBoolean(SetupActivity.KEY_DONE, true)
            .putBoolean(WritingMode.KEY_CHOSEN, true)
            .commit()
        store = NoteStore.get(ctx)
    }

    @After fun reset() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun newNotesDefaultToShortAndStayOutOfSessions() {
        val id = store.add("a quick thought")
        assertEquals(NoteSchema.SHORT, store.get(id)!!.kind)
        assertEquals(listOf(id), store.active(NoteSchema.SHORT).map { it.id })
        assertTrue(store.active(NoteSchema.LONG).isEmpty())
    }

    @Test fun longNotesListSeparatelyAndSearchStaysInKind() {
        val shortId = store.add("buy milk at the market")
        val longId = store.add("Walked through the market this morning thinking of Amma", NoteSchema.LONG)
        assertEquals(listOf(shortId), store.active(NoteSchema.SHORT).map { it.id })
        assertEquals(listOf(longId), store.active(NoteSchema.LONG).map { it.id })
        assertEquals(listOf(shortId), store.search("milk", includeArchived = false, kind = NoteSchema.SHORT).map { it.id })
        assertEquals(listOf(longId), store.search("walked", includeArchived = false, kind = NoteSchema.LONG).map { it.id })
        assertTrue(store.search("walked", includeArchived = false, kind = NoteSchema.SHORT).isEmpty())
        assertEquals(setOf(shortId, longId), store.search("market", includeArchived = false).map { it.id }.toSet())
    }

    @Test fun saveDraftKeepsHistoryUntilACheckpoint() {
        val id = store.add("hello", NoteSchema.LONG)
        store.saveDraft(id, "hello there", 5000)
        val draft = store.get(id)!!
        assertEquals("hello there", draft.text)
        assertEquals(5000L, draft.durationMs)
        assertEquals(1, store.versions(id).size)
        store.update(id, "hello there, Amma")
        store.setDuration(id, 8000)
        assertEquals(2, store.versions(id).size)
        assertEquals(8000L, store.get(id)!!.durationMs)
    }

    @Test fun existingV2NotesBecomeShortOnUpgrade() {
        store.close()
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        val file = ctx.getDatabasePath("notes.db")
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        NoteSchema.CREATE_V2.forEach(db::execSQL)
        NoteSchema.ftsObjects("simple").forEach { runCatching { db.execSQL(it) } }
        db.execSQL("INSERT INTO notes (text, created, updated, status) VALUES ('old note from before long sessions', 1000, 1000, 'active')")
        db.execSQL("PRAGMA user_version = 2")
        db.close()
        val upgraded = NoteStore.get(ctx)
        val notes = upgraded.active()
        assertEquals(1, notes.size)
        assertEquals("old note from before long sessions", notes[0].text)
        assertEquals(NoteSchema.SHORT, notes[0].kind)
        assertEquals(0L, notes[0].durationMs)
        assertTrue(upgraded.active(NoteSchema.LONG).isEmpty())
        assertEquals(notes[0].id, upgraded.search("old note", includeArchived = false, kind = NoteSchema.SHORT).single().id)
    }

    @Test fun fabOpensALongRecordingAndSessionsListShowsIt() {
        WritingMode.set(ctx, WritingMode.LONG)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.findViewById<View>(R.id.fab).performClick()
        val next = shadowOf(main).nextStartedActivity
        assertEquals(NoteActivity::class.java.name, next.component!!.className)
        assertTrue(next.getBooleanExtra(NoteActivity.EXTRA_RECORD, false))
        val id = next.getLongExtra(NoteActivity.EXTRA_ID, -1)
        assertEquals(NoteSchema.LONG, store.get(id)!!.kind)
        assertEquals(listOf(id), store.active(NoteSchema.LONG).map { it.id })
    }

    @Test fun wordCountAndDurationHelpers() {
        assertEquals(0, wordCount("   "))
        assertEquals(3, wordCount("one two three"))
        assertEquals("0:05", formatDuration(5000))
        assertEquals("18 min", formatDuration(18 * 60 * 1000L))
        assertEquals("1:05", formatDuration(65 * 1000L))
    }

    @Test fun longAutosavePrefIsOnTheNotesPage() {
        assertTrue(Prefs.sections.any { it.first == "Notes" && Prefs.LONG_AUTOSAVE_SECONDS in it.second })
        assertEquals(4, Prefs.LONG_AUTOSAVE_SECONDS.def)
    }
}
