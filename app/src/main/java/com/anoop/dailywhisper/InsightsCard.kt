package com.anoop.dailywhisper

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * The Insights card under a Long note (mockups 2-insights-*.png): "Summary | Sentiment" flips one card (a ~300 ms turn
 * about its vertical axis); wide screens (the side-by-side width, Settings → Translation) show both faces next to each
 * other instead. [onChip] is the small "Original" / translation-language chip. Plain framework views, no library.
 */
class InsightsCard(private val a: Activity, private val onChip: () -> Unit) {
    val view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(24)) }
    private val segSummary = segItem("Summary")
    private val segSentiment = segItem("Sentiment")
    private val seg = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
        background = round(a.getColor(R.color.mode_track), 999f)
        addView(segSummary); addView(segSentiment)
    }
    private val headNote = TextView(a).apply { textSize = 11.5f; setTextColor(a.getColor(R.color.text2)) }
    private val body = FrameLayout(a)
    private var front: View? = null
    private var back: View? = null
    /** Showing the Sentiment side (kept across rebinds). */
    var sentiment = false
        private set
    private var wide = false
    private var flipping = false

    init {
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(8))
        }
        head.addView(TextView(a).apply {
            text = "INSIGHTS"; textSize = 13f; letterSpacing = 0.06f; typeface = DwFonts.semibold(a); setTextColor(a.getColor(R.color.text2))
        })
        head.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(seg); head.addView(headNote)
        view.addView(head)
        view.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        segSummary.setOnClickListener { flipTo(false) }
        segSentiment.setOnClickListener { flipTo(true) }
        body.cameraDistance = 12000 * a.resources.displayMetrics.density
        view.visibility = View.GONE
    }

    fun hide() { view.visibility = View.GONE }

    /**
     * Shows [item]. [chip]: the chip's words ("Original", or the translation language's name), null for none.
     * [busy]: the other language is being fetched. [wide]: both faces side by side.
     */
    fun bind(item: Insights.Item, chip: String?, busy: Boolean, wide: Boolean) {
        this.wide = wide
        body.animate().cancel(); body.rotationY = 0f; flipping = false
        body.removeAllViews()
        val chipView = { chipView(chip, busy) }
        val f = summaryFace(item, chipView()); val b = sentimentFace(item, chipView())
        front = f; back = b
        if (wide) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(f, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = a.dp(6) })
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = a.dp(6) })
            body.addView(row)
            seg.visibility = View.GONE; headNote.visibility = View.VISIBLE; headNote.text = "Summary and mood"
        } else {
            // Both faces stay laid out (the hidden one invisible), so the card keeps its height through a flip.
            body.addView(f, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            body.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            seg.visibility = View.VISIBLE; headNote.visibility = View.GONE
            showFace(sentiment)
        }
        view.visibility = View.VISIBLE
    }

    /** Flips to the Sentiment ([toBack]) or Summary side: a quarter turn out, swap, a quarter turn in (~300 ms). */
    fun flipTo(toBack: Boolean) {
        if (wide || toBack == sentiment || flipping) return
        sentiment = toBack
        markSeg()
        flipping = true
        val sign = if (toBack) 1f else -1f
        body.animate().rotationY(90f * sign).setDuration(FLIP_MS / 2).setInterpolator(AccelerateInterpolator()).withEndAction {
            showFace(toBack)
            body.rotationY = -90f * sign
            body.animate().rotationY(0f).setDuration(FLIP_MS / 2).setInterpolator(DecelerateInterpolator())
                .withEndAction { flipping = false }.start()
        }.start()
    }

    private fun showFace(back: Boolean) {
        front?.visibility = if (back) View.INVISIBLE else View.VISIBLE
        this.back?.visibility = if (back) View.VISIBLE else View.INVISIBLE
        markSeg()
    }

    private fun markSeg() {
        for ((v, on) in listOf(segSummary to !sentiment, segSentiment to sentiment)) {
            v.background = if (on) round(a.getColor(R.color.card), 999f) else null
            v.elevation = if (on) a.dp(1).toFloat() else 0f
            v.setTextColor(a.getColor(if (on) R.color.text else R.color.text2))
        }
    }

    // ---- faces ----

    private fun face(title: String, chip: View?): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14))
        background = GradientDrawable().apply {
            setColor(a.getColor(R.color.card)); cornerRadius = a.dp(20).toFloat(); setStroke(a.dp(1), a.getColor(R.color.outline))
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(a).apply { text = title; textSize = 12f; typeface = DwFonts.semibold(a); setTextColor(a.getColor(R.color.text2)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chip?.let { head.addView(it) }
        addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(26)).apply { bottomMargin = a.dp(8) })
    }

    private fun summaryFace(item: Insights.Item, chip: View?): View {
        val f = face("Summary · ${item.summary.size} points", chip)
        for (line in item.summary) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.getColor(R.color.accent)) } },
                LinearLayout.LayoutParams(a.dp(6), a.dp(6)).apply { topMargin = a.dp(9); marginEnd = a.dp(10); marginStart = a.dp(2) })
            row.addView(text(line, 15f, R.color.text).apply { setLineSpacing(0f, 1.3f) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            f.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(8) })
        }
        return f
    }

    private fun sentimentFace(item: Insights.Item, chip: View?): View {
        val f = face("Sentiment", chip)
        val mood = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mood.addView(TextView(a).apply {
            text = Insights.emoji(item.tone); textSize = 22f; gravity = Gravity.CENTER
            background = round(a.getColor(R.color.gilt_soft), a.dp(14).toFloat())
        }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(12) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(item.mood, 24f, R.color.text).apply { typeface = DwFonts.bold(a); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (item.toneLine.isNotEmpty()) col.addView(text(item.toneLine, 12f, R.color.text2))
        mood.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(mood)
        f.addView(ToneScale(a, item.tone), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(20)).apply { topMargin = a.dp(12) })
        val labels = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val words = item.scale.takeIf { it.size == 3 } ?: listOf("Low", "Steady", "Upbeat")
        words.forEachIndexed { i, w ->
            labels.addView(text(w, 11.5f, R.color.text2).apply { gravity = when (i) { 0 -> Gravity.START; 1 -> Gravity.CENTER; else -> Gravity.END } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        f.addView(labels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(12) })
        for (s in item.shifts) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val (bg, fg) = when (s.dir) {
                Insights.UP -> R.color.tag_up_bg to R.color.tag_up
                Insights.DOWN -> R.color.tag_down_bg to R.color.tag_down
                else -> R.color.mode_track to R.color.text2
            }
            row.addView(text(s.label, 11f, fg).apply {
                typeface = DwFonts.semibold(a); setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(3)); background = round(a.getColor(bg), 999f)
            })
            row.addView(text(s.text, 14f, R.color.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = a.dp(10) })
            f.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(8) })
        }
        return f
    }

    private fun chipView(chip: String?, busy: Boolean): View? {
        chip ?: return null
        return TextView(a).apply {
            text = if (busy) "…" else chip; textSize = 12f; typeface = DwFonts.medium(a); gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.accent)); setPadding(a.dp(10), 0, a.dp(10), 0)
            background = round(a.getColor(R.color.accent_soft), 999f)
            minHeight = a.dp(24); contentDescription = if (busy) "Getting it" else chip
            setOnClickListener { if (!busy) onChip() }
        }
    }

    private fun segItem(label: String) = TextView(a).apply {
        text = label; textSize = 13f; typeface = DwFonts.semibold(a); gravity = Gravity.CENTER
        setPadding(a.dp(14), 0, a.dp(14), 0); minHeight = a.dp(30); setTextColor(a.getColor(R.color.text2))
    }

    private fun text(s: String, sp: Float, color: Int) = TextView(a).apply { text = s; textSize = sp; setTextColor(a.getColor(color)) }

    private fun round(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** The tone scale: a track from low (grey) to upbeat (green) with a knob at [tone] (0-100). */
    private class ToneScale(c: Context, private val tone: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val low = c.getColor(R.color.hint); private val mid = c.getColor(R.color.outline); private val high = c.getColor(R.color.ok)
        private val card = c.getColor(R.color.card)
        init { contentDescription = "Tone $tone out of 100" }
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val h = 8 * d; val r = 9 * d; val cy = height / 2f
            val track = RectF(r, cy - h / 2, width - r, cy + h / 2)
            paint.shader = LinearGradient(track.left, 0f, track.right, 0f, intArrayOf(low, mid, high), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(track, h / 2, h / 2, paint)
            paint.shader = null
            val x = track.left + track.width() * tone / 100f
            paint.color = card; canvas.drawCircle(x, cy, r - d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = high
            canvas.drawCircle(x, cy, r - 2.5f * d, paint)
        }
    }

    companion object { const val FLIP_MS = 300L }
}
