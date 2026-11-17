package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** v0.1.25 (Anoop, 12 Nov 2026: "clean it up first"): the weak spots from the v0.1.24 screenshots. */
@RunWith(RobolectricTestRunner::class)
class NoteScreenV0125Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        for (f in listOf("settings", "state", "secrets", NoteActivity.WHOLE_PREFS)) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
    }

    @After fun reset() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        InsightsMaker.testProviders = null
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun open(id: Long) = Robolectric.buildActivity(NoteActivity::class.java,
        android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()
    private fun card(a: NoteActivity) = NoteActivity::class.java.getDeclaredField("insights").apply { isAccessible = true }.get(a) as InsightsCard

    private val long = (1..12).joinToString("\n\n") { "Paragraph $it about the walk, the review on Thursday and a quiet dinner at home with old songs." }
    private fun item(lang: String) = Insights.Item(lang, listOf("A walk", "Worry about the review", "A quiet dinner"), "Hopeful", 68,
        "anxious, then settled", listOf("Low", "Steady", "Upbeat"), listOf(Insights.Shift(Insights.UP, "Relief", "Priya's tip")))

    // 1. no other app's name in what the user reads
    @Test fun noVionInSettingsOrStrings() {
        val prefs = Prefs::class.java.declaredFields.filter { Pref::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(Prefs) as Pref }
        assertTrue(prefs.none { "vion" in (it.title + it.summary).lowercase() })
        val strings = R.string::class.java.fields.map { ctx.getString(it.getInt(null)) }
        assertTrue(strings.none { "vion" in it.lowercase() })
    }

    // 2. Short notes: the same compact line as Long, on one line
    @Test fun shortNoteDateLineIsCompactLikeLong() {
        val id = NoteStore.get(ctx).add("Pick up oat milk and coriander on the way home", NoteSchema.SHORT)
        val a = open(id)
        val date = a.findViewById<TextView>(R.id.date)
        assertTrue(date.text.toString(), Regex("^Today · .+ · 10 words$").matches(date.text))
        assertEquals(1, date.maxLines)
    }

    // 3. the hint says each thing once
    @Test fun hintsAreShortAndSayEditOnce() {
        for (s in listOf(ctx.getString(R.string.read_hint), ctx.getString(R.string.read_hint_on))) {
            assertEquals(s, 1, Regex("edit").findAll(s).count())
            assertTrue(s, s.length <= 45)
        }
    }

    // 4. a "Summary ↓" chip near the top while the card is out of sight
    @Config(qualifiers = "w411dp-h800dp")
    @Test fun summaryChipScrollsToTheCardAndGoesOnceItIsInView() {
        val s = NoteStore.get(ctx)
        val id = s.add(long, NoteSchema.LONG)
        s.setInsights(id, Insights.Saved(Insights.hash(long), mapOf("en" to item("en"))).toJson(), "en")
        ctx.setTranslationOn(false)
        val a = open(id)
        ShadowLooper.idleMainLooper()
        val jump = a.findViewById<TextView>(R.id.insights_jump)
        assertEquals("card below the screen", View.VISIBLE, jump.visibility)
        jump.performClick(); ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        val scroll = a.findViewById<ScrollView>(R.id.note_scroll)
        assertTrue("scrolled to the card", scroll.scrollY > 0)
        scroll.scrollTo(0, scroll.getChildAt(0).height); ShadowLooper.idleMainLooper()
        assertEquals("card in view: no chip", View.GONE, jump.visibility)
    }

    @Test fun noSummaryChipOnShortNotes() {
        val a = open(NoteStore.get(ctx).add("Short one", NoteSchema.SHORT))
        assertEquals(View.GONE, a.findViewById<View>(R.id.insights_jump).visibility)
    }

    // 5. the language chip says what it does
    @Test fun languageChipSaysShowInLanguage() {
        val s = NoteStore.get(ctx)
        val id = s.add(long, NoteSchema.LONG)
        s.setInsights(id, Insights.Saved(Insights.hash(long), mapOf("en" to item("en"), "zh" to item("zh"))).toJson(), "zh")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id)
        val slot = a.findViewById<View>(R.id.insights_slot)
        assertTrue(texts(slot).toString(), "Show in English" in texts(slot) && "Original" !in texts(slot))
        all(slot).first { it is TextView && it.text == "Show in English" }.performClick()
        assertTrue(texts(slot).toString(), "Show in 中文" in texts(slot))
    }

    // 6. one header for wide and narrow: INSIGHTS and Copy (no extra caption on wide screens)
    @Config(qualifiers = "w900dp-h800dp")
    @Test fun wideHeaderHasNoExtraCaption() {
        val s = NoteStore.get(ctx)
        val id = s.add(long, NoteSchema.LONG)
        s.setInsights(id, Insights.Saved(Insights.hash(long), mapOf("en" to item("en"))).toJson(), "en")
        ctx.setTranslationOn(false)
        val t = texts(open(id).findViewById(R.id.insights_slot))
        assertTrue(t.toString(), "INSIGHTS" in t && "Summary and mood" !in t)
    }

    // 7 + 8. loading has a spinner; failure is readable and has a clear Try again
    @Test fun loadingSpinsAndFailureIsReadableWithTryAgain() {
        val s = NoteStore.get(ctx)
        val id = s.add(long, NoteSchema.LONG)
        s.setInsights(id, Insights.Saved(Insights.hash(long), mapOf("en" to item("en"))).toJson(), "en")
        val a = open(id)
        val c = card(a)
        var retried = 0
        c.loading()
        val slot = a.findViewById<View>(R.id.insights_slot)
        assertTrue(all(slot).any { it is ProgressBar && it.isShown })
        assertTrue(InsightsCard.LOADING in texts(slot))
        c.failed("no answer in 30 s")
        val why = all(slot).filterIsInstance<TextView>().first { it.text.startsWith("Why:") }
        assertEquals("normal contrast", ctx.getColor(R.color.text), why.currentTextColor)
        assertTrue(why.textSize >= 14f * ctx.resources.displayMetrics.scaledDensity - 0.5f)
        assertTrue("Try again" in texts(slot))
        assertFalse(all(slot).any { it is ProgressBar && it.isShown })
    }

    // 9. "Translate into": Chinese on a fresh install; upgrades keep what they had
    @Test fun translateIntoIsChineseOnFreshInstallsOnly() {
        for (f in listOf("settings", "state")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        assertEquals("fresh", "zh", ctx.translateInto())
        for (f in listOf("settings", "state")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putInt("settings_version", 8).commit()
        assertEquals("upgrade that never picked one keeps Spanish", "es", ctx.translateInto())
        for (f in listOf("settings", "state")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putInt("settings_version", 8).commit()
        ctx.getSharedPreferences("settings", 0).edit().putString(Prefs.TRANSLATE_INTO.key, "ml").commit()
        assertEquals("a chosen language stays", "ml", ctx.translateInto())
        assertTrue(TranslationRegistry.choices().any { it.first == "zh" && it.second == "Chinese (Simplified)" })
    }

    @Test fun dictationLineSaysWhatIsNeeded() {
        val t = texts(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView)
        assertTrue(t.toString(), t.any { SpeechSlots.NO_ENGINE in it } && t.none { "Not set up yet" in it })
    }
}
