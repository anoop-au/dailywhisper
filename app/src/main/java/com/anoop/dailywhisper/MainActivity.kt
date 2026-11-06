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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import java.util.Date

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
    private lateinit var header: View
    private lateinit var selectBar: View
    private lateinit var selectCount: TextView
    private lateinit var selectDelete: ImageButton
    private lateinit var composerBar: View
    private lateinit var modeRow: View
    private lateinit var modeShort: TextView
    private lateinit var modeLong: TextView
    private lateinit var fab: ImageButton
    private lateinit var composerPair: LinearLayout
    private lateinit var composerTr: TextView
    private lateinit var liveTr: LiveTranslator
    private lateinit var reapplyInsets: () -> Unit

    private val handler = Handler(Looper.getMainLooper())
    private var query = ""
    private var wantKeyboard = false
    private var enterSaves: Boolean? = null
    private var pendingUndo: List<Note> = emptyList()

    /** Multi-select mode (long-press → Select): null when off, else the ids picked, in the order picked. */
    private var selection: LinkedHashSet<Long>? = null
    private val selecting get() = selection != null
    /** Ids of the notes currently shown, for Select all (follows search). */
    private var visibleIds: List<Long> = emptyList()

    private val autoSave = Runnable { if (composer.text.isNotBlank() && !selecting) saveComposer() }
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; pendingUndo = emptyList() }
    private var backCallback: Any? = null

    // Live dictation: the in-progress (partial) words sit at the end of the composer, marked by these spans.
    private val partialColor by lazy { ForegroundColorSpan(getColor(R.color.text2)) }
    private val partialItalic = StyleSpan(Typeface.ITALIC)
    private var micPulse: ValueAnimator? = null
    /** After a Short pause, stop the mic and save the composer as a note. */
    private var saveWhenDictationEnds = false

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
        header = findViewById(R.id.header)
        selectBar = findViewById(R.id.select_bar)
        selectCount = findViewById(R.id.select_count)
        selectDelete = findViewById(R.id.select_delete)
        composerBar = findViewById(R.id.composer_bar)
        modeRow = findViewById(R.id.mode_row)
        modeShort = findViewById(R.id.mode_short)
        modeLong = findViewById(R.id.mode_long)
        fab = findViewById(R.id.fab)
        reapplyInsets = edgeToEdge(findViewById(R.id.root)) { prefs[Prefs.MAX_WIDTH] }


        // Composer
        send.setOnClickListener { saveComposer() }
        liveDictation = StreamingLiveDictation(this, liveCallbacks)
        composerPair = findViewById(R.id.composer_pair)
        composerTr = findViewById(R.id.composer_tr)
        composerTr.movementMethod = android.text.method.ScrollingMovementMethod()
        liveTr = newLiveTranslator()
        mic.setOnClickListener { onMicTap() }
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSend()
                // Composer cleared by hand: its live translation goes too.
                if (s.isNullOrBlank() && !liveDictation.isListening && composerTr.visibility == View.VISIBLE) liveTr.begin()
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
        findViewById<View>(R.id.select_cancel).setOnClickListener { exitSelection() }
        findViewById<Button>(R.id.select_all).setOnClickListener { selectAll() }
        selectDelete.setOnClickListener { requestDeleteSelected() }
        modeShort.setOnClickListener { setMode(WritingMode.SHORT) }
        modeLong.setOnClickListener { setMode(WritingMode.LONG) }
        fab.imageTintList = ColorStateList.valueOf(getColor(R.color.on_accent))
        glow(fab); glow(send)
        fab.setOnClickListener { startLongSession() }
        updateSend()
        applyMode()

        // First run (also once for people updating from an older version): languages, region, consent. Notes untouched.
        if (savedInstanceState == null && !uiState().getBoolean(SetupActivity.KEY_DONE, false))
            startActivity(Intent(this, SetupActivity::class.java))
        else if (savedInstanceState == null && WritingMode.hasChosen(this)) OpusPacks.sync(this)
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        applyMode()
        refresh()
        // Setup just finished: show the mode chooser before the notes list.
        if (uiState().getBoolean(SetupActivity.KEY_DONE, false) && !WritingMode.hasChosen(this)) {
            startActivity(Intent(this, ModeChooserActivity::class.java))
            return
        }
        NoteStore.recentlyDeleted?.let { NoteStore.recentlyDeleted = null; showUndo(listOf(it)) }
        val long = WritingMode.isLong(this)
        if (prefs[Prefs.AUTO_FOCUS] && search.visibility != View.VISIBLE && !selecting && !long) {
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

    /** Android is short of memory and we're in the background: let go of a loaded offline speech pack. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !liveDictation.isListening) OnDeviceSpeech.release()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold / unfold keeps this activity: re-lay the original and its translation for the new width.
        LiveTranslator.arrange(this, composerPair, composer, composerTr, newConfig)
    }

    override fun onDestroy() {
        liveTr.mute()
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
        // Hidden unless some speech slot may receive the audio (Settings → Where your voice goes, per slot).
        mic.visibility = if (prefs[Prefs.SHOW_MIC] && micAvailable()) View.VISIBLE else View.GONE
        reapplyInsets()
    }

    /** Short (Today + composer) or Long (Sessions + start button), from the remembered preference. */
    private fun applyMode() {
        val long = WritingMode.isLong(this)
        modeShort.setBackgroundResource(if (!long) R.drawable.bg_mode_thumb else 0)
        modeLong.setBackgroundResource(if (long) R.drawable.bg_mode_thumb else 0)
        findViewById<TextView>(R.id.subtitle).text = getString(if (long) R.string.sessions_title else R.string.today_title)
        if (selecting) return
        composerBar.visibility = if (long) View.GONE else View.VISIBLE
        fab.visibility = if (long) View.VISIBLE else View.GONE
        if (long) {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(composer.windowToken, 0)
            composer.clearFocus()
        }
    }

    private fun setMode(mode: String) {
        if (WritingMode.current(this) == mode) return
        if (liveDictation.isListening) { liveDictation.stop(); keepPartial() }
        WritingMode.set(this, mode)
        applyMode()
        refresh()
    }

    private fun startLongSession() {
        val id = store.add("", NoteSchema.LONG)
        startActivity(Intent(this, NoteActivity::class.java)
            .putExtra(NoteActivity.EXTRA_ID, id)
            .putExtra(NoteActivity.EXTRA_RECORD, true))
    }

    private fun updateSend() {
        val has = composer.text.isNotBlank()
        send.isEnabled = has
        send.animate().alpha(if (has) 1f else 0.35f).setDuration(Motion.MS).setInterpolator(Motion.interpolator).start()
    }

    private fun saveComposer() {
        handler.removeCallbacks(autoSave)
        if (liveDictation.isListening) { liveDictation.cancel(); keepPartial() }
        val t = composer.text.toString().trim()
        if (t.isEmpty()) return
        // The note keeps the live translation; a sentence still being translated is added when it's done.
        val tr = liveTr
        val id = store.add(t, NoteSchema.SHORT, tr.finalTranslation())
        tr.mute()
        tr.whenSettled { store.setTranslation(id, tr.finalTranslation()) }
        liveTr = newLiveTranslator()
        showLiveTranslation("")
        composer.setTextInternal("")
        uiState().edit().putString(KEY_DRAFT, "").apply()
        composer.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (search.visibility == View.VISIBLE) showSearch(false) else refresh()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // ---- live dictation (composer mic) ----
    private fun onMicTap() {
        if (liveDictation.isListening) { liveDictation.stop(); return }
        // Which slot (Deepgram, then Sarvam AI, then on this phone) can take this language with a key and the user's OK;
        // else say why. Auto (Sarvam AI) falls back to Deepgram's Multilingual when Sarvam AI can't be used. With no
        // internet only on this phone can.
        val language = prefs[Prefs.DG_LANGUAGE]
        val slots = speechSlots()
        val online = isOnline()
        if (SpeechSlots.plan(slots, language, online).slots.isEmpty()) { toast(SpeechSlots.whyNone(slots, language, online)); return }
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
        override fun onPartial(text: String) { putLive(text, final = false); liveTr.onPartial(text) }
        override fun onFinal(text: String) {
            putLive(text, final = true)
            liveTr.onFinal(text)
            // Short: a sentence-ending pause (the Sentence pause setting) finishes the note.
            if (!WritingMode.isLong(this@MainActivity) && text.isNotBlank() && liveDictation.isListening) {
                saveWhenDictationEnds = true
                liveDictation.stop()
            }
        }
        override fun onError(message: String, openSettings: Boolean) { keepPartial(); saveWhenDictationEnds = false; toast(message) }
        override fun onNotice(message: String) = toast(message)
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

    private fun newLiveTranslator() = LiveTranslator(this, onNotice = { toast(it) }) { showLiveTranslation(it) }.also { it.begin() }

    /** Live translation under (or beside) the composer text; hidden when there's none. */
    private fun showLiveTranslation(text: String) {
        composerTr.text = text
        composerTr.typeface = composer.typeface
        composerTr.setTextSize(TypedValue.COMPLEX_UNIT_PX, composer.textSize)
        val vis = if (text.isBlank()) View.GONE else View.VISIBLE
        if (composerTr.visibility != vis) { composerTr.visibility = vis; LiveTranslator.arrange(this, composerPair, composer, composerTr) }
    }

    /** Keeps any leftover partial words as normal text (e.g. when the stream stops before a final). */
    private fun keepPartial() {
        liveTr.dropPartial()
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
            StreamingLiveDictation.State.IDLE -> {
                keepPartial()
                if (saveWhenDictationEnds) { saveWhenDictationEnds = false; saveComposer() }
                null
            }
        }?.apply {
            duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
    }

    /** Screen stays awake only while live dictation is on (cleared on stop, cancel, error and pause). */
    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun glow(view: View) {
        view.elevation = dp(12).toFloat()
        view.outlineAmbientShadowColor = getColor(R.color.accent)
        view.outlineSpotShadowColor = getColor(R.color.accent)
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
    private fun delete(n: Note) { store.delete(n.id); refresh(); showUndo(listOf(n)) }

    private fun showUndo(notes: List<Note>) {
        if (notes.isEmpty()) return
        pendingUndo = notes
        findViewById<TextView>(R.id.undo_text).text =
            if (notes.size == 1) getString(R.string.deleted) else "${notes.size} notes deleted"
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, prefs[Prefs.UNDO_SECONDS] * 1000L)
    }

    private fun undoDelete() {
        if (pendingUndo.isNotEmpty()) store.restoreAll(pendingUndo)
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
            updateBack()
        } else {
            search.setText("")
            search.visibility = View.GONE
            searchBtn.setImageResource(R.drawable.ic_search)
            if (!selecting) composer.requestFocus()
            updateBack()
        }
    }

    /** Back closes multi-select first, then search; otherwise it leaves the app as usual. */
    private fun handleBack(): Boolean = when {
        selecting -> { exitSelection(); true }
        search.visibility == View.VISIBLE -> { showSearch(false); true }
        else -> false
    }

    /** Android 13+: our back callback is registered only while there is something for Back to close. */
    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = selecting || search.visibility == View.VISIBLE
        if (want && backCallback == null) {
            val cb = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!want) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        if (!handleBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- list ----
    // Phase 1 keeps it simple: notes are plain views stacked in a ScrollView (no adapter/recycling to go wrong).
    private fun refresh() {
        // Feed = active notes only. Search (FTS4) also covers archived notes if that setting is on; never deleted ones.
        val long = WritingMode.isLong(this)
        val kind = if (long) NoteSchema.LONG else NoteSchema.SHORT
        val notes = if (query.isBlank()) store.active(kind) else store.search(query, prefs[Prefs.SEARCH_ARCHIVED], kind)
        notesBox.removeAllViews()
        visibleIds = notes.map { it.id }
        // Selection follows what's on screen (e.g. a new search): only notes you can see stay picked.
        selection?.retainAll(visibleIds.toSet())
        val textSize = prefs[Prefs.NOTE_TEXT_SIZE].toFloat()
        val lines = prefs[Prefs.PREVIEW_LINES]
        var lastDay: LocalDate? = null
        for (n in notes) {
            val day = n.created.toLocalDate()
            if (!long && day != lastDay) {
                notesBox.addView(dayHeader(notesBox, day))
                lastDay = day
            }
            notesBox.addView(noteCard(notesBox, n, textSize, lines, cardMeta(n)).apply {
                tag = n.id
                // In multi-select, tap and long-press both just pick or unpick the note.
                setOnClickListener { if (selecting) toggle(n.id) else openNote(n) }
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (selecting) toggle(n.id) else showCardMenu(it, n)
                    true
                }
                showChecked(this, n.id)
            })
        }
        updateSelectBar()
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        empty.setText(when {
            query.isNotBlank() -> R.string.empty_search
            long -> R.string.empty_sessions
            else -> R.string.empty
        })
    }

    /** Long-press on a note card: Share (system share sheet), Delete (soft, with Undo) or Select (multi-select). */
    private fun showCardMenu(anchor: View, n: Note) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Share")
            menu.add(0, 2, 1, "Delete")
            menu.add(0, 3, 2, "Select")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> shareNote(n.text)
                    2 -> requestDelete(n)
                    else -> enterSelection(n.id)
                }
                true
            }
            show()
        }
    }

    // ---- multi-select (soft delete many at once) ----
    private fun enterSelection(firstId: Long) {
        if (liveDictation.isListening) { liveDictation.stop(); keepPartial() }
        handler.removeCallbacks(autoSave)
        selection = linkedSetOf(firstId)
        header.visibility = View.GONE
        modeRow.visibility = View.GONE
        selectBar.visibility = View.VISIBLE
        // The composer steps aside while picking notes (its draft is kept); it comes back on exit.
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(composer.windowToken, 0)
        composer.clearFocus()
        composerBar.visibility = View.GONE
        updateBack()
        refreshChecks()
    }

    private fun exitSelection() {
        if (!selecting) return
        selection = null
        selectBar.visibility = View.GONE
        header.visibility = View.VISIBLE
        modeRow.visibility = View.VISIBLE
        applyMode()
        if (search.visibility != View.VISIBLE && !WritingMode.isLong(this)) {
            composer.requestFocus()
            composer.setSelection(composer.text.length)
        }
        updateBack()
        refreshChecks()
        if (!WritingMode.isLong(this) && composer.text.isNotBlank() && prefs[Prefs.AUTO_SAVE]) handler.postDelayed(autoSave, prefs[Prefs.AUTO_SAVE_SECONDS] * 1000L)
    }

    private fun toggle(id: Long) {
        val sel = selection ?: return
        if (!sel.remove(id)) sel.add(id)
        refreshChecks()
    }

    private fun selectAll() {
        val sel = selection ?: return
        // A second tap when everything is already picked clears the picks (stays in select mode).
        if (sel.containsAll(visibleIds)) sel.clear() else sel.addAll(visibleIds)
        refreshChecks()
    }

    private fun requestDeleteSelected() {
        val ids = selection?.toList().orEmpty()
        if (ids.isEmpty()) return
        // Same rule as a single delete: ask only if "Ask before deleting" is on; otherwise straight to Undo.
        if (prefs[Prefs.CONFIRM_DELETE]) {
            AlertDialog.Builder(this)
                .setMessage(if (ids.size == 1) "Delete this note?" else "Delete ${ids.size} notes?")
                .setPositiveButton("Delete") { _, _ -> deleteSelected(ids) }
                .setNegativeButton("Cancel", null)
                .show()
        } else deleteSelected(ids)
    }

    private fun deleteSelected(ids: List<Long>) {
        val deleted = store.deleteAll(ids)
        exitSelection()
        refresh()
        showUndo(deleted)
    }

    private fun refreshChecks() {
        for (i in 0 until notesBox.childCount) {
            val card = notesBox.getChildAt(i)
            (card.tag as? Long)?.let { showChecked(card, it) }
        }
        updateSelectBar()
    }

    private fun showChecked(card: View, id: Long) {
        val sel = selection
        val check = card.findViewById<ImageView>(R.id.check) ?: return
        if (sel == null) {
            check.visibility = View.GONE
            card.foreground = null
            card.isSelected = false
            return
        }
        val on = id in sel
        check.visibility = View.VISIBLE
        check.setImageResource(if (on) R.drawable.ic_check_on else R.drawable.ic_check_off)
        card.isSelected = on
        card.foreground = if (on) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(2), getColor(R.color.accent))
        } else null
    }

    private fun updateSelectBar() {
        val n = selection?.size ?: return
        selectCount.text = "$n selected"
        selectDelete.isEnabled = n > 0
        selectDelete.alpha = if (n > 0) 1f else 0.35f
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
