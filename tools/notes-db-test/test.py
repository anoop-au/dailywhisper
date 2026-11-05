"""Runs the app's NoteSchema SQL (dumped by Dump.kt) on real SQLite: the v1 -> v2 upgrade of an existing
database (no data loss, one seed version per note), a fresh install, FTS4 search + triggers, versions,
soft delete / archive / restore, highlight, and Delete forever. Mirrors NoteStore.kt's writes."""
import sqlite3, sys, time

blocks, cur = {}, None
for line in open(sys.argv[1], encoding="utf-8").read().splitlines():
    if line.startswith("@@"): cur = line[2:]; blocks[cur] = []
    elif cur: blocks[cur].append(line)
LIST_BY_STATUS, LIST_BY_STATUS_KIND, LIST_DELETED, SEARCH_FTS, SEARCH_LIKE, VERSIONS = blocks["Q"]
FTSQ = dict(l.split("\t") for l in blocks["FTSQ"])

fails = 0
def check(name, ok):
    global fails
    print(("PASS " if ok else "FAIL ") + name)
    if not ok: fails += 1

def run(db, name):
    for s in blocks[name]: db.execute(s)

# ---- NoteStore-equivalent writes ----
def add(db, text, now):
    nid = db.execute("INSERT INTO notes (text, created, updated, status) VALUES (?,?,?, 'active')", (text, now, now)).lastrowid
    db.execute("INSERT INTO note_versions (note_id, text, created) VALUES (?,?,?)", (nid, text, now)); return nid
def update(db, nid, text, now):
    cur_text = db.execute("SELECT text FROM notes WHERE id=?", (nid,)).fetchone()[0]
    if cur_text == text: return
    db.execute("UPDATE notes SET text=?, updated=? WHERE id=?", (text, now, nid))
    db.execute("INSERT INTO note_versions (note_id, text, created) VALUES (?,?,?)", (nid, text, now))
def set_status(db, nid, status, deleted_at=None):
    db.execute("UPDATE notes SET status=?, deleted_at=? WHERE id=?", (status, deleted_at, nid))
def ids(rows): return [r[0] for r in rows]
def search(db, q, include_archived=True):
    s2 = "archived" if include_archived else "active"
    fq = FTSQ.get(q)
    return ids(db.execute(SEARCH_FTS, (fq, "active", s2)).fetchall())

# ---- ftsQuery ----
check("ftsQuery: prefix per word", FTSQ["Buy mil"] == "buy* mil*")
check("ftsQuery: operators lower-cased (not operators)", FTSQ["and OR not"] == "and* or* not*")
check("ftsQuery: punctuation only -> null (LIKE fallback)", FTSQ["!!!"] == "<null>")
check("ftsQuery: quotes stripped", FTSQ['"quoted" words'] == "quoted* words*")
check("likePattern escapes % and _", blocks["LIKE"][0] == "%50\\% off\\_now%")

