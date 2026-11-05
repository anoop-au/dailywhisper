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
    val kind: String = NoteSchema.SHORT, val durationMs: Long = 0,
)

data class NoteVersion(val id: Long, val noteId: Long, val text: String, val created: Long)

/**
 * On-device SQLite (app-private storage, excluded from backup). Notes are small, so calls run on the main thread.
 * Nothing is hard-deleted except by an explicit "Delete forever" in the Deleted view. See [NoteSchema] for the SQL.
 */
class NoteStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, NoteSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        NoteSchema.CREATE_V3.forEach(db::execSQL)
        createFts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            NoteSchema.UPGRADE_1_TO_2.forEach(db::execSQL)
            createFts(db)
        }
        if (oldVersion < 3) NoteSchema.UPGRADE_2_TO_3.forEach(db::execSQL)
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
    /** Active notes of one kind (Short or Long), newest first. */
    fun active(kind: String): List<Note> = query(NoteSchema.LIST_BY_STATUS_KIND, NoteSchema.ACTIVE, kind)
    fun archived(): List<Note> = query(NoteSchema.LIST_BY_STATUS, NoteSchema.ARCHIVED)
    fun deleted(): List<Note> = query(NoteSchema.LIST_DELETED)
    fun deletedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE status = ?", arrayOf(NoteSchema.DELETED))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Full-text search over active notes (and archived ones if [includeArchived]). Never shows deleted notes.
     *  Pass [kind] to stay in Short or Long; null searches both. */
    fun search(input: String, includeArchived: Boolean, kind: String? = null): List<Note> {
        val s2 = if (includeArchived) NoteSchema.ARCHIVED else NoteSchema.ACTIVE
        val fts = NoteSchema.ftsQuery(input)
        val notes = if (hasFts && fts != null) {
            runCatching { query(NoteSchema.SEARCH_FTS, fts, NoteSchema.ACTIVE, s2) }.getOrNull()
        } else null
        val found = notes ?: if (input.isBlank()) active() else query(NoteSchema.SEARCH_LIKE, NoteSchema.likePattern(input), NoteSchema.ACTIVE, s2)
        return if (kind == null) found else found.filter { it.kind == kind }
    }

    fun get(id: Long): Note? =
        readableDatabase.query("notes", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.toNote() else null }

    fun versions(noteId: Long): List<NoteVersion> =
        readableDatabase.rawQuery(NoteSchema.VERSIONS, arrayOf(noteId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(NoteVersion(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))) }
        }

    // ---- writing ----

    fun add(text: String, kind: String = NoteSchema.SHORT): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("notes", null, ContentValues().apply {
                put("text", text); put("created", now); put("updated", now); put("status", NoteSchema.ACTIVE)
                put("kind", if (kind == NoteSchema.LONG) NoteSchema.LONG else NoteSchema.SHORT)
                put("duration_ms", 0)
            })
            addVersion(db, id, text, now)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    /**
     * Writes [text] (and optional [durationMs]) without adding a version. Used to autosave a long session
     * while talking so nothing is lost if the app closes; a version is added later by [update] on pause or stop.
     */
    fun saveDraft(id: Long, text: String, durationMs: Long? = null) {
        val current = get(id) ?: return
        if (current.text == text && (durationMs == null || current.durationMs == durationMs)) return
        writableDatabase.update("notes", ContentValues().apply {
            put("text", text); put("updated", System.currentTimeMillis())
            if (durationMs != null) put("duration_ms", durationMs)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun setDuration(id: Long, durationMs: Long) {
        writableDatabase.update("notes", ContentValues().apply { put("duration_ms", durationMs) },
            "id = ?", arrayOf(id.toString()))
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

    /** Multi-select delete: the same soft delete for each note, in one transaction. Returns them as they were (for Undo). */
    fun deleteAll(ids: Collection<Long>): List<Note> {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val notes = ids.mapNotNull { id -> get(id)?.also { setStatus(id, NoteSchema.DELETED, now) } }
            db.setTransactionSuccessful()
            return notes
        } finally { db.endTransaction() }
    }

    /** Undo for [deleteAll]: each note back to the status it had, in one transaction. */
    fun restoreAll(notes: Collection<Note>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            notes.forEach { restore(it.id, it.status) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        val kindCol = getColumnIndex("kind")
        val durCol = getColumnIndex("duration_ms")
        return Note(
            getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("text")),
            getLong(getColumnIndexOrThrow("created")), getLong(getColumnIndexOrThrow("updated")),
            getString(getColumnIndexOrThrow("status")), if (isNull(del)) null else getLong(del), if (isNull(hl)) null else getString(hl),
            if (kindCol >= 0) getString(kindCol) ?: NoteSchema.SHORT else NoteSchema.SHORT,
            if (durCol >= 0 && !isNull(durCol)) getLong(durCol) else 0,
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
