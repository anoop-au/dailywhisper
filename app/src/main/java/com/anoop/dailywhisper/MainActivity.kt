package com.anoop.dailywhisper

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MainActivity : ThemedActivity() {

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

    // Live dictation: the in-progress (partial) words sit at the end of the composer, marked by these spans.
    private val partialColor by lazy { ForegroundColorSpan(getColor(R.color.text2)) }
    private val partialItalic = StyleSpan(Typeface.ITALIC)
    private var micPulse: ValueAnimator? = null

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
        liveDictation = StreamingLiveDictation(this, liveCallbacks)
        mic.setOnClickListener { onMicTap() }
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSend()
                handler.removeCallbacks(autoSave)
                // No auto-save while live dictation is still typing; the user saves when ready.
                if (prefs[Prefs.AUTO_SAVE] && !s.isNullOrBlank() && !liveDictation.isListening) handler.postDelayed(autoSave, prefs[Prefs.AUTO_SAVE_SECONDS] * 1000L)
            }
        })
        composer.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { saveComposer(); true } else false
        }
        composer.setTextInternal(uiState().getString(KEY_DRAFT, "").orEmpty())

        // Header
        findViewById<TextView>(R.id.subtitle).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        findViewById<ImageButton>(R.id.btn_theme).apply {
            // Sun in dark mode (bright red tint, see colors theme_toggle), moon in light mode.
            setImageResource(if (isNight) R.drawable.ic_sun else R.drawable.ic_moon)
            contentDescription = getString(R.string.theme_toggle)
            setOnClickListener { toggleTheme() }
        }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
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
        keepScreenOn(false)
        // Draft survives the app being closed or killed, so dictated text is never lost.
        uiState().edit().putString(KEY_DRAFT, composer.text.toString()).apply()
    }

    override fun onDestroy() {
        liveDictation.cancel()
        micPulse?.cancel()
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
        if (liveDictation.isListening) { liveDictation.cancel(); keepPartial() }
        val t = composer.text.toString().trim()
        if (t.isEmpty()) return
        store.add(t)
        composer.setTextInternal("")
        uiState().edit().putString(KEY_DRAFT, "").apply()
        composer.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (search.visibility == View.VISIBLE) showSearch(false) else refresh()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // ---- live dictation (composer mic) ----
    private fun onMicTap() {
        if (liveDictation.isListening) { liveDictation.stop(); return }
        if (!SecretStore.has(this, Prefs.STT_API_KEY.key)) {
            toast("Add your Deepgram API key in Settings → Live dictation"); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        mic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        liveDictation.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) liveDictation.start()
        else toast("Live dictation needs the microphone. You can allow it in Android Settings → Apps → Daily Whisper")
    }

    private val liveCallbacks = object : StreamingLiveDictation.Callbacks {
        override fun onState(state: StreamingLiveDictation.State) = showMicState(state)
        override fun onPartial(text: String) = putLive(text, final = false)
        override fun onFinal(text: String) = putLive(text, final = true)
        override fun onError(message: String, openSettings: Boolean) { keepPartial(); toast(message) }
    }

    /** Replaces the current partial words at the end of the composer with [text]; finals become plain text. */
    private fun putLive(text: String, final: Boolean) {
        val e = composer.text
        var start = e.getSpanStart(partialColor)
        var end = e.getSpanEnd(partialColor)
        if (start < 0 || end < start) { start = e.length; end = e.length }
        e.removeSpan(partialColor); e.removeSpan(partialItalic)
        val words = text.trim()
        val ins = if (words.isEmpty()) "" else (if (start > 0 && !e[start - 1].isWhitespace()) " " else "") + words
        val hadCursorAtEnd = composer.selectionEnd >= end
        e.replace(start, end, ins)
        if (!final && ins.isNotEmpty()) {
            e.setSpan(partialColor, start, start + ins.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.setSpan(partialItalic, start, start + ins.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (hadCursorAtEnd) composer.setSelection(e.length)
    }

    /** Keeps any leftover partial words as normal text (e.g. when the stream stops before a final). */
    private fun keepPartial() {
        composer.text.removeSpan(partialColor)
        composer.text.removeSpan(partialItalic)
    }

    private fun showMicState(state: StreamingLiveDictation.State) {
        micPulse?.cancel(); micPulse = null
        mic.scaleX = 1f; mic.scaleY = 1f; mic.alpha = 1f
        val active = state != StreamingLiveDictation.State.IDLE
        keepScreenOn(active && prefs[Prefs.KEEP_SCREEN_ON])
        mic.setBackgroundResource(if (active) R.drawable.bg_mic_active else R.drawable.bg_icon_btn)
        mic.imageTintList = ColorStateList.valueOf(getColor(if (active) R.color.on_accent else R.color.accent))
        mic.contentDescription = getString(if (active) R.string.live_dictation_stop else R.string.live_dictation)
        micPulse = when (state) {
            StreamingLiveDictation.State.CONNECTING -> ObjectAnimator.ofFloat(mic, View.ALPHA, 1f, 0.45f)
            StreamingLiveDictation.State.LISTENING -> ObjectAnimator.ofPropertyValuesHolder(mic,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f))
            StreamingLiveDictation.State.STOPPING -> { mic.alpha = 0.6f; null }
            StreamingLiveDictation.State.IDLE -> { keepPartial(); null }
        }?.apply {
            duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
    }

    /** Screen stays awake only while live dictation is on (cleared on stop, cancel, error and pause). */
    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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

    /** Soft delete (moves to Deleted); the Undo bar puts it straight back. */
    private fun delete(n: Note) { store.delete(n.id); refresh(); showUndo(n) }

    private fun showUndo(n: Note) {
        pendingUndo = n
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, prefs[Prefs.UNDO_SECONDS] * 1000L)
    }

    private fun undoDelete() {
        pendingUndo?.let { store.restore(it.id, it.status) }
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
        // Feed = active notes only. Search (FTS4) also covers archived notes if that setting is on; never deleted ones.
        val notes = if (query.isBlank()) store.active() else store.search(query, prefs[Prefs.SEARCH_ARCHIVED])
        notesBox.removeAllViews()
        val textSize = prefs[Prefs.NOTE_TEXT_SIZE].toFloat()
        val lines = prefs[Prefs.PREVIEW_LINES]
        var lastDay: LocalDate? = null
        for (n in notes) {
            val day = n.created.toLocalDate()
            if (day != lastDay) {
                notesBox.addView(dayHeader(notesBox, day))
                lastDay = day
            }
            notesBox.addView(noteCard(notesBox, n, textSize, lines, cardMeta(n)).apply {
                setOnClickListener { openNote(n) }
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    requestDelete(n)
                    true
                }
            })
        }
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        empty.setText(if (query.isBlank()) R.string.empty else R.string.empty_search)
    }

    private fun openNote(n: Note) =
        startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id))

    // ---- Archive / Deleted (header "more" menu) ----
    private fun showMore(anchor: View) {
        val deleted = store.deletedCount()
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Archive")
            menu.add(0, 2, 1, if (deleted > 0) "Deleted ($deleted)" else "Deleted")
            setOnMenuItemClickListener {
                val mode = if (it.itemId == 1) CollectionActivity.MODE_ARCHIVED else CollectionActivity.MODE_DELETED
                startActivity(Intent(this@MainActivity, CollectionActivity::class.java).putExtra(CollectionActivity.EXTRA_MODE, mode))
                true
            }
            show()
        }
    }

    companion object {
        private const val KEY_DRAFT = "draft"
        private const val REQ_MIC = 7
    }
}
