package com.anoop.dailywhisper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var prefs: SharedPreferences
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var composer: ComposerEditText
    private lateinit var send: ImageButton
    private lateinit var searchBtn: ImageButton
    private lateinit var search: EditText
    private lateinit var undoBar: View
    private lateinit var reapplyInsets: () -> Unit

    private val adapter = NotesAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var query = ""
    private var wantKeyboard = false
    private var enterSaves: Boolean? = null
    private var pendingUndo: Note? = null

    private val autoSave = Runnable { if (composer.text.isNotBlank()) saveComposer() }
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; pendingUndo = null }
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = NoteStore.get(this)
        prefs = settings()

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        composer = findViewById(R.id.composer)
        send = findViewById(R.id.send)
        searchBtn = findViewById(R.id.btn_search)
        search = findViewById(R.id.search)
        undoBar = findViewById(R.id.undo_bar)
        reapplyInsets = edgeToEdge(findViewById(R.id.root)) { prefs[Prefs.MAX_WIDTH] }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            (adapter.getItem(pos) as? Row.Item)?.let {
                startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, it.note.id))
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val row = adapter.getItem(pos) as? Row.Item ?: return@setOnItemLongClickListener false
            list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            requestDelete(row.note)
            true
        }

        // Composer
        send.setOnClickListener { saveComposer() }
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSend()
                handler.removeCallbacks(autoSave)
                if (prefs[Prefs.AUTO_SAVE] && !s.isNullOrBlank()) handler.postDelayed(autoSave, prefs[Prefs.AUTO_SAVE_SECONDS] * 1000L)
            }
        })
        composer.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { saveComposer(); true } else false
        }
        composer.setTextInternal(uiState().getString(KEY_DRAFT, "").orEmpty())

        // Header
        findViewById<TextView>(R.id.subtitle).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        findViewById<View>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        searchBtn.setOnClickListener { showSearch(search.visibility != View.VISIBLE) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refresh() }
        })
        findViewById<View>(R.id.undo_btn).setOnClickListener { undoDelete() }
        updateSend()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        refresh()
        NoteStore.recentlyDeleted?.let { NoteStore.recentlyDeleted = null; showUndo(it) }
        if (prefs[Prefs.AUTO_FOCUS] && search.visibility != View.VISIBLE) {
            composer.requestFocus()
            composer.setSelection(composer.text.length)
            wantKeyboard = prefs[Prefs.SHOW_KEYBOARD]
            if (hasWindowFocus()) showKeyboardNow()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showKeyboardNow()
    }

    private fun showKeyboardNow() {
        if (!wantKeyboard) return
        wantKeyboard = false
        composer.post {
            if (composer.hasFocus()) getSystemService(InputMethodManager::class.java).showSoftInput(composer, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        // Draft survives the app being closed or killed, so dictated text is never lost.
        uiState().edit().putString(KEY_DRAFT, composer.text.toString()).apply()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applySettings() {
        composer.appendOnReplace = prefs[Prefs.APPEND_DICTATION]
        composer.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs[Prefs.COMPOSER_TEXT_SIZE].toFloat())
        val es = prefs[Prefs.ENTER_SAVES]
        if (es != enterSaves) {
            enterSaves = es
            val base = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            if (es) {
                // Visually multi-line, but the keyboard shows a Send key.
                composer.inputType = base
                composer.imeOptions = EditorInfo.IME_ACTION_SEND
                composer.setHorizontallyScrolling(false)
            } else {
                composer.inputType = base or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                composer.imeOptions = EditorInfo.IME_ACTION_NONE
            }
        }
        composer.minLines = 1
        composer.maxLines = prefs[Prefs.COMPOSER_MAX_LINES]
        adapter.textSize = prefs[Prefs.NOTE_TEXT_SIZE].toFloat()
        adapter.previewLines = prefs[Prefs.PREVIEW_LINES]
        reapplyInsets()
    }

    private fun updateSend() {
        val has = composer.text.isNotBlank()
        send.isEnabled = has
        send.animate().alpha(if (has) 1f else 0.35f).setDuration(120).start()
    }

    private fun saveComposer() {
        handler.removeCallbacks(autoSave)
        val t = composer.text.toString().trim()
        if (t.isEmpty()) return
        store.add(t)
        composer.setTextInternal("")
        uiState().edit().putString(KEY_DRAFT, "").apply()
        composer.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (search.visibility == View.VISIBLE) showSearch(false) else refresh()
        list.setSelection(0)
    }

    // ---- delete + undo ----
    private fun requestDelete(n: Note) {
        if (prefs[Prefs.CONFIRM_DELETE]) {
            AlertDialog.Builder(this)
                .setMessage("Delete this note?")
                .setPositiveButton("Delete") { _, _ -> delete(n) }
                .setNegativeButton("Cancel", null)
                .show()
        } else delete(n)
    }

    private fun delete(n: Note) { store.delete(n.id); refresh(); showUndo(n) }

    private fun showUndo(n: Note) {
        pendingUndo = n
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, prefs[Prefs.UNDO_SECONDS] * 1000L)
    }

    private fun undoDelete() {
        pendingUndo?.let { store.restore(it) }
        handler.removeCallbacks(hideUndo)
        hideUndo.run()
        refresh()
    }

    // ---- search ----
    private fun showSearch(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            search.visibility = View.VISIBLE
            searchBtn.setImageResource(R.drawable.ic_close)
            search.requestFocus()
            imm.showSoftInput(search, 0)
            registerBack()
        } else {
            search.setText("")
            search.visibility = View.GONE
            searchBtn.setImageResource(R.drawable.ic_search)
            composer.requestFocus()
            unregisterBack()
        }
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = OnBackInvokedCallback { showSearch(false) }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT < 33) return
        (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        if (search.visibility == View.VISIBLE) showSearch(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- list ----
    private fun refresh() {
        val notes = store.list(query)
        adapter.rows = buildRows(notes)
        adapter.notifyDataSetChanged()
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (query.isBlank()) R.string.empty else R.string.empty_search)
    }

    private fun buildRows(notes: List<Note>): List<Row> {
        val rows = ArrayList<Row>(notes.size + 8)
        var lastDay: LocalDate? = null
        for (n in notes) {
            val day = n.created.toLocalDate()
            if (day != lastDay) { rows.add(Row.Header(dayLabel(day))); lastDay = day }
            rows.add(Row.Item(n))
        }
        return rows
    }

    private fun dayLabel(day: LocalDate): String {
        val today = LocalDate.now()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
            else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
        }
    }

    private fun timeLabel(n: Note): String {
        val tf = DateFormat.getTimeFormat(this)
        val t = tf.format(Date(n.created))
        return if (n.updated - n.created > 60_000) "$t  ·  edited" else t
    }

    sealed class Row {
        class Header(val label: String) : Row()
        class Item(val note: Note) : Row()
    }

    inner class NotesAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        var textSize = 16f
        var previewLines = 5

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = (rows[position] as? Row.Item)?.note?.id ?: (-1L - position)
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Row.Header -> ((convertView ?: layoutInflater.inflate(R.layout.item_header, parent, false)) as TextView)
                .also { it.text = row.label }
            is Row.Item -> (convertView ?: layoutInflater.inflate(R.layout.item_note, parent, false)).also { v ->
                v.findViewById<TextView>(R.id.text).apply {
                    text = row.note.text
                    maxLines = previewLines
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                }
                v.findViewById<TextView>(R.id.time).text = timeLabel(row.note)
            }
        }
    }

    companion object {
        private const val KEY_DRAFT = "draft"
        fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
