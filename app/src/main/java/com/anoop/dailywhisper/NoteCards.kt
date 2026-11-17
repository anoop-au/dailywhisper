package com.anoop.dailywhisper

import android.app.Activity
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.view.View
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Note cards and date labels shared by the feed, search, Archive and Deleted. */
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
    }
}

fun Activity.timeOf(ms: Long): String = DateFormat.getTimeFormat(this).format(Date(ms))

/** Words, counting Chinese / Japanese characters two to a word (as Insights does), so a Chinese note isn't "1 word". */
fun wordCount(text: String): Int = Insights.words(text)

/** Spoken duration: "18 min", "3:40", or "0:12". */
fun formatDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    return when {
        m >= 60 -> "${m / 60}h ${m % 60} min"
        m > 0 && s == 0 -> "$m min"
        m > 0 -> "%d:%02d".format(m, s)
        else -> "0:%02d".format(s)
    }
}

/**
 * Short: "10:32" or "10:32 · edited". Long: "Today · 3:40 pm · 1,240 words · 18 min"; [inFeed] (the one Home feed, under
 * a day header, v0.1.27): "Session · 3:40 pm · 1,240 words · 18 min".
 */
fun Activity.cardMeta(n: Note, inFeed: Boolean = false): String {
    if (n.kind == NoteSchema.LONG) {
        val words = wordCount(n.text)
        val wordBit = java.text.NumberFormat.getIntegerInstance().format(words) + if (words == 1) " word" else " words"
        val head = if (inFeed) "Session" else dayLabel(n.created.toLocalDate())
        var s = "$head · ${timeOf(n.updated)} · $wordBit"
        if (n.durationMs > 0) s += " · ${formatDuration(n.durationMs)}"
        if (n.status == NoteSchema.ARCHIVED) s += " · archived"
        return s
    }
    var s = timeOf(n.created)
    if (n.updated - n.created > 60_000) s += " · edited"
    if (n.status == NoteSchema.ARCHIVED) s += " · archived"
    return s
}

fun Activity.dayHeader(parent: ViewGroup, day: LocalDate): View = sectionHeader(parent, dayLabel(day))

/** A section label (v0.1.28): small letter-spaced caps, an optional count pill, and a hairline to the right. */
fun Activity.sectionHeader(parent: ViewGroup, label: String, count: Int? = null): View {
    val v = LayoutInflater.from(this).inflate(R.layout.item_header, parent, false)
    v.findViewById<TextView>(R.id.label).text = label
    v.findViewById<TextView>(R.id.count).apply {
        if (count == null) visibility = View.GONE else {
            visibility = View.VISIBLE; text = count.toString()
            contentDescription = if (count == 1) "1 note" else "$count notes"
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(getColor(R.color.pill_bg)); setStroke(maxOf(1, dp(1) / 2), getColor(R.color.pill_stroke))
            }
        }
    }
    v.contentDescription = label + (count?.let { ", $it" } ?: "")
    return v
}

/**
 * The line under a note row (v0.1.29, Settings → Advanced → Home → Note dividers): a thin gold rule that fades at both
 * ends with a tiny centred diamond (default), the plain hairline, or nothing.
 */
fun Activity.styleDivider(v: View) {
    val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
    when (settings()[Prefs.NOTE_DIVIDER]) {
        HomeFeed.DIVIDER_NONE -> { v.visibility = View.INVISIBLE; lp.height = 1 }
        HomeFeed.DIVIDER_HAIRLINE -> { v.visibility = View.VISIBLE; lp.height = 1; v.setBackgroundColor(getColor(R.color.hairline)) }
        else -> {
            val night = (this as? ThemedActivity)?.isNight ?: false
            v.visibility = View.VISIBLE; lp.height = dp(7)
            lp.topMargin = lp.topMargin - dp(3) // keep the row rhythm: the rule's box is taller than the line
            v.background = GoldRule(getColor(R.color.wordmark_gold), lineWidthPx = resources.displayMetrics.density * 0.8f,
                diamondPx = dp(5).toFloat(), gapPx = dp(5).toFloat(),
                lineAlpha = if (night) 150 else 155, diamondAlpha = if (night) 205 else 210)
        }
    }
    v.layoutParams = lp
}

/**
 * One note row (v0.1.28): serif text, a small meta line, a hairline under it; no card. A highlight shows as a dot in its
 * colour before the time. The text falls back to Noto Sans Malayalam and the phone's fonts (Chinese…).
 */
fun Activity.noteCard(parent: ViewGroup, n: Note, textSize: Float, lines: Int, meta: String): View {
    val card = LayoutInflater.from(this).inflate(R.layout.item_note, parent, false)
    card.background = cardBackground(n.highlight)
    card.findViewById<TextView>(R.id.text).apply {
        text = HomeFeed.preview(n.text) // blank lines collapse; long pasted docs stay within [lines]
        maxLines = lines
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        typeface = DwFonts.serif(this@noteCard)
    }
    card.findViewById<TextView>(R.id.time).text = meta
    styleDivider(card.findViewById(R.id.divider))
    val hl = Highlight.of(n.highlight)
    card.findViewById<View>(R.id.hl_dot).apply {
        visibility = if (hl == null) View.GONE else View.VISIBLE
        if (hl != null) {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(hl.swatch) }
            contentDescription = "Highlighted ${hl.name}"
        }
    }
    return card
}
