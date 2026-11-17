package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu

/** v0.1.27 Home: no Short/Long switch or chooser, Search + one ⋮, a session from the composer, one feed. */
@RunWith(RobolectricTestRunner::class)
class HomeV0127Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private fun home() = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun id(name: String) = ctx.resources.getIdentifier(name, "id", ctx.packageName)

    @Test fun headerHasOnlySearchAndOneMenu() {
        val main = home()
        for (gone in listOf("mode_row", "mode_short", "mode_long", "fab", "btn_theme", "btn_settings")) {
            val i = id(gone)
            assertTrue("$gone is gone", i == 0 || main.findViewById<View>(i) == null)
        }
        val header = main.findViewById<View>(R.id.btn_search).parent as ViewGroup
        val buttons = all(header).filterIsInstance<ImageButton>().filter { it.visibility == View.VISIBLE }
        assertEquals(listOf(R.id.btn_search, R.id.btn_more), buttons.map { it.id })
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.composer_bar).visibility)
        assertEquals(HomeFeed.dateLine(java.time.LocalDate.now()), main.findViewById<TextView>(R.id.subtitle).text.toString()) // v0.1.28: the date line
    }

    @Test fun noModeChooserOnFirstOpenAfterSetup() {
        val main = home()
        assertNull(shadowOf(main).nextStartedActivity)
        assertFalse(ctx.uiState().getBoolean(WritingMode.KEY_CHOSEN, false))
    }

    @Test fun overflowHasSessionReviewArchiveDeletedSettings() {
        val main = home()
        main.findViewById<View>(R.id.btn_more).performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(popup)
        val titles = (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() }
        assertEquals(listOf("Start a session", "Weekly review", "Archive", "Deleted", "Settings"), titles)
        fun pick(itemId: Int) = shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.findItem(itemId))
        pick(MainActivity.MORE_SETTINGS)
        assertEquals(SettingsActivity::class.java.name, shadowOf(main).nextStartedActivity.component!!.className)
        pick(MainActivity.MORE_REVIEW)
        assertEquals(WeeklyReviewActivity::class.java.name, shadowOf(main).nextStartedActivity.component!!.className)
        pick(MainActivity.MORE_SESSION)
        val next = shadowOf(main).nextStartedActivity
        assertEquals(NoteActivity::class.java.name, next.component!!.className)
        assertTrue(next.getBooleanExtra(NoteActivity.EXTRA_RECORD, false))
    }

    @Test fun selectAppearsInTheMenuOnceThereAreNotes() {
        NoteStore.get(ctx).add("a short thought")
        val main = home()
        main.findViewById<View>(R.id.btn_more).performClick()
        val popup: PopupMenu = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(popup.menu.findItem(MainActivity.MORE_SELECT))
    }

    @Test fun micHintSaysHoldForASession() {
        val main = home()
        assertEquals("Tap to dictate · hold for a long session", main.findViewById<View>(R.id.mic).tooltipText.toString())
    }

    @Test fun oneFeedShowsShortNotesAndSessionsTogether() {
        val store = NoteStore.get(ctx)
        store.add("a short thought for today")
        val long = store.add("a long session about the garden and the week ahead", NoteSchema.LONG)
        store.setDuration(long, 18 * 60_000L)
        val main = home()
        ShadowLooper.idleMainLooper()
        val texts = all(main.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.any { it.contains("a short thought") })
        assertTrue(texts.toString(), texts.any { it.contains("a long session") })
        assertTrue("session meta: $texts", texts.any { it.startsWith("Session") && it.contains("18 min") })
        assertNotNull(store.get(long))
    }

    @Test fun writingModeIsKeptButNotShown() {
        assertTrue(Prefs.WRITING_MODE in Prefs.hidden)
        assertFalse(Prefs.sections.any { Prefs.WRITING_MODE in it.second })
        assertEquals(WritingMode.SHORT, Prefs.WRITING_MODE.def)
        assertFalse(Prefs.THEME.summary.contains("button"))
    }
}
