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
import android.widget.TextView
import java.util.Locale

/**
 * First-run setup, once after install (or update): two small, friendly questions.
 *  1. Which language do you mostly write in?  2. Which language should notes be translated to?
 * Translation is ML Kit on the phone; Done starts fetching that pair's language packs so the first tap is quick.
 * Choices go to Settings → Translation (changeable any time); the done flag lives in "state", so Reset to
 * defaults doesn't bring setup back. Notes are never touched here.
 */
class SetupActivity : ThemedActivity() {

    private val prefs by lazy { settings() }
    private lateinit var body: LinearLayout
    private var step = 0
    private var source = TranslateConfig.DEFAULT_SOURCE
    private var target = TranslateConfig.DEFAULT_TARGET

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

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() { if (step == 1) show(0) else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun show(s: Int) {
        step = s
        body.removeAllViews()
        when (s) {
            0 -> languageStep("Hi there.", "Which language do you mostly speak or write in?", source) { source = it; show(1) }
            else -> languageStep("Nice.", "And which language would you like your notes translated to?", target) { target = it; done() }
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
        if (step == 0) textButton(f, "Skip for now") { finishSetup() } else textButton(f, "Back") { show(step - 1) }
        spacer(f)
        mainButton(f, if (step == 0) "Next" else "Done") { onPick(chosen) }
    }

    private fun done() {
        prefs.edit().putString(Prefs.TR_SOURCE.key, source).putString(Prefs.TR_TARGET.key, target).apply()
        NoteTranslator.prefetch(this, source, target)
        finishSetup()
    }

    private fun markDone() = uiState().edit().putBoolean(KEY_DONE, true).apply()

    private fun finishSetup() { markDone(); finish() }

    companion object {
        const val KEY_DONE = "setup_done"
    }
}
