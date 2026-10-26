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
 * One note. Opens to read: a tap on the text translates it (ML Kit, on the phone, to the language in Settings → Translation)
 * into a panel below, keeping the original; the pencil (or a long-press on the text) switches to editing.
 * Changes save when you leave editing or the screen (each save is a new version in History);
 * emptying a note moves it to Deleted (with Undo on the list). Header: Edit, Share, History, Highlight, Archive, Delete.
 */
class NoteActivity : ThemedActivity() {

    private lateinit var store: NoteStore
    private lateinit var edit: EditText
    private lateinit var highlightBtn: ImageButton
    private lateinit var archiveBtn: ImageButton
    private lateinit var statusLine: TextView
    private lateinit var editBtn: ImageButton
    private lateinit var readHint: TextView
    private var note: Note? = null
    private var closed = false
    private var editing = false
    private var touchX = 0f
    private var touchY = 0f

    // Translation panel
    private lateinit var trPanel: View
    private lateinit var trTitle: TextView
    private lateinit var trNote: TextView
    private lateinit var trText: TextView
    private lateinit var trProgress: View
    private lateinit var trActions: View
    private lateinit var trReplace: android.widget.Button
    private var trJob: NoteTranslator.Job? = null
    private var trResult: String? = null
    private var trFor: String? = null       // the note text + target the result belongs to
    private var undoText: String? = null    // original text after Replace, until Undo or close

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

        editBtn = findViewById(R.id.edit_toggle)
        readHint = findViewById(R.id.read_hint)
        editBtn.setOnClickListener { setEditing(!editing, atOffset = -1) }
        // Read mode: tap = translate, long-press = edit at that spot. In edit mode the EditText behaves as usual.
        @Suppress("ClickableViewAccessibility")
        edit.setOnTouchListener { _, e -> touchX = e.x; touchY = e.y; false }
        edit.setOnClickListener { if (!editing) translate() }
        edit.setOnLongClickListener {
            if (editing) return@setOnLongClickListener false
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            setEditing(true, atOffset = edit.getOffsetForPosition(touchX, touchY)); true
        }
        setupPanel()
        setEditing(false, atOffset = -1)

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

    // ---- read / edit ----
    private fun setEditing(on: Boolean, atOffset: Int) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (!on && editing) saveNow()
        editing = on
        edit.isFocusable = on
        edit.isFocusableInTouchMode = on
        edit.isCursorVisible = on
        editBtn.setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_edit)
        editBtn.contentDescription = getString(if (on) R.string.done_editing else R.string.edit)
        readHint.visibility = if (!on && uiState().getInt(KEY_TRANSLATIONS, 0) < 3) View.VISIBLE else View.GONE
        if (on) {
            edit.requestFocus()
            edit.setSelection(if (atOffset in 0..edit.text.length) atOffset else edit.text.length)
            edit.post { imm.showSoftInput(edit, 0) }
        } else {
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
            edit.clearFocus()
        }
    }

    // ---- translate ----
    private fun setupPanel() {
        trPanel = findViewById(R.id.tr_panel)
        trTitle = findViewById(R.id.tr_title)
        trNote = findViewById(R.id.tr_note)
        trText = findViewById(R.id.tr_text)
        trProgress = findViewById(R.id.tr_progress)
        trActions = findViewById(R.id.tr_actions)
        trReplace = findViewById(R.id.tr_replace)
        trText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings()[Prefs.NOTE_TEXT_SIZE] + 1f)
        findViewById<View>(R.id.tr_close).setOnClickListener { closePanel() }
        findViewById<View>(R.id.tr_copy).setOnClickListener {
            val t = trResult ?: return@setOnClickListener
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Translation", t))
            toast("Copied")
        }
        findViewById<View>(R.id.tr_keep).setOnClickListener {
            val t = trResult ?: return@setOnClickListener
            store.add(t)
            toast("Saved as a new note. This one stays as it was")
            closePanel()
        }
        trReplace.setOnClickListener { if (undoText != null) undoReplace() else replaceWithTranslation() }
        // Keep the panel to about 40% of the screen so the original stays in view.
        findViewById<MaxHeightScrollView>(R.id.tr_scroll).maxHeightPx = resources.displayMetrics.heightPixels * 2 / 5
    }

    private fun translate() {
        val text = edit.text.toString()
        if (text.isBlank()) return
        val p = settings()
        val source = p[Prefs.TR_SOURCE]
        val target = p[Prefs.TR_TARGET]
        val key = target + "\u0000" + text
        if (trJob != null && trFor == key) return              // already working on it
        if (trResult != null && trFor == key && undoText == null) { trPanel.visibility = View.VISIBLE; return }
        trJob?.cancel(); trJob = null
        undoText = null; trResult = null; trFor = key
        val translator = NoteTranslator(this)
        trPanel.visibility = View.VISIBLE
        trTitle.text = "${TranslateConfig.languageName(source)} → ${TranslateConfig.languageName(target)}"
        trNote.visibility = View.GONE
        trReplace.setText(R.string.tr_replace)
        if (!translator.supports(source, target)) {
            trProgress.visibility = View.GONE; trActions.visibility = View.GONE; trText.text = ""; trFor = null
            trText.hint = "Your notes are already in ${TranslateConfig.languageName(target)}. Pick another language in Settings → Translation."
            return
        }
        trText.text = ""
        trText.hint = "Translating…"
        trProgress.visibility = View.VISIBLE
        trActions.visibility = View.GONE
        uiState().edit().putInt(KEY_TRANSLATIONS, uiState().getInt(KEY_TRANSLATIONS, 0) + 1).apply()
        trJob = translator.translate(text, source, target, object : NoteTranslator.Callbacks {
            override fun onStatus(message: String) { trText.hint = message }
            override fun onDone(translation: String) {
                trJob = null; trResult = translation
                trText.text = translation; trProgress.visibility = View.GONE; trActions.visibility = View.VISIBLE
            }
            override fun onError(message: String) {
                trJob = null; trFor = null
                trText.text = ""; trText.hint = message; trProgress.visibility = View.GONE; trActions.visibility = View.GONE
            }
        })
    }

    /** Replace keeps everything: the original stays in History, and Undo here puts it straight back. */
    private fun replaceWithTranslation() {
        val n = note ?: return
        val t = trResult ?: return
        saveNow()
        undoText = edit.text.toString()
        store.update(n.id, t); note = store.get(n.id)
        edit.setText(t)
        trReplace.setText(R.string.undo)
        trNote.text = "Replaced. The original is kept in History too."
        trNote.visibility = View.VISIBLE
    }

    private fun undoReplace() {
        val n = note ?: return
        val orig = undoText ?: return
        store.update(n.id, orig); note = store.get(n.id)
        edit.setText(orig)
        undoText = null
        trReplace.setText(R.string.tr_replace)
        trNote.text = "Back to the original."
    }

    private fun closePanel() {
        trJob?.cancel(); trJob = null
        trPanel.visibility = View.GONE
        trResult = null; trFor = null; undoText = null
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
        if (isFinishing) { trJob?.cancel(); trJob = null }
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

    companion object {
        const val EXTRA_ID = "id"
        private const val KEY_TRANSLATIONS = "translations_done" // the read-mode hint hides after a few uses
    }
}
