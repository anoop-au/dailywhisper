package com.anoop.dailywhisper

import android.app.Activity
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
        }
        return row
    }
}
