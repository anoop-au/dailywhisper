package com.anoop.dailywhisper

/**
 * All note SQL in one place, pure Kotlin (no Android imports) so tools/notes-db-test runs exactly these
 * statements against real SQLite on the box.
 *
 * v1 (0.1–0.1.6): notes(id, text, created, updated)
 * v2: + notes.status ('active' | 'archived' | 'deleted'), notes.deleted_at, notes.highlight (palette key or NULL)
 *     + note_versions(id, note_id, text, created): every create/edit appends a version; history is never rewritten
 *     + notes_fts: FTS4 index over notes.text (external content, kept in sync by triggers)
 * v3: + notes.kind ('short' | 'long'), notes.duration_ms (spoken time for long sessions). Existing notes become short.
 */
object NoteSchema {
    const val VERSION = 3

    const val ACTIVE = "active"
    const val ARCHIVED = "archived"
    const val DELETED = "deleted"
    const val SHORT = "short"
    const val LONG = "long"

    val CREATE_V1 = listOf(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)",
        "CREATE INDEX notes_created ON notes(created DESC)",
    )

    private val VERSIONS_TABLE = listOf(
        "CREATE TABLE note_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)",
        "CREATE INDEX note_versions_note ON note_versions(note_id, created DESC)",
    )

    /** Schema as of v2 (used to test the v2 → v3 upgrade). */
    val CREATE_V2: List<String> = listOf(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
            "status TEXT NOT NULL DEFAULT '$ACTIVE', deleted_at INTEGER, highlight TEXT)",
        "CREATE INDEX notes_created ON notes(created DESC)",
        "CREATE INDEX notes_status ON notes(status, created DESC)",
    ) + VERSIONS_TABLE

    /** Fresh install. */
    val CREATE_V3: List<String> = listOf(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
            "status TEXT NOT NULL DEFAULT '$ACTIVE', deleted_at INTEGER, highlight TEXT, " +
            "kind TEXT NOT NULL DEFAULT '$SHORT', duration_ms INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX notes_created ON notes(created DESC)",
        "CREATE INDEX notes_status ON notes(status, created DESC)",
        "CREATE INDEX notes_kind ON notes(kind, status, created DESC)",
    ) + VERSIONS_TABLE

    /** Upgrade an existing v1 database in place. No row is dropped; each note gets one seed version. */
    val UPGRADE_1_TO_2: List<String> = listOf(
        "ALTER TABLE notes ADD COLUMN status TEXT NOT NULL DEFAULT '$ACTIVE'",
        "ALTER TABLE notes ADD COLUMN deleted_at INTEGER",
        "ALTER TABLE notes ADD COLUMN highlight TEXT",
        "CREATE INDEX notes_status ON notes(status, created DESC)",
    ) + VERSIONS_TABLE + listOf(
        // Seed: the current text is the first known version, stamped with the note's last-edited time.
        "INSERT INTO note_versions (note_id, text, created) SELECT id, text, updated FROM notes",
    )

    /** Upgrade a v2 database in place. No row is dropped; every existing note becomes a Short note. */
    val UPGRADE_2_TO_3: List<String> = listOf(
        "ALTER TABLE notes ADD COLUMN kind TEXT NOT NULL DEFAULT '$SHORT'",
        "ALTER TABLE notes ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX notes_kind ON notes(kind, status, created DESC)",
    )

    /** FTS4 search index + sync triggers. [tokenizer] "unicode61" (preferred) or "simple" (fallback). */
    fun ftsObjects(tokenizer: String): List<String> = listOf(
        "CREATE VIRTUAL TABLE notes_fts USING fts4(content=\"notes\", text, tokenize=$tokenizer)",
        "CREATE TRIGGER notes_fts_bu BEFORE UPDATE OF text ON notes BEGIN DELETE FROM notes_fts WHERE docid = old.id; END",
        "CREATE TRIGGER notes_fts_bd BEFORE DELETE ON notes BEGIN DELETE FROM notes_fts WHERE docid = old.id; END",
        "CREATE TRIGGER notes_fts_au AFTER UPDATE OF text ON notes BEGIN INSERT INTO notes_fts (docid, text) VALUES (new.id, new.text); END",
        "CREATE TRIGGER notes_fts_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts (docid, text) VALUES (new.id, new.text); END",
        "INSERT INTO notes_fts (notes_fts) VALUES ('rebuild')",
    )

    val DROP_FTS = listOf(
        "DROP TRIGGER IF EXISTS notes_fts_bu", "DROP TRIGGER IF EXISTS notes_fts_bd",
        "DROP TRIGGER IF EXISTS notes_fts_au", "DROP TRIGGER IF EXISTS notes_fts_ai",
        "DROP TABLE IF EXISTS notes_fts",
    )

    // ---- queries (parameters are bound with ?) ----

    /** Notes with one status, newest first. Args: status. */
    const val LIST_BY_STATUS = "SELECT * FROM notes WHERE status = ? ORDER BY created DESC"

    /** Active/archived/deleted notes of one kind, newest first. Args: status, kind. */
    const val LIST_BY_STATUS_KIND = "SELECT * FROM notes WHERE status = ? AND kind = ? ORDER BY created DESC"

    /** Deleted notes, most recently deleted first. */
    const val LIST_DELETED = "SELECT * FROM notes WHERE status = '$DELETED' ORDER BY deleted_at DESC, id DESC"

    /** FTS search. Args: fts query, status1, status2 (pass the same status twice to search one). */
    const val SEARCH_FTS = "SELECT * FROM notes WHERE id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?) " +
        "AND status IN (?, ?) ORDER BY created DESC"

    /** Fallback substring search. Args: %pattern%, status1, status2. */
    const val SEARCH_LIKE = "SELECT * FROM notes WHERE text LIKE ? ESCAPE '\\' AND status IN (?, ?) ORDER BY created DESC"

    /** Versions of one note, newest first. Args: note id. */
    const val VERSIONS = "SELECT id, note_id, text, created FROM note_versions WHERE note_id = ? ORDER BY created DESC, id DESC"

    /**
     * User words -> FTS4 query: every word must match, each as a prefix ("buy mil" finds "buy milk").
     * Words are lower-cased so AND/OR/NOT/NEAR are never read as operators. Null if there are no words.
     */
    fun ftsQuery(input: String): String? {
        val words = input.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }

    fun likePattern(input: String): String =
        "%" + input.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
