package com.anoop.dailywhisper

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu

/** v0.1.27 note screen: Back, Edit and More; translation controls only when set up and useful. */
@RunWith(RobolectricTestRunner::class)
class NoteScreenV0127Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        for (f in listOf("settings", "state", "secrets", NoteActivity.WHOLE_PREFS)) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    @After fun reset() { NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null) }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun open(id: Long) = Robolectric.buildActivity(NoteActivity::class.java,
        android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()
    private fun more(a: NoteActivity): android.widget.PopupMenu {
        a.findViewById<View>(R.id.note_more).performClick()
        return ShadowPopupMenu.getLatestPopupMenu()
    }
    private fun pick(p: android.widget.PopupMenu, id: Int) { shadowOf(p).onMenuItemClickListener.onMenuItemClick(p.menu.findItem(id)); ShadowLooper.idleMainLooper() }

    @Test fun headerHasBackEditAndMoreOnly() {
        val a = open(NoteStore.get(ctx).add("Pick up oat milk on the way home"))
        val header = a.findViewById<View>(R.id.back).parent as ViewGroup
        val buttons = all(header).filterIsInstance<ImageButton>().filter { it.visibility == View.VISIBLE }.map { it.id }
        assertEquals(listOf(R.id.back, R.id.edit_toggle, R.id.note_more), buttons)
        for (gone in listOf("share", "history", "highlight", "archive", "delete")) {
            val i = ctx.resources.getIdentifier(gone, "id", ctx.packageName)
            assertTrue("$gone left the header", i == 0 || a.findViewById<View>(i) == null)
        }
        assertEquals("More", a.findViewById<View>(R.id.note_more).contentDescription)
    }

    @Test fun moreMenuHasShareHistoryHighlightArchiveDelete() {
        val a = open(NoteStore.get(ctx).add("Pick up oat milk on the way home"))
        val p = more(a)
        assertEquals(listOf("Share note", "Version history", "Highlight", "Archive", "Delete note"),
            (0 until p.menu.size()).map { p.menu.getItem(it).title.toString() })
    }

    @Test fun deleteFromMoreStillAsksFirst() {
        ctx.settings().edit().putBoolean(Prefs.CONFIRM_DELETE.key, true).commit() // Settings → Advanced → Notes → Ask before deleting
        val s = NoteStore.get(ctx)
        val id = s.add("Pick up oat milk on the way home")
        val a = open(id)
        pick(more(a), NoteActivity.NOTE_DELETE)
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertTrue(d.isShowing)
        assertEquals(NoteSchema.ACTIVE, s.get(id)!!.status) // nothing yet
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(NoteSchema.ACTIVE, s.get(id)!!.status)
        pick(more(a), NoteActivity.NOTE_DELETE)
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(NoteSchema.DELETED, s.get(id)!!.status)
        assertTrue(a.isFinishing)
    }

    @Test fun deleteFromMoreByDefaultGoesWithAnUndo() {
        val s = NoteStore.get(ctx)
        val id = s.add("Pick up oat milk on the way home")
        val a = open(id)
        pick(more(a), NoteActivity.NOTE_DELETE)
        assertEquals(NoteSchema.DELETED, s.get(id)!!.status)
        assertEquals("Home shows the Undo bar", id, NoteStore.recentlyDeleted?.id)
        assertTrue(a.isFinishing)
        NoteStore.recentlyDeleted = null
    }

    @Test fun highlightAndArchiveFromMore() {
        val s = NoteStore.get(ctx)
        val id = s.add("Pick up oat milk on the way home")
        val a = open(id)
        pick(more(a), NoteActivity.NOTE_HIGHLIGHT)
        val hl = s.get(id)!!.highlight
        assertTrue(hl != null)
        val p = more(a)
        assertTrue(p.menu.findItem(NoteActivity.NOTE_HIGHLIGHT).title.toString().startsWith("Highlight · "))
        pick(p, NoteActivity.NOTE_ARCHIVE)
        assertEquals(NoteSchema.ARCHIVED, s.get(id)!!.status)
        val again = open(id)
        assertEquals("Move back to notes", more(again).menu.findItem(NoteActivity.NOTE_ARCHIVE).title.toString())
    }

    // ---- translation only when set up and useful ----

    @Test fun gateRules() {
        assertTrue(TranslationGate.useful("zh", "en", onPhone = true, onlineReady = false))
        assertTrue(TranslationGate.useful("zh", "en", onPhone = false, onlineReady = true))
        assertFalse("same language", TranslationGate.useful("zh", "zh", true, true))
        assertFalse("no language", TranslationGate.useful(LiveTranslation.OFF, "en", true, true))
        assertFalse("no language", TranslationGate.useful(null, "en", true, true))
        assertFalse("nothing can do it", TranslationGate.useful("zh", "en", false, false))
    }

    private fun pill(a: NoteActivity) = a.findViewById<View>(R.id.tr_pill).visibility

    @Test fun pillShowsForAnEnglishNoteIntoChinese() {
        ctx.settings().edit().putString(Prefs.TRANSLATE_INTO.key, "zh").commit()
        val id = NoteStore.get(ctx).add("Pick up oat milk and coriander on the way home tonight")
        assertTrue(ctx.translationUseful("Pick up oat milk and coriander on the way home tonight"))
        assertEquals(View.VISIBLE, pill(open(id)))
    }

    @Test fun pillHiddenWhenTheNoteIsAlreadyInTheTargetLanguage() {
        ctx.settings().edit().putString(Prefs.TRANSLATE_INTO.key, "zh").putBoolean(Prefs.TRANSLATION.key, true).commit()
        val text = "今天下午去超市买牛奶和香菜，晚上给妈妈打电话。"
        val id = NoteStore.get(ctx).add(text)
        assertFalse(ctx.translationUseful(text))
        val a = open(id)
        assertEquals(View.GONE, pill(a))
        assertEquals("no translation beside it", View.GONE, a.findViewById<View>(R.id.note_tr).visibility)
        assertEquals("no tap-to-translate hint", ctx.getString(R.string.read_hint_on),
            a.findViewById<android.widget.TextView>(R.id.read_hint).text.toString())
        a.findViewById<View>(R.id.edit).performClick(); ShadowLooper.idleMainLooper()
        assertEquals("tap doesn't open the translation panel", View.GONE, a.findViewById<View>(R.id.tr_panel).visibility)
    }

    @Test fun pillHiddenWithoutATranslationLanguage() {
        ctx.settings().edit().putString(Prefs.TRANSLATE_INTO.key, LiveTranslation.OFF).commit()
        val id = NoteStore.get(ctx).add("Pick up oat milk and coriander on the way home tonight")
        assertEquals(View.GONE, pill(open(id)))
    }

    @Test fun switchedOffButUsefulStillShowsTheSwitch() {
        ctx.settings().edit().putString(Prefs.TRANSLATE_INTO.key, "zh").putBoolean(Prefs.TRANSLATION.key, false).commit()
        val a = open(NoteStore.get(ctx).add("Pick up oat milk and coriander on the way home tonight"))
        assertEquals(View.VISIBLE, pill(a))
        assertEquals("Off", a.findViewById<android.widget.TextView>(R.id.tr_pill_label).text.toString())
    }
}
