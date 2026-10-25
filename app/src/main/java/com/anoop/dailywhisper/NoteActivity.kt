package com.anoop.dailywhisper

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** View and edit one note. Changes save when you leave; emptying a note deletes it (with Undo on the list). */
class NoteActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var edit: EditText
    private var note: Note? = null
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)
        store = NoteStore.get(this)
        val n = store.get(intent.getLongExtra(EXTRA_ID, -1))
        if (n == null) { finish(); return }
        note = n
        val prefs = settings()
        edgeToEdge(findViewById(R.id.root)) { prefs[Prefs.MAX_WIDTH] }

        edit = findViewById(R.id.edit)
        edit.setText(n.text)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs[Prefs.NOTE_TEXT_SIZE] + 2f)
        val df = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT)
        findViewById<TextView>(R.id.date).text = df.format(Date(n.created))

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.delete).setOnClickListener {
            if (prefs[Prefs.CONFIRM_DELETE]) {
                AlertDialog.Builder(this)
                    .setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ -> deleteAndClose() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else deleteAndClose()
        }
    }

    private fun deleteAndClose() {
        val n = note ?: return
        store.delete(n.id)
        NoteStore.recentlyDeleted = n.copy(text = edit.text.toString().ifBlank { n.text })
        deleted = true
        finish()
    }

    override fun onPause() {
        super.onPause()
        val n = note ?: return
        if (deleted) return
        val t = edit.text.toString()
        when {
            t.isBlank() && isFinishing -> { store.delete(n.id); NoteStore.recentlyDeleted = n; deleted = true }
            t.isBlank() -> {}
            t != n.text -> { store.update(n.id, t); note = n.copy(text = t) }
        }
    }

    companion object { const val EXTRA_ID = "id" }
}
