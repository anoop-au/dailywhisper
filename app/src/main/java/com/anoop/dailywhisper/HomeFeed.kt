package com.anoop.dailywhisper

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home's shape (v0.1.28, Anoop's chosen design): the newest note on its own as "Latest", then today's other notes grouped
 * by time of day ("Morning Whispers", then Afternoon, Evening, Night; newest first inside each), then Yesterday and older
 * days by date. Pure (tests).
 */
object HomeFeed {
    const val DEFAULT_WORD = "Whispers"

    enum class Part(val label: String) { MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), NIGHT("Night") }

    /** Where each part starts (hour 0-23). Night runs from [night] to [morning] the next day. */
    data class Bounds(val morning: Int = 5, val afternoon: Int = 12, val evening: Int = 17, val night: Int = 21)

    /** The part of the day [hour] falls in. Bounds out of order still give an answer: the latest start before it wins. */
    fun part(hour: Int, b: Bounds): Part {
        val starts = listOf(Part.MORNING to b.morning, Part.AFTERNOON to b.afternoon, Part.EVENING to b.evening, Part.NIGHT to b.night)
            .sortedBy { it.second }
        return starts.lastOrNull { it.second <= hour }?.first ?: starts.last().first // before the first start: the night before
    }

    /** "Morning Whispers", or just "Morning" when [word] is blank. */
    fun partLabel(p: Part, word: String): String = if (word.isBlank()) p.label else "${p.label} ${word.trim()}"

    data class Group(val label: String, val notes: List<Note>)
    data class Feed(val latest: Note?, val groups: List<Group>)

    /**
     * [notes] newest first. With [showLatest] the newest note is the quote and isn't repeated below. Today's notes are
     * grouped by part of the day; earlier days by date ("Yesterday", "Monday · 16 November").
     */
    fun build(notes: List<Note>, today: LocalDate, b: Bounds, word: String, showLatest: Boolean,
              zone: ZoneId = ZoneId.systemDefault()): Feed {
        val latest = if (showLatest) notes.firstOrNull() else null
        val rest = if (latest != null) notes.drop(1) else notes
        // Today: one group per part of the day, in the day's order (Morning, Afternoon, Evening, Night; the small hours
        // count as Night), newest first inside each. Earlier days follow, one group per day, newest day first.
        val todayParts = LinkedHashMap<Part, ArrayList<Note>>()
        val older = ArrayList<Group>()
        var key: String? = null
        var bucket = ArrayList<Note>()
        for (n in rest) {
            val t = Instant.ofEpochMilli(n.created).atZone(zone)
            if (t.toLocalDate() == today) { todayParts.getOrPut(part(t.hour, b)) { ArrayList() } += n; continue }
            val label = dayLabel(t.toLocalDate(), today)
            if (label != key) {
                if (key != null) older += Group(key, bucket)
                key = label; bucket = ArrayList()
            }
            bucket += n
        }
        if (key != null) older += Group(key, bucket)
        val groups = Part.values().mapNotNull { p -> todayParts[p]?.let { Group(partLabel(p, word), it) } } + older
        return Feed(latest, groups)
    }

    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE · d MMMM", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("EEE · d MMM yyyy", Locale.getDefault()))
    }

    /** The line under the wordmark: "TUESDAY · 17 NOVEMBER" (upper-cased by the view). */
    fun dateLine(today: LocalDate): String = today.format(DateTimeFormatter.ofPattern("EEEE · d MMMM", Locale.getDefault()))

    /** "just now", "12 min ago", "3 h ago", else null (the time alone is enough). */
    fun ago(ms: Long, now: Long): String? {
        val d = (now - ms) / 60_000
        return when {
            d < 1 -> "just now"
            d < 60 -> "$d min ago"
            d < 12 * 60 -> "${d / 60} h ago"
            else -> null
        }
    }
}