for tok in ("unicode61", "simple"):
    print(f"--- upgrade v1 -> v{blocks['VERSION'][0]} with FTS tokenizer {tok}")
    db = sqlite3.connect(":memory:", isolation_level=None)
    run(db, "CREATE_V1")
    old = [("Buy milk and eggs", 1000, 1000), ("Dentist at 3pm Thursday", 2000, 9000), ("Café idea: write daily", 3000, 3000)]
    for t, c, u in old: db.execute("INSERT INTO notes (text, created, updated) VALUES (?,?,?)", (t, c, u))
    db.execute("BEGIN")  # SQLiteOpenHelper runs onUpgrade in one transaction
    run(db, "UPGRADE_1_TO_2"); run(db, "UPGRADE_2_TO_3"); run(db, "FTS_" + tok)
    db.execute("COMMIT")
    rows = db.execute("SELECT id, text, created, updated, status, deleted_at, highlight, kind, duration_ms FROM notes ORDER BY id").fetchall()
    check("upgrade: all 3 notes kept with text/dates intact", [(r[1], r[2], r[3]) for r in rows] == old)
    check("upgrade: status=active, deleted_at/highlight NULL", all(r[4] == "active" and r[5] is None and r[6] is None for r in rows))
    check("upgrade: existing notes become short with no duration", all(r[7] == "short" and r[8] == 0 for r in rows))
    vers = db.execute("SELECT note_id, text, created FROM note_versions ORDER BY note_id").fetchall()
    check("upgrade: one seed version per note (text, stamped at updated)", vers == [(1, old[0][0], 1000), (2, old[1][0], 9000), (3, old[2][0], 3000)])
    check("feed lists active newest first", ids(db.execute(LIST_BY_STATUS, ("active",)).fetchall()) == [3, 2, 1])
    check("FTS rebuilt over existing notes", search(db, "milk") == [1] and search(db, "dentist 3pm") == [2])
    check("FTS prefix match", search(db, "Buy mil") == [1])
    if tok == "unicode61": check("FTS unicode61 folds accents/case (café)", search(db, "  café  ") == [3])

    # edit -> version + FTS sync
    update(db, 1, "Buy oat milk", 20000)
    update(db, 1, "Buy oat milk", 21000)  # unchanged text: no new version
    v = db.execute(VERSIONS, (1,)).fetchall()
    check("edit appends a version (no-op edit doesn't)", [r[2] for r in v] == ["Buy oat milk", "Buy milk and eggs"])
    check("FTS follows edits", search(db, "oat") == [1] and search(db, "eggs") == [])
    # restore an old version = new version, history intact
    update(db, 1, v[1][2], 30000)
    v2 = db.execute(VERSIONS, (1,)).fetchall()
    check("restore adds a version, never removes history", [r[2] for r in v2] == ["Buy milk and eggs", "Buy oat milk", "Buy milk and eggs"])
    # archive
    set_status(db, 2, "archived")
    check("archived note leaves the feed", ids(db.execute(LIST_BY_STATUS, ("active",)).fetchall()) == [3, 1])
    check("search includes archived when asked", search(db, "dentist", True) == [2] and search(db, "dentist", False) == [])
    # soft delete
    set_status(db, 3, "deleted", 40000)
    check("soft delete: row kept, out of feed and search", db.execute("SELECT COUNT(*) FROM notes").fetchone()[0] == 3
          and 3 not in ids(db.execute(LIST_BY_STATUS, ("active",)).fetchall()) and search(db, "daily") == [])
    check("Deleted view lists it", ids(db.execute(LIST_DELETED).fetchall()) == [3])
    set_status(db, 3, "active", None)
    check("restore from Deleted brings it back", search(db, "daily") == [3] and db.execute(LIST_DELETED).fetchall() == [])
    # highlight
    db.execute("UPDATE notes SET highlight=? WHERE id=?", ("amber", 1))
    check("highlight stored", db.execute("SELECT highlight FROM notes WHERE id=1").fetchone()[0] == "amber")
    # new note after upgrade
    nid = add(db, "Call mum on Sunday", 50000)
    check("new note: version + searchable", search(db, "mum") == [nid] and len(db.execute(VERSIONS, (nid,)).fetchall()) == 1)
    # delete forever (only from Deleted)
    set_status(db, nid, "deleted", 60000)
    db.execute("DELETE FROM note_versions WHERE note_id=?", (nid,)); db.execute("DELETE FROM notes WHERE id=? AND status='deleted'", (nid,))
    check("delete forever removes note, history and index entry",
          db.execute("SELECT COUNT(*) FROM notes WHERE id=?", (nid,)).fetchone()[0] == 0
          and db.execute(VERSIONS, (nid,)).fetchall() == []
          and db.execute("SELECT COUNT(*) FROM notes_fts WHERE notes_fts MATCH 'mum*'").fetchone()[0] == 0)
    check("FTS integrity-check", db.execute("INSERT INTO notes_fts(notes_fts) VALUES('integrity-check')") is not None)
    check("LIKE fallback query works", ids(db.execute(SEARCH_LIKE, ("%milk%", "active", "archived")).fetchall()) == [1])

print("--- upgrade v2 -> v3")
db = sqlite3.connect(":memory:", isolation_level=None)
run(db, "CREATE_V2")
db.execute("INSERT INTO notes (text, created, updated, status) VALUES ('kept from v2', 1, 1, 'active')")
run(db, "UPGRADE_2_TO_3")
row = db.execute("SELECT text, kind, duration_ms FROM notes").fetchone()
check("v2 to v3 keeps text and marks it short", row == ("kept from v2", "short", 0))

print("--- fresh install")
db = sqlite3.connect(":memory:", isolation_level=None)
run(db, "CREATE_V3"); run(db, "FTS_unicode61")
nid = add(db, "First note on a new phone", 1)
check("fresh: add + search + version", search(db, "phone") == [nid] and len(db.execute(VERSIONS, (nid,)).fetchall()) == 1)
check("fresh: default status active, kind short", db.execute("SELECT status, kind, duration_ms FROM notes").fetchone() == ("active", "short", 0))
check("kind filter lists short notes", ids(db.execute(LIST_BY_STATUS_KIND, ("active", "short")).fetchall()) == [nid])
check("kind filter hides them from long", ids(db.execute(LIST_BY_STATUS_KIND, ("active", "long")).fetchall()) == [])
print("--- tokenizer fallback path")
db = sqlite3.connect(":memory:", isolation_level=None); run(db, "CREATE_V3")
try:
    for s in blocks["FTS_unicode61"][:1]: db.execute(s.replace("unicode61", "nosuchtokenizer"))
    check("bad tokenizer raises (so NoteStore falls back)", False)
except sqlite3.OperationalError:
    run(db, "DROP_FTS"); run(db, "FTS_simple")
    check("bad tokenizer raises, DROP_FTS + simple works", search(db, "x") == [])

print("ALL PASSED" if fails == 0 else f"{fails} FAILED"); sys.exit(1 if fails else 0)
