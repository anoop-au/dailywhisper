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

/** "10:32", "10:32 · edited", plus " · archived" for archived notes shown in search. */
fun Activity.cardMeta(n: Note): String {
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
    return card
}
