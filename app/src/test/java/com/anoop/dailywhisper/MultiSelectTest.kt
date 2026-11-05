package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu

/** Multi-select soft delete in the feed, driven through the real MainActivity / CollectionActivity on Robolectric. */
@RunWith(RobolectricTestRunner::class)
class MultiSelectTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: NoteStore
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var main: MainActivity
    private val ids = mutableListOf<Long>()

    @Before fun setUp() {
        // Fresh database per test (NoteStore is a process-wide singleton).
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
        store = NoteStore.get(ctx)
        ids.clear()
        for (t in listOf("first note", "second note", "third note", "fourth note")) { ids += store.add(t); Thread.sleep(2) }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        main = controller.get()
        idle()
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun cards(): List<View> {
        val box = main.findViewById<ViewGroup>(R.id.notes)
        return (0 until box.childCount).map { box.getChildAt(it) }.filter { it.tag is Long }
    }
    private fun card(id: Long) = cards().first { it.tag == id }
    private fun checked(): Set<Long> = cards().filter { it.isSelected }.map { it.tag as Long }.toSet()
    private val selectBar get() = main.findViewById<View>(R.id.select_bar)
    private val header get() = main.findViewById<View>(R.id.header)
    private val composerBar get() = main.findViewById<View>(R.id.composer_bar)
    private val count get() = main.findViewById<TextView>(R.id.select_count).text.toString()
    private val deleteBtn get() = main.findViewById<View>(R.id.select_delete)

    private fun longPressMenu(id: Long): List<String> {
        card(id).performLongClick(); idle()
        val menu = ShadowPopupMenu.getLatestPopupMenu()!!.menu
        return (0 until menu.size()).map { menu.getItem(it).title.toString() }
    }
    private fun pickFromMenu(title: String) {
        val popup = ShadowPopupMenu.getLatestPopupMenu()!!
        val item = (0 until popup.menu.size()).map { popup.menu.getItem(it) }.first { it.title == title }
        shadowOf(popup).onMenuItemClickListener.onMenuItemClick(item); idle()
    }
    private fun enterSelectOn(id: Long) { longPressMenu(id); pickFromMenu("Select") }

    @Test fun longPressMenuKeepsShareAndDeleteAndAddsSelect() {
        assertEquals(listOf("Share", "Delete", "Select"), longPressMenu(ids[1]))
        assertEquals(View.GONE, selectBar.visibility)
    }

    @Test fun selectEntersModeWithNotePreselected() {
        enterSelectOn(ids[1])
        assertEquals(View.VISIBLE, selectBar.visibility)
        assertEquals(View.GONE, header.visibility)
        assertEquals(View.GONE, composerBar.visibility)
        assertEquals("1 selected", count)
        assertEquals(setOf(ids[1]), checked())
        assertTrue(deleteBtn.isEnabled)
        // Every card shows a tick ring in select mode.
        assertTrue(cards().all { it.findViewById<ImageView>(R.id.check).visibility == View.VISIBLE })
    }

    @Test fun tapTogglesInsteadOfOpening() {
        enterSelectOn(ids[0])
        card(ids[2]).performClick(); idle()
        assertNull("tap in select mode does not open the note", shadowOf(main).nextStartedActivity)
        assertEquals(setOf(ids[0], ids[2]), checked())
        assertEquals("2 selected", count)
        card(ids[0]).performClick(); card(ids[2]).performLongClick(); idle()
        assertEquals(emptySet<Long>(), checked())
        assertEquals("0 selected", count)
        assertFalse("Delete disabled with nothing picked", deleteBtn.isEnabled)
        assertEquals(View.VISIBLE, selectBar.visibility)
    }

    @Test fun selectAllThenBulkDeleteSoftDeletesAllAndExits() {
        enterSelectOn(ids[0])
        main.findViewById<View>(R.id.select_all).performClick(); idle()
        assertEquals(ids.toSet(), checked())
        assertEquals("4 selected", count)
        deleteBtn.performClick(); idle()
        assertNull("no confirm dialog by default (same as single delete)", ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(View.GONE, selectBar.visibility)
        assertEquals(View.VISIBLE, header.visibility)
        assertEquals(View.VISIBLE, composerBar.visibility)
        assertTrue(store.active().isEmpty())
        val deleted = store.deleted()
        assertEquals(ids.toSet(), deleted.map { it.id }.toSet())
        assertTrue(deleted.all { it.deletedAt != null && it.status == NoteSchema.DELETED })
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.undo_bar).visibility)
        assertEquals("4 notes deleted", main.findViewById<TextView>(R.id.undo_text).text.toString())
        // Versions are untouched by a soft delete.
        assertTrue(ids.all { store.versions(it).size == 1 })
    }

    @Test fun undoRestoresAllIncludingArchivedStatus() {
        store.archive(ids[3])
        controller.pause().resume(); idle()
        enterSelectOn(ids[0]); card(ids[1]).performClick(); idle()
        deleteBtn.performClick(); idle()
        assertEquals(setOf(ids[2]), store.active().map { it.id }.toSet())
        main.findViewById<View>(R.id.undo_btn).performClick(); idle()
        assertEquals(setOf(ids[0], ids[1], ids[2]), store.active().map { it.id }.toSet())
        assertEquals(listOf(ids[3]), store.archived().map { it.id })
        assertEquals(0, store.deletedCount())
    }

    @Test fun deletedNotesRecoverFromDeletedView() {
        store.setHighlight(ids[0], "sage")
        enterSelectOn(ids[0]); card(ids[1]).performClick(); idle()
        deleteBtn.performClick(); idle()
        val col = Robolectric.buildActivity(CollectionActivity::class.java,
            android.content.Intent(ctx, CollectionActivity::class.java)
                .putExtra(CollectionActivity.EXTRA_MODE, CollectionActivity.MODE_DELETED)).setup().get()
        idle()
        fun deletedCards(): List<View> {
            val out = mutableListOf<View>()
            fun walk(v: View) {
                if (v.id == R.id.text) out += v.parent as View
                else if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
            }
            walk(col.window.decorView); return out
        }
        assertEquals(2, deletedCards().size)
        deletedCards().first().performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(1, store.deletedCount())
        assertEquals(3, store.active().size)
        val restored = store.active().map { it.id }.toSet().intersect(setOf(ids[0], ids[1])).single()
        if (restored == ids[0]) assertEquals("sage", store.get(ids[0])!!.highlight)
    }

    @Test fun cancelExitsWithoutDeleting() {
        enterSelectOn(ids[0]); card(ids[1]).performClick(); idle()
        main.findViewById<View>(R.id.select_cancel).performClick(); idle()
        assertEquals(View.GONE, selectBar.visibility)
        assertEquals(View.VISIBLE, composerBar.visibility)
        assertEquals(4, store.active().size)
        assertTrue(cards().all { !it.isSelected && it.findViewById<ImageView>(R.id.check).visibility == View.GONE })
        // Back to normal: tap opens the note again.
        card(ids[1]).performClick(); idle()
        assertEquals(NoteActivity::class.java.name, shadowOf(main).nextStartedActivity?.component?.className)
    }

    @Test fun backExitsWithoutDeleting() {
        enterSelectOn(ids[2])
        @Suppress("DEPRECATION") main.onBackPressed(); idle()
        assertEquals(View.GONE, selectBar.visibility)
        assertFalse("back closed select mode, not the app", main.isFinishing)
        assertEquals(4, store.active().size)
        assertEquals(0, store.deletedCount())
    }

    @Test fun confirmSettingAsksOnceForBulkDelete() {
        ctx.getSharedPreferences("settings", 0).edit().putBoolean("confirm_delete", true).commit()
        enterSelectOn(ids[0]); card(ids[1]).performClick(); idle()
        deleteBtn.performClick(); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals(4, store.active().size)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(2, store.deletedCount())
    }

    @Test fun selectAllFollowsSearch() {
        enterSelectOn(ids[0])
        main.findViewById<View>(R.id.btn_search).performClick(); idle()
        main.findViewById<android.widget.EditText>(R.id.search).setText("second"); idle()
        // Only visible notes stay picked; Select all picks what the search shows.
        assertEquals(emptySet<Long>(), checked())
        main.findViewById<View>(R.id.select_all).performClick(); idle()
        assertEquals(setOf(ids[1]), checked())
        deleteBtn.performClick(); idle()
        assertEquals(listOf(ids[1]), store.deleted().map { it.id })
    }
}
