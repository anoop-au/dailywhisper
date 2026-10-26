package com.anoop.dailywhisper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

data class Note(
    val id: Long, val text: String, val created: Long, val updated: Long,
    val status: String = NoteSchema.ACTIVE, val deletedAt: Long? = null, val highlight: String? = null,
)

data class NoteVersion(val id: Long, val noteId: Long, val text: String, val created: Long)

/**
 * On-device SQLite (app-private storage, excluded from backup). Notes are small, so calls run on the main thread.
 * Nothing is hard-deleted except by an explicit "Delete forever" in the Deleted view. See [NoteSchema] for the SQL.
 */
class NoteStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, NoteSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        NoteSchema.CREATE_V2.forEach(db::execSQL)
        createFts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            NoteSchema.UPGRADE_1_TO_2.forEach(db::execSQL)
            createFts(db)
        }
    }

    /** FTS4 with the unicode61 tokenizer if this SQLite has it, else the simple one; if FTS is missing, search uses LIKE. */
    private fun createFts(db: SQLiteDatabase) {
        for (tok in listOf("unicode61", "simple")) {
            try {
                NoteSchema.ftsObjects(tok).forEach(db::execSQL)
                return
            } catch (e: SQLiteException) {
                NoteSchema.DROP_FTS.forEach { runCatching { db.execSQL(it) } }
            }
        }
    }

    private val hasFts: Boolean by lazy {
        readableDatabase.rawQuery("SELECT 1 FROM sqlite_master WHERE name = 'notes_fts'", null).use { it.moveToFirst() }
    }

    // ---- reading ----

    /** The daily feed: active notes, newest first. */
    fun active(): List<Note> = query(NoteSchema.LIST_BY_STATUS, NoteSchema.ACTIVE)
    fun archived(): List<Note> = query(NoteSchema.LIST_BY_STATUS, NoteSchema.ARCHIVED)
    fun deleted(): List<Note> = query(NoteSchema.LIST_DELETED)
    fun deletedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE status = ?", arrayOf(NoteSchema.DELETED))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Full-text search over active notes (and archived ones if [includeArchived]). Never shows deleted notes. */
    fun search(input: String, includeArchived: Boolean): List<Note> {
        val s2 = if (includeArchived) NoteSchema.ARCHIVED else NoteSchema.ACTIVE
        val fts = NoteSchema.ftsQuery(input)
        if (hasFts && fts != null) {
            runCatching { return query(NoteSchema.SEARCH_FTS, fts, NoteSchema.ACTIVE, s2) }
        }
        if (input.isBlank()) return active()
        return query(NoteSchema.SEARCH_LIKE, NoteSchema.likePattern(input), NoteSchema.ACTIVE, s2)
    }

    fun get(id: Long): Note? =
        readableDatabase.query("notes", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.toNote() else null }

    fun versions(noteId: Long): List<NoteVersion> =
        readableDatabase.rawQuery(NoteSchema.VERSIONS, arrayOf(noteId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(NoteVersion(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))) }
        }

    // ---- writing ----

    fun add(text: String): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("notes", null, ContentValues().apply {
                put("text", text); put("created", now); put("updated", now); put("status", NoteSchema.ACTIVE)
            })
            addVersion(db, id, text, now)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    /** Saves new text and appends it as a version. No-op if the text didn't change. Also used by Restore. */
    fun update(id: Long, text: String) {
        val db = writableDatabase
        val current = get(id) ?: return
        if (current.text == text) return
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update("notes", ContentValues().apply { put("text", text); put("updated", now) }, "id = ?", arrayOf(id.toString()))
            addVersion(db, id, text, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun addVersion(db: SQLiteDatabase, noteId: Long, text: String, at: Long) {
        db.insert("note_versions", null, ContentValues().apply { put("note_id", noteId); put("text", text); put("created", at) })
    }

    /** Soft delete: moves the note to Deleted. Returns it as it was (for Undo). */
    fun delete(id: Long): Note? {
        val n = get(id) ?: return null
        setStatus(id, NoteSchema.DELETED, System.currentTimeMillis())
        return n
    }

    /** Undo / Restore: back to [toStatus] (active unless Undo knows it was archived). */
    fun restore(id: Long, toStatus: String = NoteSchema.ACTIVE) = setStatus(id, toStatus, null)

    fun archive(id: Long) = setStatus(id, NoteSchema.ARCHIVED, null)
    fun unarchive(id: Long) = setStatus(id, NoteSchema.ACTIVE, null)

    private fun setStatus(id: Long, status: String, deletedAt: Long?) {
        writableDatabase.update("notes", ContentValues().apply {
            put("status", status)
            if (deletedAt != null) put("deleted_at", deletedAt) else putNull("deleted_at")
        }, "id = ?", arrayOf(id.toString()))
    }

    /** Palette key from [Highlight], or null to clear. */
    fun setHighlight(id: Long, key: String?) {
        writableDatabase.update("notes", ContentValues().apply { if (key != null) put("highlight", key) else putNull("highlight") },
            "id = ?", arrayOf(id.toString()))
    }

    /** The only hard delete: "Delete forever" from the Deleted view, after a confirm. Removes its history too. */
    fun deleteForever(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("note_versions", "note_id = ?", arrayOf(id.toString()))
            db.delete("notes", "id = ? AND status = ?", arrayOf(id.toString(), NoteSchema.DELETED))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun query(sql: String, vararg args: String): List<Note> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toNote()) } }

    private fun Cursor.toNote(): Note {
        val del = getColumnIndexOrThrow("deleted_at")
        val hl = getColumnIndexOrThrow("highlight")
        return Note(
            getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("text")),
            getLong(getColumnIndexOrThrow("created")), getLong(getColumnIndexOrThrow("updated")),
            getString(getColumnIndexOrThrow("status")), if (isNull(del)) null else getLong(del), if (isNull(hl)) null else getString(hl),
        )
    }

    companion object {
        @Volatile private var instance: NoteStore? = null
        fun get(ctx: Context): NoteStore =
            instance ?: synchronized(this) { instance ?: NoteStore(ctx.applicationContext).also { instance = it } }

        /** Set by the note screen when it deletes, so the list can offer Undo. */
        @Volatile var recentlyDeleted: Note? = null
    }
}
