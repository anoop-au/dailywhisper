package com.anoop.dailywhisper

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.1.28 Home shape (pure): Latest, today by time of day, then earlier days. */
class HomeFeedTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 11, 17)
    private val b = HomeFeed.Bounds()
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private var nextId = 1L
    private fun note(t: Long) = Note(nextId++, "n$t", t, t)

    @Test fun defaultBoundsMatchTheBrief() {
        assertEquals(HomeFeed.Bounds(5, 12, 17, 21), b)
        assertEquals(HomeFeed.Part.NIGHT, HomeFeed.part(4, b))
        assertEquals(HomeFeed.Part.MORNING, HomeFeed.part(5, b))
        assertEquals(HomeFeed.Part.MORNING, HomeFeed.part(11, b))
        assertEquals(HomeFeed.Part.AFTERNOON, HomeFeed.part(12, b))
        assertEquals(HomeFeed.Part.EVENING, HomeFeed.part(17, b))
        assertEquals(HomeFeed.Part.EVENING, HomeFeed.part(20, b))
        assertEquals(HomeFeed.Part.NIGHT, HomeFeed.part(21, b))
        assertEquals(HomeFeed.Part.NIGHT, HomeFeed.part(0, b))
    }

    @Test fun customAndOutOfOrderBoundsStillAnswer() {
        val c = HomeFeed.Bounds(morning = 6, afternoon = 13, evening = 18, night = 23)
        assertEquals(HomeFeed.Part.NIGHT, HomeFeed.part(5, c))
        assertEquals(HomeFeed.Part.AFTERNOON, HomeFeed.part(17, c))
        val odd = HomeFeed.Bounds(morning = 12, afternoon = 5, evening = 17, night = 21) // nonsense, but no crash
        for (h in 0..23) HomeFeed.part(h, odd)
        assertEquals(HomeFeed.Part.AFTERNOON, HomeFeed.part(6, odd))
    }

    @Test fun labelsUseTheWordOrJustThePart() {
        assertEquals("Morning Whispers", HomeFeed.partLabel(HomeFeed.Part.MORNING, HomeFeed.DEFAULT_WORD))
        assertEquals("Night notes", HomeFeed.partLabel(HomeFeed.Part.NIGHT, " notes "))
        assertEquals("Evening", HomeFeed.partLabel(HomeFeed.Part.EVENING, "  "))
    }

    @Test fun latestIsNotRepeatedAndGroupsFollowTheDay() {
        val notes = listOf(
            note(at(today, 19, 36)), note(at(today, 18, 5)), note(at(today, 13, 10)), note(at(today, 8, 20)),
            note(at(today, 8, 19)), note(at(today, 1, 30)), note(at(today.minusDays(1), 22)), note(at(today.minusDays(3), 9)),
        )
        val f = HomeFeed.build(notes, today, b, "Whispers", showLatest = true, zone = zone, newestFirst = false)
        assertEquals(notes[0], f.latest)
        assertEquals(listOf("Morning Whispers", "Afternoon Whispers", "Evening Whispers", "Night Whispers", "Yesterday",
            "Saturday · 14 November"), f.groups.map { it.label })
        assertEquals(listOf(notes[3], notes[4]), f.groups[0].notes) // newest first inside a group
        assertEquals(listOf(notes[1]), f.groups[2].notes)            // the latest isn't repeated
        assertEquals(listOf(notes[5]), f.groups[3].notes)            // the small hours count as Night
        assertEquals(notes.size - 1, f.groups.sumOf { it.notes.size })
    }

    @Test fun newestGroupFirstByDefault() {
        val notes = listOf(
            note(at(today, 22, 10)), note(at(today, 21, 30)), note(at(today, 19, 36)), note(at(today, 13, 10)),
            note(at(today, 8, 20)), note(at(today, 8, 19)), note(at(today.minusDays(1), 22)),
        )
        val f = HomeFeed.build(notes, today, b, "Whispers", showLatest = true, zone = zone)
        assertEquals(notes[0], f.latest)
        assertEquals(listOf("Night Whispers", "Evening Whispers", "Afternoon Whispers", "Morning Whispers", "Yesterday"),
            f.groups.map { it.label })
        assertEquals(listOf(notes[1]), f.groups[0].notes)
        assertEquals(listOf(notes[4], notes[5]), f.groups[3].notes) // newest first inside a group, either order
        // Small hours only (no late night yet): Night holds the oldest notes of the day, so it comes last.
        val early = listOf(note(at(today, 9)), note(at(today, 2)))
        assertEquals(listOf("Morning Whispers", "Night Whispers"),
            HomeFeed.build(early, today, b, "Whispers", showLatest = false, zone = zone).groups.map { it.label })
        assertEquals(listOf("Morning Whispers", "Night Whispers"),
            HomeFeed.build(early, today, b, "Whispers", showLatest = false, zone = zone, newestFirst = false).groups.map { it.label })
    }

    @Test fun withoutLatestEveryNoteIsInAGroup() {
        val notes = listOf(note(at(today, 19)), note(at(today, 9)))
        val f = HomeFeed.build(notes, today, b, "Whispers", showLatest = false, zone = zone, newestFirst = false)
        assertNull(f.latest)
        assertEquals(listOf("Morning Whispers", "Evening Whispers"), f.groups.map { it.label })
        assertEquals(listOf("Evening Whispers", "Morning Whispers"),
            HomeFeed.build(notes, today, b, "Whispers", showLatest = false, zone = zone).groups.map { it.label })
        assertEquals(HomeFeed.Feed(null, emptyList()), HomeFeed.build(emptyList(), today, b, "Whispers", true, zone))
    }

    @Test fun dayLabelsDateLineAndAgo() {
        assertEquals("Today", HomeFeed.dayLabel(today, today))
        assertEquals("Yesterday", HomeFeed.dayLabel(today.minusDays(1), today))
        assertEquals("Sunday · 15 November", HomeFeed.dayLabel(today.minusDays(2), today))
        assertEquals("Wed · 17 Dec 2025", HomeFeed.dayLabel(LocalDate.of(2025, 12, 17), today))
        assertEquals("Tuesday · 17 November", HomeFeed.dateLine(today))
        val now = 10_000_000_000L
        assertEquals("just now", HomeFeed.ago(now - 20_000, now))
        assertEquals("just now", HomeFeed.ago(now + 5_000, now)) // a clock a little ahead
        assertEquals("12 min ago", HomeFeed.ago(now - 12 * 60_000, now))
        assertEquals("3 h ago", HomeFeed.ago(now - 3 * 3600_000L - 60_000, now))
        assertNull(HomeFeed.ago(now - 13 * 3600_000L, now))
    }
}
