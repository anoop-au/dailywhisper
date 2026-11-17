package com.anoop.dailywhisper

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** The quiet gold frame (v0.1.23): list cards, the Insights card and the note's reading well; not full gilt by default. */
@RunWith(RobolectricTestRunner::class)
class GoldEdgeTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
    }

    @After fun reset() { NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null) }

    private fun frame(v: View): GoldEdge = when (val b = v.background) {
        is GoldEdge -> b
        is RippleDrawable -> (0 until b.numberOfLayers).map { b.getDrawable(it) }.filterIsInstance<GoldEdge>().single()
        else -> throw AssertionError("no gold frame: $b")
    }

    @Test fun tokensAreMutedGilt() {
        for (id in listOf(R.color.gilt_edge, R.color.gilt_glow)) {
            val c = ctx.getColor(id)
            assertEquals("same gold as gilt", ctx.getColor(R.color.gilt) and 0xFFFFFF, c and 0xFFFFFF)
            assertTrue("translucent, not full gilt", (c ushr 24) in 0x1A..0x73)
        }
        assertTrue("the halo is softer than the rim", (ctx.getColor(R.color.gilt_glow) ushr 24) < (ctx.getColor(R.color.gilt_edge) ushr 24))
    }

    /** v0.1.28 (Anoop's chosen design): notes are rows on the page, not framed cards; a highlight is a dot in its colour. */
    @Test fun listRowsAreUnframedAndHighlightsShowADot() {
        val store = NoteStore.get(ctx)
        val lit = store.add("highlighted note"); Thread.sleep(2); val plain = store.add("plain note"); Thread.sleep(2)
        store.add("newest note (the Latest quote)"); store.setHighlight(lit, "amber")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get(); ShadowLooper.idleMainLooper()
        val box = main.findViewById<ViewGroup>(R.id.notes)
        val cards = (0 until box.childCount).map { box.getChildAt(it) }.filter { it.tag is Long }.associateBy { it.tag as Long }
        for (c in cards.values) assertFalse("no gold frame", all(c).any { it.background is GoldEdge })
        assertEquals(View.GONE, cards.getValue(plain).findViewById<View>(R.id.hl_dot).visibility)
        val dot = cards.getValue(lit).findViewById<View>(R.id.hl_dot)
        assertEquals(View.VISIBLE, dot.visibility)
        assertEquals(Highlight.of("amber")!!.swatch, (dot.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
    }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Test fun noteScreenIsUnframedButInsightsCardIsFramed() {
        val store = NoteStore.get(ctx)
        val long = (1..80).joinToString(" ") { "word$it" }
        val id = store.add(long, NoteSchema.LONG)
        val en = Insights.parse("""{"summary":["One.","Two."],"mood":"Calm","tone":50}""", "en")!!
        store.setInsights(id, Insights.Saved(Insights.hash(long), emptyMap()).with(en, long).toJson(), "en")
        ctx.setTranslationOn(false)
        val a = Robolectric.buildActivity(NoteActivity::class.java,
            android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()
        val well = a.findViewById<View>(R.id.note_pair)
        assertNull("v0.1.28: the note sits on the page, no framed well", well.background)
        assertEquals(a.dp(12), (well.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
        assertEquals("no form-field look inside", null, a.findViewById<View>(R.id.edit).background)
        val faces = all(a.findViewById(R.id.insights_slot)).filter { it.background is GoldEdge }
        assertEquals("Summary and Sentiment faces", 2, faces.size)
        assertTrue(faces.all { (it.background as GoldEdge).edgeColor == ctx.getColor(R.color.gilt_edge) })
    }
}
