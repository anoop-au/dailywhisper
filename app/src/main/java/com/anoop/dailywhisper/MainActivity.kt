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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var scroll: ScrollView
    private lateinit var notesBox: LinearLayout
    private lateinit var empty: TextView
    private lateinit var composer: ComposerEditText
    private lateinit var send: ImageButton
    private lateinit var mic: ImageButton
    private lateinit var liveDictation: LiveDictation
    private lateinit var searchBtn: ImageButton
    private lateinit var search: EditText
    private lateinit var undoBar: View
    private lateinit var reapplyInsets: () -> Unit

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

        scroll = findViewById(R.id.scroll)
        notesBox = findViewById(R.id.notes)
        empty = findViewById(R.id.empty)
        composer = findViewById(R.id.composer)
        send = findViewById(R.id.send)
        mic = findViewById(R.id.mic)
        searchBtn = findViewById(R.id.btn_search)
        search = findViewById(R.id.search)
        undoBar = findViewById(R.id.undo_bar)
        reapplyInsets = edgeToEdge(findViewById(R.id.root)) { prefs[Prefs.MAX_WIDTH] }


        // Composer
        send.setOnClickListener { saveComposer() }
        liveDictation = ComingSoonLiveDictation(this)
        mic.setOnClickListener { if (liveDictation.isListening) liveDictation.stop() else liveDictation.start() }
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
        liveDictation.stop()
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
        mic.visibility = if (prefs[Prefs.SHOW_MIC]) View.VISIBLE else View.GONE
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
        scroll.post { scroll.scrollTo(0, 0) }
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
    // Phase 1 keeps it simple: notes are plain views stacked in a ScrollView (no adapter/recycling to go wrong).
    private fun refresh() {
        val notes = store.list(query)
        notesBox.removeAllViews()
        val textSize = prefs[Prefs.NOTE_TEXT_SIZE].toFloat()
        val lines = prefs[Prefs.PREVIEW_LINES]
        var lastDay: LocalDate? = null
        for (n in notes) {
            val day = n.created.toLocalDate()
            if (day != lastDay) {
                val h = layoutInflater.inflate(R.layout.item_header, notesBox, false) as TextView
                h.text = dayLabel(day)
                notesBox.addView(h)
                lastDay = day
            }
            notesBox.addView(noteCard(n, textSize, lines))
        }
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        empty.setText(if (query.isBlank()) R.string.empty else R.string.empty_search)
    }

    private fun noteCard(n: Note, textSize: Float, lines: Int): View {
        val card = layoutInflater.inflate(R.layout.item_note, notesBox, false)
        card.findViewById<TextView>(R.id.text).apply {
            text = n.text
            maxLines = lines
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
        card.findViewById<TextView>(R.id.time).text = timeLabel(n)
        card.setOnClickListener {
            startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id))
        }
        card.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            requestDelete(n)
            true
        }
        return card
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
        val t = DateFormat.getTimeFormat(this).format(Date(n.created))
        return if (n.updated - n.created > 60_000) "$t  ·  edited" else t
    }

    companion object {
        private const val KEY_DRAFT = "draft"
        fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
