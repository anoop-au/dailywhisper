package com.anoop.dailywhisper

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * View and edit one note. Changes save when you leave (each save is a new version in History);
 * emptying a note moves it to Deleted (with Undo on the list). Header: Share, History, Highlight, Archive, Delete.
 */
class NoteActivity : ThemedActivity() {

    private lateinit var store: NoteStore
    private lateinit var edit: EditText
    private lateinit var highlightBtn: ImageButton
    private lateinit var archiveBtn: ImageButton
    private lateinit var statusLine: TextView
    private var note: Note? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)
        store = NoteStore.get(this)
        val n = store.get(intent.getLongExtra(EXTRA_ID, -1))
        if (n == null || n.status == NoteSchema.DELETED) { finish(); return }
        note = n
        val prefs = settings()
        edgeToEdge(findViewById(R.id.root)) { prefs[Prefs.MAX_WIDTH] }

        edit = findViewById(R.id.edit)
        edit.setText(n.text)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs[Prefs.NOTE_TEXT_SIZE] + 2f)
        val df = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT)
        findViewById<TextView>(R.id.date).text = df.format(Date(n.created))
        highlightBtn = findViewById(R.id.highlight)
        archiveBtn = findViewById(R.id.archive)
        statusLine = findViewById(R.id.status)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        // Shares what's on screen now (saved first, so the shared text is also in History).
        findViewById<View>(R.id.share).setOnClickListener { saveNow(); shareNote(edit.text.toString()) }
        findViewById<View>(R.id.history).setOnClickListener { saveNow(); showHistory() }
        highlightBtn.setOnClickListener { onHighlight() }
        archiveBtn.setOnClickListener { onArchive() }
        findViewById<View>(R.id.delete).setOnClickListener {
            if (prefs[Prefs.CONFIRM_DELETE]) {
                AlertDialog.Builder(this)
                    .setMessage("Move this note to Deleted? You can restore it from there.")
                    .setPositiveButton("Delete") { _, _ -> deleteAndClose() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else deleteAndClose()
        }
        showState()
    }

    private fun showState() {
        val n = note ?: return
        val hl = Highlight.of(n.highlight)
        highlightBtn.imageTintList = ColorStateList.valueOf(hl?.swatch ?: getColor(R.color.text2))
        highlightBtn.contentDescription = if (hl != null) "Highlight: ${hl.name}" else getString(R.string.highlight)
        val archived = n.status == NoteSchema.ARCHIVED
        archiveBtn.setImageResource(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive)
        archiveBtn.contentDescription = getString(if (archived) R.string.unarchive else R.string.archive)
        val bits = listOfNotNull(if (archived) "Archived" else null, hl?.let { "Highlighted · ${it.name}" })
        statusLine.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
        statusLine.text = bits.joinToString("   ")
        statusLine.setTextColor(hl?.swatch ?: getColor(R.color.accent))
    }

    /** Saves the editor text now (a new version if it changed). Blank text is never saved over a note. */
    private fun saveNow() {
        val n = note ?: return
        val t = edit.text.toString()
        if (t.isNotBlank() && t != n.text) { store.update(n.id, t); note = store.get(n.id) }
    }

    // ---- highlight ----
    private fun onHighlight() {
        val n = note ?: return
        if (n.highlight == null) {
            // One tap highlights with the default colour from Settings; tap again to change or remove.
            val key = settings()[Prefs.HIGHLIGHT_DEFAULT]
            store.setHighlight(n.id, key)
            note = n.copy(highlight = key); showState()
            toast("Highlighted ${Highlight.of(key)?.name ?: ""}. Tap again to change or remove")
        } else {
            pickHighlight("Highlight", n.highlight, allowNone = true) { key ->
                store.setHighlight(n.id, key); note = n.copy(highlight = key); showState()
            }
        }
    }

    // ---- archive ----
    private fun onArchive() {
        val n = note ?: return
        saveNow()
        if (n.status == NoteSchema.ARCHIVED) {
            store.unarchive(n.id); note = store.get(n.id); showState()
            toast("Moved back to your notes")
        } else {
            store.archive(n.id)
            toast("Archived. Find it under ⋮ → Archive")
            closed = true; finish()
        }
    }

    // ---- history ----
    private fun showHistory() {
        val n = note ?: return
        val versions = store.versions(n.id)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        var dialog: AlertDialog? = null
        versions.forEachIndexed { i, v ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundResource(R.drawable.bg_icon_btn)
            }
            row.addView(TextView(this).apply {
                text = versionLabel(v) + if (i == 0) "  ·  current" else ""
                textSize = 13f; setTextColor(getColor(if (i == 0) R.color.accent else R.color.text2))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            row.addView(TextView(this).apply {
                text = v.text; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, dp(2), 0, 0)
            })
            row.setOnClickListener { dialog?.dismiss(); showVersion(v, isCurrent = i == 0) }
            col.addView(row)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (versions.size == 1) "History · 1 version" else "History · ${versions.size} versions")
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showVersion(v: NoteVersion, isCurrent: Boolean) {
        val b = AlertDialog.Builder(this).setTitle(versionLabel(v)).setMessage(v.text)
            .setNeutralButton("Back to history") { _, _ -> showHistory() }
            .setNegativeButton("Close", null)
        if (!isCurrent) b.setPositiveButton("Restore this version") { _, _ ->
            val n = note ?: return@setPositiveButton
            store.update(n.id, v.text) // adds a new version; history is never rewritten
            note = store.get(n.id)
            edit.setText(v.text); edit.setSelection(edit.text.length)
            toast("Restored. The text it replaced is still in History")
        }
        b.show()
    }

    private fun versionLabel(v: NoteVersion) = dayLabel(v.created.toLocalDate()) + ", " + timeOf(v.created)

    // ---- delete ----
    private fun deleteAndClose() {
        val n = note ?: return
        saveNow()
        store.delete(n.id)
        NoteStore.recentlyDeleted = note ?: n
        closed = true
        finish()
    }

    override fun onPause() {
        super.onPause()
        val n = note ?: return
        if (closed) return
        val t = edit.text.toString()
        when {
            t.isBlank() && isFinishing -> { store.delete(n.id); NoteStore.recentlyDeleted = n; closed = true }
            t.isBlank() -> {}
            else -> saveNow()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object { const val EXTRA_ID = "id" }
}
