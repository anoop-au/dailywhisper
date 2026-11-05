package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** First-open Short/Long chooser, the top toggle, and the remembered preference. */
@RunWith(RobolectricTestRunner::class)
class WritingModeTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().commit()
    }

    @Test fun defaultIsShortUntilChosen() {
        assertEquals(WritingMode.SHORT, WritingMode.current(ctx))
        assertFalse(WritingMode.hasChosen(ctx))
        assertFalse(WritingMode.isLong(ctx))
    }

    @Test fun chooseRemembersAndHidesChooser() {
        WritingMode.choose(ctx, WritingMode.LONG)
        assertTrue(WritingMode.hasChosen(ctx))
        assertTrue(WritingMode.isLong(ctx))
        assertEquals(WritingMode.LONG, ctx.settings()[Prefs.WRITING_MODE])
        WritingMode.set(ctx, WritingMode.SHORT)
        assertEquals(WritingMode.SHORT, WritingMode.current(ctx))
        assertTrue("chooser stays dismissed after a later toggle", WritingMode.hasChosen(ctx))
    }

    @Test fun chooserWritesPreferenceAndFinishes() {
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        val chooser = Robolectric.buildActivity(ModeChooserActivity::class.java).setup().get()
        chooser.findViewById<View>(R.id.choice_long).performClick()
        assertTrue(chooser.isFinishing)
        assertEquals(WritingMode.LONG, WritingMode.current(ctx))
        assertTrue(WritingMode.hasChosen(ctx))
    }

    @Test fun toggleOnHomeRemembersAndSwapsTheLayout() {
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.composer_bar).visibility)
        assertEquals(View.GONE, main.findViewById<View>(R.id.fab).visibility)
        assertEquals("Today", main.findViewById<TextView>(R.id.subtitle).text.toString())
        main.findViewById<View>(R.id.mode_long).performClick()
        assertEquals(WritingMode.LONG, WritingMode.current(ctx))
        assertEquals(View.GONE, main.findViewById<View>(R.id.composer_bar).visibility)
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.fab).visibility)
        assertEquals("Sessions", main.findViewById<TextView>(R.id.subtitle).text.toString())
        main.findViewById<View>(R.id.mode_short).performClick()
        assertEquals(WritingMode.SHORT, WritingMode.current(ctx))
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.composer_bar).visibility)
    }

    @Test fun settingsListsTheModePref() {
        assertTrue(Prefs.sections.any { it.first == "Look & layout" && Prefs.WRITING_MODE in it.second })
        assertEquals(WritingMode.SHORT, Prefs.WRITING_MODE.def)
    }
}
