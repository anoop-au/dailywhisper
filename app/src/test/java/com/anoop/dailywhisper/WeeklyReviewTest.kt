package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate
import java.time.ZoneId

/** Private Weekly Review (Daily Whisper Plus, v0.1.27): made on the phone, online only when switched on, kept in reviews.db. */
@RunWith(RobolectricTestRunner::class)
class WeeklyReviewTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ReviewStore.reset()
        ctx.deleteDatabase("notes.db"); ctx.deleteDatabase(ReviewStore.NAME)
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        InsightsMaker.testProviders = null
    }

    @After fun reset() {
        InsightsMaker.testProviders = null; ReviewStore.reset()
        Plus.source = { LocalPlus(it.applicationContext) }
    }

    private fun at(d: LocalDate, hour: Int = 10) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun note(text: String, d: LocalDate, id: Long = 1, insights: String? = null) =
        Note(id, text, at(d), at(d), insights = insights)

    private val mon = LocalDate.of(2026, 11, 2)
    private val week = listOf(
        note("Work was stressful and tiring. The garden project needs to move. Not sure about the budget.", mon, 1),
        note("Tired again after work. I should call Mum about the garden.", mon.plusDays(1), 2),
        note("A calm walk. The garden is coming along and I feel hopeful.", mon.plusDays(4), 3),
        note("Lovely dinner with friends, happy and grateful. What should the garden look like by spring?", mon.plusDays(6), 4),
    )

    @Test fun weekEndsOnTheReviewDay() {
        val wed = LocalDate.of(2026, 11, 11)
        assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 8), WeeklyReview.week(wed, 7))
        assertEquals("on the review day itself, that week", LocalDate.of(2026, 11, 9) to LocalDate.of(2026, 11, 15),
            WeeklyReview.week(LocalDate.of(2026, 11, 15), 7))
        assertEquals(LocalDate.of(2026, 11, 5) to LocalDate.of(2026, 11, 11), WeeklyReview.week(wed, 3))
    }

    @Test fun onlyTheWeeksNotes() {
        val outside = note("last week", mon.minusDays(1), 9)
        val (s, e) = mon to mon.plusDays(6)
        assertEquals(week.map { it.id }, WeeklyReview.inWeek(week + outside, s, e, zone).map { it.id })
    }

    @Test fun themesAreWordsInTheMostNotes() {
        val t = WeeklyReview.themes(week.map { it.text }, 5)
        assertEquals("Garden", t.first())
        assertTrue(t.toString(), "Work" in t)
        assertTrue("common words left out: $t", t.none { it.lowercase() in setOf("about", "again", "should", "the") })
        assertTrue(t.size <= 5)
        val zh = WeeklyReview.themes(listOf("今天花园里很安静", "花园的花开了", "工作很累"), 3)
        assertEquals("花园", zh.first())
    }

    @Test fun moodFromInsightsElseWords() {
        assertTrue(WeeklyReview.lexiconTone("happy and grateful, a lovely day")!! > 60)
        assertTrue(WeeklyReview.lexiconTone("stressed, tired and anxious")!! < 40)
        assertNull(WeeklyReview.lexiconTone("the bus came at nine"))
        val text = "Some long session text about the week"
        val item = Insights.Item("en", listOf("a"), "Hopeful", 82, "", emptyList(), emptyList())
        val saved = Insights.Saved(Insights.hash(text), mapOf("en" to item)).toJson()
        assertEquals("a Long note's Insights tone wins", 82, WeeklyReview.tone(Note(1, text, 0, 0, insights = saved)))
        assertEquals(listOf("Hopeful"), WeeklyReview.moodWords(listOf(Note(1, text, 0, 0, insights = saved))))
        val days = WeeklyReview.days(week, mon, zone)
        assertEquals(7, days.size)
        assertEquals(WeeklyReview.Trend.LIFTED, WeeklyReview.trend(days))
        assertEquals(null, days[2].tone) // Wednesday: no notes
    }

    @Test fun unfinishedThoughtsSoundOpenNewestFirst() {
        val u = WeeklyReview.unfinished(week, 4)
        assertEquals("What should the garden look like by spring?", u.first())
        assertTrue(u.toString(), u.contains("Not sure about the budget."))
        assertTrue(u.contains("The garden project needs to move."))
        assertFalse(u.any { it.startsWith("A calm walk") })
        assertEquals(2, WeeklyReview.unfinished(week, 2).size)
    }

    @Test fun promptsAreTwoToFourAndGentle() {
        val r = WeeklyReview.build(week, mon, mon.plusDays(6), WeeklyReview.Options(prompts = 3), 1L, zone)!!
        assertEquals(3, r.prompts.size)
        assertTrue(r.prompts.first().contains("Garden"))
        assertTrue(r.prompts.any { it.contains("lifted") })
        assertEquals(2, WeeklyReview.prompts(emptyList(), WeeklyReview.Trend.STEADY, emptyList(), 1).size)
        assertEquals(4, WeeklyReview.prompts(listOf("A", "B"), WeeklyReview.Trend.LOW, listOf("x"), 9).size)
        assertEquals(WeeklyReview.ON_PHONE, r.source)
        assertEquals(4, r.notes)
    }

    @Test fun tooFewNotesGivesNoReview() {
        assertNull(WeeklyReview.build(week.take(2), mon, mon.plusDays(6), WeeklyReview.Options(minNotes = 3), 1L, zone))
        assertNotNull(WeeklyReview.build(week.take(2), mon, mon.plusDays(6), WeeklyReview.Options(minNotes = 2), 1L, zone))
    }

    @Test fun reviewRoundTripsThroughJsonAndSqlite() {
        val r = WeeklyReview.build(week, mon, mon.plusDays(6), WeeklyReview.Options(), 42L, zone)!!
        assertEquals(r, WeeklyReview.load(r.toJson()))
        val store = ReviewStore.get(ctx)
        store.save(r)
        assertEquals(r, store.get(mon))
        store.save(r.copy(prompts = listOf("one", "two")))
        assertEquals("one row per week", listOf(mon), store.weeks())
        assertEquals(listOf("one", "two"), store.get(mon)!!.prompts)
        assertTrue(ctx.getDatabasePath(ReviewStore.NAME).exists())
        assertFalse("notes.db untouched", ctx.getDatabasePath("notes.db").exists())
    }

    @Test fun tunablesHaveDefaultsAndSettings() {
        val p = ctx.settings()
        assertEquals("7", p[Prefs.WR_DAY]); assertEquals(3, p[Prefs.WR_MIN_NOTES]); assertEquals(3, p[Prefs.WR_PROMPTS])
        assertEquals(2, Prefs.WR_PROMPTS.min); assertEquals(4, Prefs.WR_PROMPTS.max)
        assertEquals(5, p[Prefs.WR_THEMES]); assertEquals(4, p[Prefs.WR_UNFINISHED])
        assertFalse("cloud is off by default", p[Prefs.WR_CLOUD])
        assertFalse("Plus is off by default", p[Prefs.PLUS])
        val adv = Prefs.advanced.first { it.first == "Weekly review" }.second
        assertEquals(listOf(Prefs.WR_DAY, Prefs.WR_MIN_NOTES, Prefs.WR_PROMPTS, Prefs.WR_THEMES, Prefs.WR_UNFINISHED), adv)
        assertTrue(Prefs.WR_CLOUD in Prefs.essentials.first { it.first == Prefs.PRIVACY }.second)
    }

    private class Fake(val reply: String?) : ChatProvider {
        var calls = 0
        override val label = "Fake"
        override fun chatUsable(c: Context) = true
        override fun chat(c: Context, system: String, user: String, maxTokens: Int): String? { calls++; return reply }
    }

    @Test fun cloudNeedsPlusAndTheSwitch() {
        val fake = Fake("{}"); InsightsMaker.testProviders = listOf(fake)
        val m = WeeklyReviewMaker(ctx)
        assertFalse("off by default", m.canUseCloud())
        ctx.settings().edit().putBoolean(Prefs.WR_CLOUD.key, true).commit()
        assertFalse("needs Plus", m.canUseCloud())
        ctx.settings().edit().putBoolean(Prefs.PLUS.key, true).commit()
        assertTrue(m.canUseCloud())
        InsightsMaker.testProviders = emptyList()
        assertFalse("needs a service", m.canUseCloud())
    }

    @Test fun cloudReplyReplacesTheWordsButKeepsTheMood() {
        val base = WeeklyReview.build(week, mon, mon.plusDays(6), WeeklyReview.Options(), 1L, zone)!!
        val reply = "```json\n{\"themes\": [\"The garden\", \"Work strain\"], \"mood\": \"Heavy early, lighter by Sunday.\", " +
            "\"unfinished\": [\"The budget\"], \"prompts\": [\"What helped on Friday?\", \"What does spring look like?\", \"x\"]}\n```"
        val r = WeeklyReview.parseCloud(reply, base, WeeklyReview.Options(prompts = 2), 5L)!!
        assertEquals(listOf("The garden", "Work strain"), r.themes)
        assertEquals(2, r.prompts.size)
        assertEquals(base.days, r.days)
        assertEquals(WeeklyReview.ONLINE, r.source)
        assertNull(WeeklyReview.parseCloud("sorry", base, WeeklyReview.Options(), 5L))
        assertNull("too few prompts", WeeklyReview.parseCloud("{\"themes\":[\"a\"],\"prompts\":[\"one\"]}", base, WeeklyReview.Options(), 5L))
        val input = WeeklyReview.cloudInput(week, zone)
        assertTrue(input.startsWith("[Mon 2] Work was stressful"))
        assertTrue(WeeklyReview.cloudPrompt(WeeklyReview.Options()).contains("No advice"))
    }

    // ---- the screen ----

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun shown(a: android.app.Activity) = all(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun addWeek() {
        val s = NoteStore.get(ctx)
        val (start, _) = WeeklyReviewMaker(ctx).week()
        for ((i, n) in week.withIndex()) {
            val id = s.add(n.text)
            val t = at(start.plusDays(minOf(i * 2L, 6L)))
            NoteStore.get(ctx).writableDatabase.execSQL("UPDATE notes SET created = $t, updated = $t WHERE id = $id")
        }
    }

    @Test fun lockedWithoutPlus() {
        val a = Robolectric.buildActivity(WeeklyReviewActivity::class.java).setup().get()
        val t = shown(a)
        assertTrue(t.toString(), t.contains("Daily Whisper Plus") && t.contains("Try Plus on this phone"))
        assertFalse(t.contains("Key themes"))
        all(a.window.decorView).first { it is TextView && it.text == "Try Plus on this phone" }.performClick()
        assertTrue(ctx.settings()[Prefs.PLUS])
    }

    @Test fun withPlusShowsTheReviewMadeOnThePhoneAndSavesIt() {
        ctx.settings().edit().putBoolean(Prefs.PLUS.key, true).commit()
        addWeek()
        val fake = Fake("{}"); InsightsMaker.testProviders = listOf(fake)
        val a = Robolectric.buildActivity(WeeklyReviewActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val t = shown(a)
        for (s in listOf("KEY THEMES", "MOOD THIS WEEK", "UNFINISHED THOUGHTS", "GENTLE PROMPTS", "Garden", "Make again"))
            assertTrue("$s on $t", s in t)
        assertTrue(t.toString(), t.any { it.startsWith("Made on this phone · from 4 notes") })
        assertEquals("nothing sent with the cloud switch off", 0, fake.calls)
        assertNotNull(ReviewStore.get(ctx).get(WeeklyReviewMaker(ctx).week().first))
    }

    @Test fun tooFewNotesSaysHowMany() {
        ctx.settings().edit().putBoolean(Prefs.PLUS.key, true).commit()
        val t = shown(Robolectric.buildActivity(WeeklyReviewActivity::class.java).setup().get())
        assertTrue(t.toString(), t.contains("Not enough notes yet"))
        assertTrue(t.any { it.contains("at least 3 notes") })
    }

    @Test fun localEntitlementIsReplaceable() {
        assertFalse(Plus.entitlement(ctx).active)
        assertEquals("local", Plus.entitlement(ctx).source)
        Plus.source = { object : Entitlement { override val active = true; override val source = "store" } }
        assertTrue(Plus.entitlement(ctx).active)
    }

    @Test fun turningCloudOnAsksAndNoKeepsItOff() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val row = all(a.window.decorView).first { it.hasOnClickListeners() && it is ViewGroup &&
            all(it).filterIsInstance<TextView>().firstOrNull()?.text == "Weekly review online" }
        row.performClick(); ShadowLooper.idleMainLooper()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertTrue(d.isShowing)
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper()
        assertFalse(ctx.settings()[Prefs.WR_CLOUD])
        row.performClick(); ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(ctx.settings()[Prefs.WR_CLOUD])
    }
}
