package com.anoop.dailywhisper

import android.app.Activity
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
class SettingsActivity : Activity() {

    private val prefs by lazy { settings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
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
            if (items.contains(Prefs.STT_URL)) col.addView(Button(this).apply {
                text = "Use Deepgram defaults"; isAllCaps = false; setTextColor(getColor(R.color.accent))
                setBackgroundResource(R.drawable.bg_icon_btn)
                setOnClickListener {
                    prefs.edit().apply { LIVE_DICTATION_RESETTABLE.forEach { remove(it.key) } }.apply()
                    android.widget.Toast.makeText(this@SettingsActivity,
                        "Deepgram defaults restored. Your API key is unchanged.", android.widget.Toast.LENGTH_SHORT).show()
                    build()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        col.addView(Button(this).apply {
            text = "Reset to defaults"; isAllCaps = false; setTextColor(getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_icon_btn)
            setOnClickListener { prefs.edit().clear().apply(); build() }
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
                fun show() { value.text = p.options.first { it.first == prefs[p] }.second }
                show()
                row.setOnClickListener {
                    val idx = p.options.indexOfFirst { it.first == prefs[p] }
                    AlertDialog.Builder(this).setTitle(p.title)
                        .setSingleChoiceItems(p.options.map { it.second }.toTypedArray(), idx) { d, which ->
                            prefs.edit().putString(p.key, p.options[which].first).apply(); show(); d.dismiss()
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
