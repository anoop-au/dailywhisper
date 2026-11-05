package com.anoop.dailywhisper

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Note highlight colours: a soft card tint for light and dark, plus a stronger swatch for pickers and icons. */
object Highlight {
    data class Colour(val key: String, val name: String, val swatch: Int, val light: Int, val dark: Int)

    val PALETTE = listOf(
        Colour("amber", "Amber", 0xFFAD7C2F.toInt(), 0xFFF6E6CB.toInt(), 0xFF3C2A0E.toInt()),
        Colour("rose", "Rose", 0xFFD9776E.toInt(), 0xFFF5DAD6.toInt(), 0xFF482A27.toInt()),
        Colour("sage", "Sage", 0xFF6E9A72.toInt(), 0xFFDCE8D8.toInt(), 0xFF2B3B2D.toInt()),
        Colour("sky", "Sky", 0xFF5E92C2.toInt(), 0xFFD7E5F1.toInt(), 0xFF223344.toInt()),
        Colour("lavender", "Lavender", 0xFF9A82C9.toInt(), 0xFFE4DCF1.toInt(), 0xFF352D47.toInt()),
    )
    const val DEFAULT = "amber"

    fun of(key: String?): Colour? = PALETTE.firstOrNull { it.key == key }
}

/** Card background: the normal card colour, or the highlight tint (light/dark aware), with the usual ripple. */
fun Activity.cardBackground(highlight: String?): Drawable {
    val night = (this as? ThemedActivity)?.isNight ?: false
    val fill = Highlight.of(highlight)?.let { if (night) it.dark else it.light } ?: getColor(R.color.card_80)
    val r = dp(16).toFloat()
    val shape = GradientDrawable().apply {
        cornerRadius = r
        setColor(fill)
        setStroke(dp(1), getColor(if (highlight != null) R.color.gilt else R.color.outline_60))
    }
    val mask = GradientDrawable().apply { cornerRadius = r; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), shape, mask)
}

/**
 * Small palette picker: a row of colour dots (current one ringed) and, if [allowNone], a "None" option.
 * Calls [onPick] with a palette key, or null for None.
 */
fun Activity.pickHighlight(title: String, current: String?, allowNone: Boolean, onPick: (String?) -> Unit) {
    var dialog: AlertDialog? = null
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(8)) }
    fun dot(fill: Int, selected: Boolean, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), 0, dp(6), 0)
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(fill)
                if (selected) setStroke(dp(3), getColor(R.color.text)) else setStroke(dp(1), getColor(R.color.outline))
            }
            contentDescription = label
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(getColor(R.color.text2)); setPadding(0, dp(4), 0, 0) })
        setOnClickListener { onClick(); dialog?.dismiss() }
    }
    if (allowNone) row.addView(dot(getColor(R.color.card), current == null, "None") { onPick(null) })
    Highlight.PALETTE.forEach { c -> row.addView(dot(c.swatch, c.key == current, c.name) { onPick(c.key) }) }
    dialog = AlertDialog.Builder(this).setTitle(title)
        .setView(android.widget.HorizontalScrollView(this).apply { addView(row) })
        .setNegativeButton("Cancel", null).show()
}
