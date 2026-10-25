package com.anoop.dailywhisper

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/**
 * A plain framework EditText (reported to accessibility as android.widget.EditText) for the bottom composer.
 *
 * Dictation apps such as Vion write through accessibility ACTION_SET_TEXT, which calls setText() with the
 * whole new field value. Keyboard typing never goes through setText(), so it is unaffected here.
 * With [appendOnReplace] on, a setText() that would throw away the existing text is turned into an append:
 *  - incoming keeps the old text (old text is a prefix + suffix of it, i.e. something was inserted) -> taken as is
 *  - incoming is blank (cleared) -> taken as is
 *  - otherwise (old text would be lost) -> old text + " " + incoming
 * The app's own changes (clear after save, draft restore) use [setTextInternal] and bypass this.
 */
class ComposerEditText @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : EditText(ctx, attrs) {

    // Note: TextView's constructor calls setText() before these initialisers run; they read false then, which is safe.
    var appendOnReplace: Boolean = true
    private var internal = false

    fun setTextInternal(value: CharSequence) {
        internal = true
        try {
            setText(value)
            setSelection(text?.length ?: 0)
        } finally {
            internal = false
        }
    }

    override fun setText(value: CharSequence?, type: BufferType?) {
        if (appendOnReplace && !internal) {
            val old = text?.toString().orEmpty()
            val incoming = value?.toString().orEmpty()
            if (old.isNotBlank() && incoming.isNotBlank() && !keepsOld(old, incoming)) {
                val merged = old.trimEnd() + " " + incoming.trimStart()
                super.setText(merged, type)
                setSelection(merged.length)
                return
            }
        }
        super.setText(value, type)
    }

    /** True if [incoming] still contains [old] split around one insertion point (prefix + suffix). */
    private fun keepsOld(old: String, incoming: String): Boolean {
        if (incoming.length < old.length) return false
        var p = 0
        while (p < old.length && old[p] == incoming[p]) p++
        if (p == old.length) return true
        var s = 0
        while (s < old.length - p && old[old.length - 1 - s] == incoming[incoming.length - 1 - s]) s++
        return p + s >= old.length
    }
}
