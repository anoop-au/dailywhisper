package com.anoop.dailywhisper

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/**
 * First-run setup, once after install (or once after updating from an older version):
 *  0. Welcome: icon + one line; moves on by itself after ~1.75 s, or on a tap.
 *  1. Which language you write/speak notes in.  2. Which language to translate them into.
 *  3. Which region you're in (explicit pick). Next saves everything and starts fetching the Opus-MT models for that
 *     pair in the background (OpusPacks; ML Kit covers until they're ready).
 *  4. Where live-dictation audio goes: a short "stays in your region" line, or the destination and a yes/no.
 *  5. "You can change these later in Settings." Then the notes list.
 * Languages go to Settings → Translation; region and consent to "state" (Settings → Live dictation shows them).
 * The done flag also lives in "state", so Reset to defaults doesn't bring setup back. Notes are never touched here.
 * Run again from Settings, it starts at the language question.
 */
class SetupActivity : ThemedActivity() {

    private val prefs by lazy { settings() }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private var step = 0
    private var source = TranslateConfig.DEFAULT_SOURCE
    private var target = TranslateConfig.DEFAULT_TARGET
    private var region: String? = null
    private var status: TextView? = null
    private val onProgress: () -> Unit = { showStatus() }
    private val ticker = object : Runnable { override fun run() { showStatus(); main.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start from what's saved (re-run from Settings), else guess the phone's language.
        val phone = Locale.getDefault().language.let { if (it == "iw") "he" else if (it == "in") "id" else it }
        source = savedInstanceState?.getString("source") ?: if (prefs.contains(Prefs.TR_SOURCE.key)) prefs[Prefs.TR_SOURCE]
            else TranslateConfig.lang(phone)?.code ?: TranslateConfig.DEFAULT_SOURCE
        target = savedInstanceState?.getString("target") ?: if (prefs.contains(Prefs.TR_TARGET.key)) prefs[Prefs.TR_TARGET]
            else if (source != "en") "en" else TranslateConfig.DEFAULT_TARGET
        // Region is picked explicitly the first time (nothing pre-selected); a re-run shows the saved one.
        region = savedInstanceState?.getString("region") ?: if (uiState().getBoolean(KEY_DONE, false)) userRegion() else null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(36), dp(28), dp(20)) }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        edgeToEdge(root) { prefs[Prefs.MAX_WIDTH] }
        show(savedInstanceState?.getInt("step") ?: if (intent.getBooleanExtra(EXTRA_RERUN, false)) 1 else 0)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("step", step); out.putString("source", source); out.putString("target", target); out.putString("region", region)
    }

    override fun onResume() { super.onResume(); OpusPacks.listen(onProgress); main.post(ticker); if (step == 0) autoAdvance() }

    override fun onPause() { super.onPause(); OpusPacks.unlisten(onProgress); main.removeCallbacksAndMessages(null) }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        when (step) {
            2, 3 -> show(step - 1)
            5 -> show(4)
            else -> @Suppress("DEPRECATION") super.onBackPressed() // leaves setup unfinished: it comes back next launch
        }
    }

    private fun show(s: Int) {
        step = s
        main.removeCallbacksAndMessages(null); main.post(ticker)
        body.removeAllViews(); status = null
        when (s) {
            0 -> welcome()
            1 -> languageStep("Which language do you usually write or speak your notes in?",
                "Keyboard language doesn't matter. You can whisper in the language that comes first.", source) { source = it; show(2) }
            2 -> languageStep("Which language would you like your notes translated into?", null, target) { target = it; show(3) }
            3 -> regionStep()
            4 -> residencyStep()
            else -> finalStep()
        }
    }

    // ---- small building blocks, in the app's calm style ----
    private fun headline(text: String, size: Float = 24f) = TextView(this).apply {
        this.text = text; textSize = size; typeface = Typeface.SERIF; setTextColor(getColor(R.color.text)); setLineSpacing(0f, 1.1f)
    }.also { body.addView(it) }

    private fun line(text: String, size: Float = 17f, color: Int = R.color.text2) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(getColor(color)); setPadding(0, dp(8), 0, dp(8)); setLineSpacing(0f, 1.2f)
    }.also { body.addView(it) }

    private fun footer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0)
    }.also { body.addView(it) }

    private fun textButton(parent: LinearLayout, label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(getColor(R.color.text2))
        setBackgroundResource(R.drawable.bg_icon_btn); setOnClickListener { onClick() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))) }

    private fun mainButton(parent: LinearLayout, label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(getColor(R.color.on_accent))
        setBackgroundResource(R.drawable.bg_pill)
        setPadding(dp(24), 0, dp(24), 0); setOnClickListener { onClick() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))) }

    private fun spacer(parent: LinearLayout) = parent.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    private fun grow() = body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

    /** A quiet line about the Opus-MT download, only when the chosen pair has one. */
    private fun statusLine() {
        if (OpusPacks.wanted(this).isEmpty()) return
        status = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.hint)); setPadding(0, dp(12), 0, 0) }
            .also { body.addView(it) }
        showStatus()
    }

    private fun showStatus() { status?.text = OpusPacks.statusLine(this) }

    // ---- steps ----
    private fun welcome() {
        body.gravity = Gravity.CENTER
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f }
        box.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = null },
            LinearLayout.LayoutParams(dp(88), dp(88)))
        box.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 30f; typeface = Typeface.SERIF; setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(6))
        })
        box.addView(TextView(this).apply {
            text = "Say it softly. Keep it close."; textSize = 16f; setTextColor(getColor(R.color.text2)); gravity = Gravity.CENTER
        })
        body.addView(box)
        box.animate().alpha(1f).setDuration(450).start()
        body.setOnClickListener { if (step == 0) next0() }
        autoAdvance()
    }

    private fun autoAdvance() { main.postDelayed({ if (step == 0 && !isFinishing) next0() }, WELCOME_MS) }

    private fun next0() { body.setOnClickListener(null); body.isClickable = false; body.gravity = Gravity.NO_GRAVITY; show(1) }

    private fun languageStep(question: String, note: String?, current: String, onPick: (String) -> Unit) {
        headline(question)
        note?.let { line(it, 14f, R.color.hint) }
        val all = TranslateConfig.options()
        var shown = all
        var chosen = current
        val search = EditText(this).apply {
            hint = "Search languages"; isSingleLine = true; setBackgroundResource(R.drawable.bg_search)
            setPadding(dp(16), dp(10), dp(16), dp(10)); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        body.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_SINGLE; divider = null }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, ArrayList())
        list.adapter = adapter
        fun filter(q: String) {
            val t = q.trim()
            shown = if (t.isEmpty()) all else all.filter { it.second.contains(t, true) || it.first.equals(t, true) }
            adapter.clear(); adapter.addAll(shown.map { it.second })
            list.clearChoices()
            shown.indexOfFirst { it.first == chosen }.takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        list.setOnItemClickListener { _, _, pos, _ -> chosen = shown[pos].first }
        filter("")
        list.setSelection((shown.indexOfFirst { it.first == chosen } - 2).coerceAtLeast(0))
        body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val f = footer()
        if (step > 1) textButton(f, "Back") { show(step - 1) }
        spacer(f)
        mainButton(f, "Next") { onPick(chosen) }
    }

    private fun regionStep() {
        headline("Which region are you in?")
        line("Live dictation uses this to keep your voice close to home.", 14f, R.color.hint)
        val regions = Residency.REGIONS
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_SINGLE; divider = null }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, regions.map { it.name })
        regions.indexOfFirst { it.code == region }.takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
        body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val f = footer()
        textButton(f, "Back") { show(2) }
        spacer(f)
        val next = mainButton(f, "Next") { region?.let { saveChoices(it); show(4) } }
        fun enable() { next.isEnabled = region != null; next.alpha = if (region != null) 1f else 0.4f }
        list.setOnItemClickListener { _, _, pos, _ -> region = regions[pos].code; enable() }
        enable()
    }

    /** Saves languages + region and starts the background work: Opus-MT models for the pair, ML Kit packs as the fallback. */
    private fun saveChoices(r: String) {
        prefs.edit().putString(Prefs.TR_SOURCE.key, source).putString(Prefs.TR_TARGET.key, target).apply()
        if (r != userRegion() || !hasUserRegion()) setUserRegion(r) // also points live dictation at that region
        OpusPacks.start(this)
        NoteTranslator.prefetch(this, source, target)
    }

    private fun residencyStep() {
        val r = userRegion()
        val dg = prefs[Prefs.DG_REGION]
        if (Residency.inRegion(r, dg)) {
            headline("Your voice stays close.")
            line(Residency.inRegionLine(dg))
            grow(); statusLine()
            val f = footer()
            textButton(f, "Back") { show(3) }
            spacer(f)
            mainButton(f, "Next") { show(5) }
        } else {
            headline("Is it OK to send audio to ${Residency.placeOf(dg)}?")
            line(Residency.consentLine(r, dg))
            line("Only when you tap the mic. Everything else works without it.", 14f, R.color.hint)
            grow(); statusLine()
            val f = footer()
            textButton(f, "Not now") { setDgConsent(""); show(5) }
            spacer(f)
            mainButton(f, "Allow") { setDgConsent(dg); show(5) }
        }
    }

    private fun finalStep() {
        headline("All set.", 28f)
        line("You can change these later in Settings.")
        grow(); statusLine()
        val f = footer()
        spacer(f)
        // From Settings, this goes back to Settings, so don't promise the notes.
        mainButton(f, if (intent.getBooleanExtra(EXTRA_RERUN, false)) "Done" else "Open my notes") { finishSetup() }
    }

    private fun finishSetup() { uiState().edit().putBoolean(KEY_DONE, true).apply(); finish() }

    companion object {
        /** New key for v0.1.7's flow: everyone sees it once (the earlier "setup_done" flag never shipped). */
        const val KEY_DONE = "first_run_done"
        const val EXTRA_RERUN = "rerun"
        private const val WELCOME_MS = 1750L
    }
}
