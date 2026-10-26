package com.anoop.dailywhisper

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** One plain screen listing every setting in [Prefs], each with its default, plus Reset. */
class SettingsActivity : ThemedActivity() {

    private val prefs by lazy { settings() }
    private var modelListener: ((ModelDownloads.State) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        ModelDownloads.resumeIfWanted(this)
        modelListener?.let { ModelDownloads.observe(this, it) }
    }

    override fun onPause() {
        super.onPause()
        modelListener?.let { ModelDownloads.unobserve(it) }
    }

    private fun build() {
        modelListener?.let { ModelDownloads.unobserve(it) }
        modelListener = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.bg_icon_btn)
            contentDescription = getString(R.string.back); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = getString(R.string.settings); textSize = 24f; typeface = Typeface.SERIF
            setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(32)) }
        for ((title, items) in Prefs.sections) {
            col.addView(TextView(this).apply {
                text = title.uppercase(); textSize = 12f; letterSpacing = 0.1f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.text2)); setPadding(dp(12), dp(20), dp(12), dp(8))
            })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_panel); setPadding(0, dp(6), 0, dp(6))
            }
            items.forEach { card.addView(row(it)) }
            col.addView(card)
        }
        col.addView(Button(this).apply {
            text = "Reset to defaults"; isAllCaps = false; setTextColor(getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_icon_btn)
            setOnClickListener {
                val theme = prefs[Prefs.THEME]
                prefs.edit().clear().apply()
                if (prefs[Prefs.THEME] != theme) recreate() else build()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
        })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        col.addView(TextView(this).apply {
            text = "Daily Whisper $version\nYour notes stay on this phone."
            gravity = Gravity.CENTER; textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(12), 0, 0)
        })

        root.addView(ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(root) { prefs[Prefs.MAX_WIDTH] }
    }

    private fun row(p: Pref): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = p.title; textSize = 16f; setTextColor(getColor(R.color.text)) })
        if (p.summary.isNotEmpty()) texts.addView(TextView(this).apply {
            text = p.summary; textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(2), 0, 0)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(top)

        when (p) {
            is ActionPref -> if (p === Prefs.HYMT_MODEL) modelRow(row) else {
                row.setOnClickListener { startActivity(android.content.Intent(this, SetupActivity::class.java)) }
            }
            is BoolPref -> {
                val sw = Switch(this).apply {
                    isChecked = prefs[p]
                    setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(p.key, v).apply() }
                }
                top.addView(sw)
                row.setOnClickListener { sw.toggle() }
            }
            is IntPref -> {
                val value = TextView(this).apply {
                    textSize = 15f; setTextColor(getColor(R.color.accent)); setPadding(dp(12), 0, 0, 0)
                    text = "${prefs[p]}${p.unit}"
                }
                top.addView(value)
                row.addView(SeekBar(this).apply {
                    max = (p.max - p.min) / p.step
                    progress = (prefs[p] - p.min) / p.step
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val v = p.min + progress * p.step
                            value.text = "$v${p.unit}"
                            if (fromUser) prefs.edit().putInt(p.key, v).apply()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                })
                row.addView(TextView(this).apply {
                    text = "Default ${p.def}${p.unit}"; textSize = 12f; setTextColor(getColor(R.color.hint)); setPadding(dp(16), 0, 0, 0)
                })
            }
            is TextPref -> {
                val value = valueLine(row)
                fun show() {
                    val v = prefs[p]
                    value.text = when {
                        v.isNotEmpty() -> v
                        p.def.isEmpty() -> "Not set"
                        else -> "Default ${p.def}"
                    }
                }
                show()
                row.setOnClickListener {
                    val input = editor(p.multiline, p.hint).apply { setText(prefs[p]); setSelection(text.length) }
                    AlertDialog.Builder(this).setTitle(p.title).setView(wrap(input))
                        .setPositiveButton("Save") { _, _ -> prefs.edit().putString(p.key, input.text.toString().trim()).apply(); show() }
                        .setNeutralButton(if (p.def.isEmpty()) "Clear" else "Default") { _, _ -> prefs.edit().remove(p.key).apply(); show() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            is ChoicePref -> {
                val value = valueLine(row)
                fun show() {
                    value.text = p.options.first { it.first == prefs[p] }.second
                    Highlight.of(prefs[p]).takeIf { p === Prefs.HIGHLIGHT_DEFAULT }?.let { value.setTextColor(it.swatch) }
                }
                show()
                fun choose(code: String) {
                    prefs.edit().putString(p.key, code).apply(); show()
                    if (p === Prefs.THEME) recreate()
                    if (p === Prefs.TR_BACKEND) onBackendChosen(code)
                }
                row.setOnClickListener {
                    if (p.searchable) { searchablePicker(p, ::choose); return@setOnClickListener }
                    if (p === Prefs.HIGHLIGHT_DEFAULT) {
                        pickHighlight(p.title, prefs[p], allowNone = false) { k -> k?.let(::choose) }
                        return@setOnClickListener
                    }
                    val idx = p.options.indexOfFirst { it.first == prefs[p] }
                    AlertDialog.Builder(this).setTitle(p.title)
                        .setSingleChoiceItems(p.options.map { it.second }.toTypedArray(), idx) { d, which ->
                            d.dismiss(); choose(p.options[which].first)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            is SecretPref -> {
                // The saved key is never shown or pre-filled: the dialog only replaces or clears it.
                val value = valueLine(row)
                fun show() { value.text = if (SecretStore.has(this, p.key)) "Saved  ••••••••" else "Not set" }
                show()
                row.setOnClickListener {
                    val input = editor(false, "Paste key").apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    AlertDialog.Builder(this).setTitle(p.title).setView(wrap(input))
                        .setPositiveButton("Save") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.isNotEmpty()) SecretStore.put(this, p.key, v)
                            input.setText(""); show()
                        }
                        .setNeutralButton("Clear") { _, _ -> SecretStore.put(this, p.key, ""); show() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        return row
    }

    /** Long lists (languages): a search box above a scrollable single-choice list. Shows names, stores codes. */
    private fun searchablePicker(p: ChoicePref, choose: (String) -> Unit) {
        val current = prefs[p]
        val translation = p === Prefs.TR_SOURCE || p === Prefs.TR_TARGET
        val options = if (translation) TranslateConfig.options(prefs[Prefs.TR_BACKEND]) else p.options
        var shown = options
        val list = android.widget.ListView(this).apply { choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE }
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, ArrayList())
        list.adapter = adapter
        fun filter(q: String) {
            val t = q.trim()
            shown = if (t.isEmpty()) options else options.filter { it.second.contains(t, true) || it.first.contains(t, true) }
            adapter.clear(); adapter.addAll(shown.map { it.second })
            list.clearChoices()
            shown.indexOfFirst { it.first == current }.takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
        }
        val search = editor(false, "Search languages").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString().orEmpty())
        })
        val note = TextView(this).apply {
            text = if (!translation) "Listed for nova-3. Other models support fewer."
                else if (prefs[Prefs.TR_BACKEND] == TranslateConfig.BACKEND_HYMT) "Languages Hy-MT can translate."
                else "Languages the built-in translator can do. Hy-MT adds a few more (Burmese, Khmer, Tibetan…)."
            textSize = 12f
            setTextColor(getColor(R.color.text2)); setPadding(dp(4), dp(4), 0, dp(4))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(search); addView(note)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))
        }
        filter("")
        list.setSelection(shown.indexOfFirst { it.first == current }.coerceAtLeast(0))
        val dialog = AlertDialog.Builder(this).setTitle(p.title).setView(box).setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, pos, _ -> dialog.dismiss(); choose(shown[pos].first) }
        dialog.show()
    }

    /** Switching to Hy-MT offers the download; translation keeps using built-in until it's ready. */
    private fun onBackendChosen(code: String) {
        if (code != TranslateConfig.BACKEND_HYMT || ModelDownloads.isReady(this) || ModelDownloads.running) return
        AlertDialog.Builder(this)
            .setMessage("Download the Hy-MT model now? It's about 440 MB, once. Until it's ready, translations use the built-in translator.")
            .setPositiveButton("Download") { _, _ -> startModelDownload() }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Model status line, a progress bar, and Download / Pause / Delete. Live while this screen is open. */
    private fun modelRow(row: LinearLayout) {
        val status = valueLine(row)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
        }
        row.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buttons)
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextColor(getColor(R.color.accent)); setBackgroundResource(R.drawable.bg_icon_btn)
            setOnClickListener { onClick() }
        }.also { buttons.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }) }
        val listener: (ModelDownloads.State) -> Unit = { s ->
            status.text = modelStatusText(s)
            val busy = s.status == ModelDownloads.Status.DOWNLOADING || s.status == ModelDownloads.Status.VERIFYING
            bar.visibility = if (busy || (s.bytes > 0 && s.status != ModelDownloads.Status.READY)) View.VISIBLE else View.GONE
            bar.isIndeterminate = s.status == ModelDownloads.Status.VERIFYING
            bar.progress = s.percent
            buttons.removeAllViews()
            when (s.status) {
                ModelDownloads.Status.READY -> btn("Delete model") {
                    AlertDialog.Builder(this).setMessage("Delete the Hy-MT model (440 MB)? Translations will use the built-in translator.")
                        .setPositiveButton("Delete") { _, _ -> ModelDownloads.delete(this) }.setNegativeButton("Cancel", null).show()
                }
                ModelDownloads.Status.DOWNLOADING, ModelDownloads.Status.VERIFYING -> btn("Pause") { ModelDownloads.pause(this) }
                else -> {
                    btn(if (s.bytes > 0) "Resume" else "Download") { startModelDownload() }
                    if (s.bytes > 0) btn("Remove") { ModelDownloads.delete(this) }
                }
            }
        }
        modelListener = listener
        ModelDownloads.observe(this, listener)
    }

    private fun valueLine(row: LinearLayout): TextView = TextView(this).apply {
        textSize = 14f; setTextColor(getColor(R.color.accent)); setPadding(0, dp(4), 0, 0)
        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
    }.also { row.addView(it) }

    private fun editor(multiline: Boolean, hint: String): EditText = EditText(this).apply {
        this.hint = hint
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI)
        if (multiline) { minLines = 3; maxLines = 8; typeface = Typeface.MONOSPACE; textSize = 14f } else isSingleLine = true
    }

    private fun wrap(v: View): View = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v)
    }
}
