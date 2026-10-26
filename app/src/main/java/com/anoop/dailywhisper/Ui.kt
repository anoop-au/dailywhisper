package com.anoop.dailywhisper

import android.app.Activity
import android.view.View
import android.view.WindowInsets

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/**
 * Edge-to-edge (required at targetSdk 36): pad [root] for the status/nav bars, cutout and keyboard,
 * and centre the content at [maxWidthDp] on wide screens (unfolded Fold, landscape).
 * Returns a function that re-applies the padding (call after settings change).
 */
fun Activity.edgeToEdge(root: View, maxWidthDp: () -> Int): () -> Unit {
    window.setDecorFitsSystemWindows(false)
    var last: WindowInsets? = null
    fun apply() {
        val ins = last ?: return
        val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
        val width = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val extra = ((width - b.left - b.right - dp(maxWidthDp())) / 2).coerceAtLeast(0)
        root.setPadding(b.left + extra, b.top, b.right + extra, b.bottom)
    }
    root.setOnApplyWindowInsetsListener { _, insets -> last = insets; apply(); WindowInsets.CONSUMED }
    root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) root.post { apply() } }
    root.requestApplyInsets()
    return { apply() }
}

/**
 * Opens Android's system share sheet with the note's plain text (ACTION_SEND, text/plain).
 * No permissions needed; nothing leaves the phone unless the user picks an app.
 */
fun Activity.shareNote(text: String) {
    if (text.isBlank()) return
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    try {
        startActivity(android.content.Intent.createChooser(send, getString(R.string.share)))
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(this, "No app available to share with", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** A ScrollView that stops growing at [maxHeightPx] (0 = no limit). Used by the translation panel. */
class MaxHeightScrollView(context: android.content.Context, attrs: android.util.AttributeSet?) : android.widget.ScrollView(context, attrs) {
    var maxHeightPx = 0
        set(v) { field = v; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var h = heightMeasureSpec
        if (maxHeightPx > 0) {
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
            h = View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, h)
    }
}
