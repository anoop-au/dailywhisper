package com.anoop.dailywhisper

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * Archive or Deleted, reached from the feed's ⋮ menu.
 * Archive: tap a note to open it (Unarchive is in its header).
 * Deleted: tap a note to read it, then Restore or (after a confirm) Delete forever, the app's only hard delete.
 */
class CollectionActivity : ThemedActivity() {

    private lateinit var store: NoteStore
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val deletedMode get() = intent.getStringExtra(EXTRA_MODE) == MODE_DELETED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore.get(this)
        val prefs = settings()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.bg_icon_btn)
            contentDescription = getString(R.string.back); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = if (deletedMode) "Deleted" else "Archive"; textSize = 24f; typeface = DwFonts.semibold(context)
            setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = if (deletedMode) "Tap a note to restore it. Nothing here is removed unless you choose Delete forever."
            else "Archived notes stay out of your feed. Open one to move it back."
            textSize = 13f; setTextColor(getColor(R.color.text2)); setPadding(dp(24), 0, dp(24), dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(24)) }
        empty = TextView(this).apply {
            text = if (deletedMode) "Nothing deleted." else "Nothing archived."
            gravity = Gravity.CENTER; textSize = 18f; typeface = DwFonts.regular(context)
            setTextColor(getColor(R.color.text2)); setPadding(dp(32), dp(48), dp(32), dp(32))
        }
        root.addView(ScrollView(this).apply {
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(empty); addView(list) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(root) { prefs[Prefs.MAX_WIDTH] }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val prefs = settings()
        val notes = if (deletedMode) store.deleted() else store.archived()
        list.removeAllViews()
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        var lastDay: LocalDate? = null
        for (n in notes) {
            val stamp = if (deletedMode) n.deletedAt ?: n.updated else n.created
            val day = stamp.toLocalDate()
            if (day != lastDay) { list.addView(dayHeader(list, day)); lastDay = day }
            val meta = if (deletedMode) "Deleted ${timeOf(stamp)}  ·  written ${dayLabel(n.created.toLocalDate())}" else cardMeta(n)
            list.addView(noteCard(list, n, prefs[Prefs.NOTE_TEXT_SIZE].toFloat(), prefs[Prefs.PREVIEW_LINES], meta).apply {
                setOnClickListener {
                    if (deletedMode) showDeleted(n)
                    else startActivity(Intent(this@CollectionActivity, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id))
                }
            })
        }
    }

    private fun showDeleted(n: Note) {
        AlertDialog.Builder(this)
            .setTitle("Deleted note")
            .setMessage(n.text)
            .setPositiveButton("Restore") { _, _ ->
                store.restore(n.id)
                Toast.makeText(this, "Restored to your notes", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNeutralButton("Delete forever") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Delete this note and its history forever? This can't be undone.")
                    .setPositiveButton("Delete forever") { _, _ -> store.deleteForever(n.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ARCHIVED = "archived"
        const val MODE_DELETED = "deleted"
    }
}
