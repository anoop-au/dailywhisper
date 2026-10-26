package com.anoop.dailywhisper

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/**
 * First-run setup, once after install (or update): a few small, friendly screens.
 *  1. Which language do you mostly write in?  2. Which language should notes be translated to?
 *  3. Built-in translator, or download the better offline model once (~440 MB)?  (4. download progress)
 * Choices go to Settings → Translation (changeable any time); the done flag lives in "state", so Reset to
 * defaults doesn't bring setup back. Notes are never touched here.
 */
class SetupActivity : ThemedActivity() {

    private val prefs by lazy { settings() }
    private lateinit var body: LinearLayout
    private var step = 0
    private var source = TranslateConfig.DEFAULT_SOURCE
    private var target = TranslateConfig.DEFAULT_TARGET
    private var modelListener: ((ModelDownloads.State) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start from what's saved (re-run from Settings), else guess the phone's language.
        val phone = Locale.getDefault().language.let { if (it == "iw") "he" else if (it == "in") "id" else it }
        source = if (prefs.contains(Prefs.TR_SOURCE.key)) prefs[Prefs.TR_SOURCE]
            else TranslateConfig.lang(phone)?.code ?: TranslateConfig.DEFAULT_SOURCE
        target = if (prefs.contains(Prefs.TR_TARGET.key)) prefs[Prefs.TR_TARGET]
            else if (source != "en") "en" else TranslateConfig.DEFAULT_TARGET
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(36), dp(28), dp(20)) }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        edgeToEdge(root) { prefs[Prefs.MAX_WIDTH] }
        show(savedInstanceState?.getInt("step") ?: 0)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("step", step) }

    override fun onDestroy() { modelListener?.let { ModelDownloads.unobserve(it) }; super.onDestroy() }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() { if (step in 1..2) show(step - 1) else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun show(s: Int) {
        step = s
        body.removeAllViews()
        when (s) {
            0 -> languageStep("Hi there.", "Which language do you mostly speak or write in?", source) { source = it; show(1) }
            1 -> languageStep("Nice.", "And which language would you like your notes translated to?", target) { target = it; show(2) }
            2 -> qualityStep()
            else -> downloadStep()
        }
    }

    // ---- small building blocks, in the app's calm style ----
    private fun hello(text: String) = TextView(this).apply {
        this.text = text; textSize = 28f; typeface = Typeface.SERIF; setTextColor(getColor(R.color.text))
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

    // ---- steps ----
    private fun languageStep(greeting: String, question: String, current: String, onPick: (String) -> Unit) {
        hello(greeting)
        line(question)
        val all = TranslateConfig.options(null)
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
        if (step == 0) textButton(f, "Skip for now") { finishSetup() } else textButton(f, "Back") { show(step - 1) }
        spacer(f)
        mainButton(f, "Next") { onPick(chosen) }
    }

    private fun qualityStep() {
        hello("Last thing.")
        line("How should translation work? Both stay fully on your phone.")
        val mlOk = MlKitTranslator(this).supports(source, target)
        val hyOk = TranslateConfig.supports(TranslateConfig.BACKEND_HYMT, target)
        var choice = when {
            prefs[Prefs.TR_BACKEND] == TranslateConfig.BACKEND_HYMT && hyOk -> TranslateConfig.BACKEND_HYMT
            mlOk -> TranslateConfig.BACKEND_MLKIT
            hyOk -> TranslateConfig.BACKEND_HYMT
            else -> TranslateConfig.BACKEND_MLKIT
        }
        val cards = ArrayList<Pair<String, View>>()
        fun mark() = cards.forEach { (k, v) -> v.alpha = if (v.isEnabled) 1f else 0.45f; v.isSelected = k == choice
            v.setBackgroundResource(if (k == choice) R.drawable.bg_card_selected else R.drawable.bg_card) }
        fun card(key: String, title: String, text: String, enabled: Boolean, whyNot: String) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); isEnabled = enabled
                addView(TextView(context).apply { this.text = title; textSize = 17f; setTextColor(getColor(R.color.text)) })
                addView(TextView(context).apply {
                    this.text = if (enabled) text else whyNot; textSize = 14f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { if (enabled) { choice = key; mark() } }
            }
            body.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
            cards += key to c
        }
        val pair = "${TranslateConfig.languageName(source)} → ${TranslateConfig.languageName(target)}"
        card(TranslateConfig.BACKEND_MLKIT, "Use the built-in translator",
            "No extra download. Good for everyday notes. (It fetches a small ~30 MB pack for $pair the first time.)",
            mlOk, if (source == target) "Pick two different languages to use this one." else "It can't do $pair, sorry.")
        card(TranslateConfig.BACKEND_HYMT, "Download a better offline model, once",
            "About 440 MB, in the background. Higher quality, fully offline after that.",
            hyOk, "It doesn't do ${TranslateConfig.languageName(target)} yet.")
        mark()
        val wifi = Switch(this).apply {
            text = "Download on Wi-Fi only"; textSize = 15f; setTextColor(getColor(R.color.text2)); isChecked = prefs[Prefs.TR_WIFI_ONLY]
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(Prefs.TR_WIFI_ONLY.key, v).apply() }
        }
        body.addView(wifi, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        line("You can change any of this later in Settings → Translation.", 13f, R.color.hint)
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val f = footer()
        textButton(f, "Back") { show(1) }
        spacer(f)
        mainButton(f, "Done") {
            prefs.edit().putString(Prefs.TR_SOURCE.key, source).putString(Prefs.TR_TARGET.key, target)
                .putString(Prefs.TR_BACKEND.key, choice).apply()
            if (choice == TranslateConfig.BACKEND_HYMT) {
                startModelDownload()
                if (mlOk) MlKitTranslator.prefetch(this, source, target) // built-in covers the wait
                markDone(); show(3)
            } else {
                MlKitTranslator.prefetch(this, source, target)
                finishSetup()
            }
        }
    }

    private fun downloadStep() {
        hello("On its way.")
        line("The better model is downloading in the background. You can start writing now. Until it's ready, " +
            "a tap on a note uses the built-in translator.")
        val status = line("", 15f, R.color.accent)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
        }
        body.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
        line("Progress also shows in Settings → Translation.", 13f, R.color.hint)
        body.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val f = footer()
        spacer(f)
        mainButton(f, "Start writing") { finishSetup() }
        val l: (ModelDownloads.State) -> Unit = { s ->
            status.text = modelStatusText(s)
            bar.isIndeterminate = s.status == ModelDownloads.Status.VERIFYING
            bar.progress = s.percent
        }
        modelListener = l
        ModelDownloads.observe(this, l)
    }

    private fun markDone() = uiState().edit().putBoolean(KEY_DONE, true).apply()

    private fun finishSetup() { markDone(); finish() }

    companion object {
        const val KEY_DONE = "setup_done"
    }
}
