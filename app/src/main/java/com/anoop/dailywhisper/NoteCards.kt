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

fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

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

/** Short: "10:32" or "10:32 · edited". Long: "Today · 3:40 pm · 1,240 words · 18 min". */
fun Activity.cardMeta(n: Note): String {
    if (n.kind == NoteSchema.LONG) {
        val words = wordCount(n.text)
        val wordBit = java.text.NumberFormat.getIntegerInstance().format(words) + if (words == 1) " word" else " words"
        var s = "${dayLabel(n.created.toLocalDate())}  ·  ${timeOf(n.updated)}  ·  $wordBit"
        if (n.durationMs > 0) s += "  ·  ${formatDuration(n.durationMs)}"
        if (n.status == NoteSchema.ARCHIVED) s += "  ·  archived"
        return s
    }
    var s = timeOf(n.created)
    if (n.updated - n.created > 60_000) s += "  ·  edited"
    if (n.status == NoteSchema.ARCHIVED) s += "  ·  archived"
    return s
}

fun Activity.dayHeader(parent: ViewGroup, day: LocalDate): View =
    (LayoutInflater.from(this).inflate(R.layout.item_header, parent, false) as TextView).apply { text = dayLabel(day) }

/** One card: the note text (highlight-tinted if set) and a small meta line. */
fun Activity.noteCard(parent: ViewGroup, n: Note, textSize: Float, lines: Int, meta: String): View {
    val card = LayoutInflater.from(this).inflate(R.layout.item_note, parent, false)
    card.background = cardBackground(n.highlight)
    card.findViewById<TextView>(R.id.text).apply {
        text = n.text
        maxLines = lines
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
    }
    card.findViewById<TextView>(R.id.time).text = meta
    card.findViewById<TextView>(R.id.text).typeface = DwFonts.regular(this)
    return card
}
