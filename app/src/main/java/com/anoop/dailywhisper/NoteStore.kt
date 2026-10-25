package com.anoop.dailywhisper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Note(val id: Long, val text: String, val created: Long, val updated: Long)

/** On-device SQLite (app-private storage, excluded from backup). Notes are small, so calls run on the main thread. */
class NoteStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX notes_created ON notes(created DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Newest first; optional case-insensitive substring search. */
    fun list(query: String = ""): List<Note> {
        val q = query.trim()
        val c = if (q.isEmpty()) {
            readableDatabase.query("notes", null, null, null, null, null, "created DESC")
        } else {
            val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            readableDatabase.query("notes", null, "text LIKE ? ESCAPE '\\'", arrayOf("%$esc%"), null, null, "created DESC")
        }
        return c.use { cur -> buildList { while (cur.moveToNext()) add(cur.toNote()) } }
    }

    fun get(id: Long): Note? =
        readableDatabase.query("notes", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.toNote() else null }

    fun add(text: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("notes", null, ContentValues().apply {
            put("text", text); put("created", now); put("updated", now)
        })
    }

    fun update(id: Long, text: String) {
        writableDatabase.update("notes", ContentValues().apply {
            put("text", text); put("updated", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    /** Undo: put a deleted note back exactly as it was (same id and dates). */
    fun restore(n: Note) {
        writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", n.id); put("text", n.text); put("created", n.created); put("updated", n.updated)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toNote() = Note(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("text")),
        getLong(getColumnIndexOrThrow("created")), getLong(getColumnIndexOrThrow("updated")),
    )

    companion object {
        @Volatile private var instance: NoteStore? = null
        fun get(ctx: Context): NoteStore =
            instance ?: synchronized(this) { instance ?: NoteStore(ctx.applicationContext).also { instance = it } }

        /** Set by the note screen when it deletes, so the list can offer Undo. */
        @Volatile var recentlyDeleted: Note? = null
    }
}
