package com.anoop.dailywhisper

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * A sideways swipe on a note card in the list (framework touch events only, no library). Taps and long-presses still go
 * to the card's own listeners; once the finger clearly moves sideways more than up or down, the list stops scrolling for
 * this touch, the card follows the finger a little, and past [THRESHOLD_DP] [onSwipe] runs (true = the finger moved
 * left). The card always slides back: deleting or archiving takes it out of the list on its own.
 */
class NoteSwipe(context: Context, private val onSwipe: (left: Boolean) -> Unit) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = THRESHOLD_DP * context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var vertical = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false; vertical = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!swiping && !vertical) {
                    if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) vertical = true // a scroll: leave it alone
                    else if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // No tap or long-press from this touch.
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        v.onTouchEvent(cancel); cancel.recycle()
                    }
                }
                if (swiping) { v.translationX = dx * 0.5f; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                val dx = e.rawX - downX
                v.parent?.requestDisallowInterceptTouchEvent(false)
                v.animate().translationX(0f).setDuration(150).start()
                if (e.actionMasked == MotionEvent.ACTION_UP && Math.abs(dx) >= threshold) onSwipe(dx < 0)
                return true
            }
        }
        return false
    }

    companion object { const val THRESHOLD_DP = 90f }
}
