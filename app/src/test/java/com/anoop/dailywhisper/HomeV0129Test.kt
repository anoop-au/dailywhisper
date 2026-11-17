package com.anoop.dailywhisper

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** v0.1.29: a thin gold divider between notes (a setting), and previews that stay short. */
@RunWith(RobolectricTestRunner::class)
class HomeV0129Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val settings get() = ctx.getSharedPreferences("settings", 0)

    @Before fun clean() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        settings.edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private val doc = "NIGHTMARE — AFTER DARK\n\n\n   Chapter one   \n\nThe house was quiet.\n\n\nThen the stairs creaked.\n\n" +
        (1..40).joinToString("\n\n") { "Line $it of the pasted document." }

    private fun rows(): List<View> {
        val s = NoteStore.get(ctx)
        s.add(doc); Thread.sleep(2); s.add("second note"); Thread.sleep(2); s.add("third note (the Latest quote)")
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get(); ShadowLooper.idleMainLooper()
        val box = m.findViewById<ViewGroup>(R.id.notes)
        return (0 until box.childCount).map { box.getChildAt(it) }.filter { it.tag is Long && it.findViewById<View>(R.id.divider) != null }
    }

    @Test fun previewDropsBlankLinesButNotTheNote() {
        assertEquals("NIGHTMARE — AFTER DARK\nChapter one\nThe house was quiet.\nThen the stairs creaked.",
            HomeFeed.preview(doc).lineSequence().take(4).joinToString("\n"))
        assertEquals("one line", HomeFeed.preview("  one line  "))
        assertEquals("", HomeFeed.preview("\n\n  \n"))
        val id = NoteStore.get(ctx).add(doc)
        rows()
        assertEquals("the saved note is untouched", doc, NoteStore.get(ctx).get(id)!!.text)
    }

    @Test fun rowsAreClampedToThePreviewLength() {
        val r = rows()
        val docRow = r.first { it.findViewById<TextView>(R.id.text).text.startsWith("NIGHTMARE") }
        val tv = docRow.findViewById<TextView>(R.id.text)
        assertEquals(3, tv.maxLines)
        assertTrue("no blank lines in the row", !tv.text.contains("\n\n"))
        settings.edit().putInt(Prefs.PREVIEW_LINES.key, 15).commit() // an old, longer value is clamped
        assertEquals(8, settings[Prefs.PREVIEW_LINES])
    }

    @Test fun goldDividerByDefault() {
        assertEquals(HomeFeed.DIVIDER_GOLD, Prefs.NOTE_DIVIDER.def)
        val r = rows()
        assertEquals(2, r.size)
        for (row in r) {
            val d = row.findViewById<View>(R.id.divider)
            assertEquals(View.VISIBLE, d.visibility)
            assertTrue(d.background is GoldRule)
        }
    }

    @Test fun dividerCanBePlainOrNone() {
        settings.edit().putString(Prefs.NOTE_DIVIDER.key, HomeFeed.DIVIDER_HAIRLINE).commit()
        rows().forEach { val d = it.findViewById<View>(R.id.divider)
            assertEquals(ctx.getColor(R.color.hairline), (d.background as ColorDrawable).color) }
        clean()
        settings.edit().putString(Prefs.NOTE_DIVIDER.key, HomeFeed.DIVIDER_NONE).commit()
        rows().forEach { assertEquals(View.INVISIBLE, it.findViewById<View>(R.id.divider).visibility) }
    }

    @Test fun goldRuleDraws() {
        val g = GoldRule(0xFFCFA261.toInt(), 2f, 10f, 10f, 120, 175)
        g.setBounds(0, 0, 400, 14)
        val bmp = android.graphics.Bitmap.createBitmap(400, 14, android.graphics.Bitmap.Config.ARGB_8888)
        g.draw(android.graphics.Canvas(bmp)) // no crash; pixel checks are left to the renders
        assertTrue(g.intrinsicHeight >= 10)
    }
}
