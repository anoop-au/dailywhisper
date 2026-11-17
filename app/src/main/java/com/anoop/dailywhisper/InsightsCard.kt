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
 * other instead. [onChip] is the small "Show in English" / "Show in 中文" chip. Plain framework views, no library.
 */
class InsightsCard(private val a: Activity, private val onChip: () -> Unit, private val onRetry: () -> Unit = {}) {
    enum class State { HIDDEN, LOADING, FAILED, SHOWN }
    /** What the card shows now. */
    var state = State.HIDDEN
        private set
    val view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(24)) }
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
    private var item: Insights.Item? = null
    /** Copies the face showing (plain text). */
    private val copy = android.widget.ImageButton(a).apply {
        setImageResource(R.drawable.ic_copy); setBackgroundResource(R.drawable.bg_icon_btn)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7))
        contentDescription = "Copy"; visibility = View.GONE
        setOnClickListener {
            val t = plainText(); if (t.isBlank()) return@setOnClickListener
            a.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Insights", t))
            android.widget.Toast.makeText(a, a.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    init {
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(8))
        }
        head.addView(TextView(a).apply {
            text = "INSIGHTS"; textSize = 11.5f; letterSpacing = 0.22f; typeface = DwFonts.regular(a); setTextColor(a.getColor(R.color.text2))
        })
        head.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(seg); head.addView(headNote)
        head.addView(copy, LinearLayout.LayoutParams(a.dp(36), a.dp(32)).apply { marginStart = a.dp(4) })
        view.addView(head)
        view.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        segSummary.setOnClickListener { flipTo(false) }
        segSentiment.setOnClickListener { flipTo(true) }
        body.cameraDistance = 12000 * a.resources.displayMetrics.density
        view.visibility = View.GONE
    }

    fun hide() { view.visibility = View.GONE; state = State.HIDDEN }

    /** A summary is on its way: a quiet line where it will appear. */
    fun loading() = message(LOADING, State.LOADING)

    /** Nothing usable came back (no answer in time, refused, or a reply that didn't check out): tap to try again. */
    fun failed(reason: String? = null) = message(FAILED, State.FAILED, reason)

    /** The face showing now as plain text (Copy): the summary, the sentiment, or both side by side. */
    fun plainText(): String {
        val i = item ?: return ""
        val summary = "Summary\n" + i.summary.joinToString("\n") { "• $it" }
        val mood = buildString {
            append("Sentiment: ${i.mood} (${i.tone}/100)")
            if (i.toneLine.isNotBlank()) append("\n${i.toneLine}")
            for (s in i.shifts) append("\n${when (s.dir) { Insights.UP -> "↑"; Insights.DOWN -> "↓"; else -> "→" }} ${s.label}: ${s.text}")
        }
        return when { wide -> "$summary\n\n$mood"; sentiment -> mood; else -> summary }
    }

    private fun message(words: String, s: State, reason: String? = null) {
        body.animate().cancel(); body.rotationY = 0f; flipping = false
        body.removeAllViews(); front = null; back = null; item = null
        copy.visibility = View.GONE
        val f = face("Summary", null)
        if (s == State.LOADING) {
            // A small spinner beside the words, then faint bars where the points will appear.
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(android.widget.ProgressBar(a).apply {
                isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent))
                contentDescription = words
            }, LinearLayout.LayoutParams(a.dp(18), a.dp(18)).apply { marginEnd = a.dp(10) })
            row.addView(text(words, 15f, R.color.text2))
            f.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(12) })
            for (w in listOf(0.92f, 0.78f, 0.6f)) {
                val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                bar.addView(View(a).apply { background = round(a.getColor(R.color.mode_track), 999f) },
                    LinearLayout.LayoutParams(0, a.dp(10), w))
                bar.addView(Space(a), LinearLayout.LayoutParams(0, 1, 1f - w))
                f.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(10) })
            }
        } else {
            f.addView(text(words, 15f, R.color.text).apply { setPadding(0, a.dp(2), 0, a.dp(2)) })
            if (reason != null) f.addView(text("Why: $reason.", 14f, R.color.text).apply { setPadding(0, a.dp(6), 0, 0); setLineSpacing(0f, 1.2f) })
            f.addView(text("Settings → Insights → Test summary & translation shows what's missing.", 13f, R.color.text2)
                .apply { setPadding(0, a.dp(4), 0, 0) })
        }
        if (s == State.FAILED) {
            f.isClickable = true; f.isFocusable = true; f.contentDescription = words
            f.setOnClickListener { onRetry() }
            f.addView(TextView(a).apply {
                text = "Try again"; textSize = 14f; typeface = DwFonts.semibold(a); gravity = Gravity.CENTER
                setTextColor(a.getColor(R.color.accent)); setPadding(a.dp(16), 0, a.dp(16), 0); minHeight = a.dp(36)
                background = round(a.getColor(R.color.accent_soft), 999f)
                setOnClickListener { onRetry() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, a.dp(36)).apply { topMargin = a.dp(12) })
        }
        body.addView(f, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        seg.visibility = View.GONE; headNote.visibility = View.GONE
        view.visibility = View.VISIBLE; state = s
    }

    /**
     * Shows [item]. [chip]: the chip's words ("Original", or the translation language's name), null for none.
     * [busy]: the other language is being fetched. [wide]: both faces side by side.
     */
    fun bind(item: Insights.Item, chip: String?, busy: Boolean, wide: Boolean) {
        this.wide = wide; this.item = item
        copy.visibility = View.VISIBLE
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
            seg.visibility = View.GONE; headNote.visibility = View.GONE // the same header as one face: INSIGHTS, then Copy
        } else {
            // Both faces stay laid out (the hidden one invisible), so the card keeps its height through a flip.
            body.addView(f, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            body.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            seg.visibility = View.VISIBLE; headNote.visibility = View.GONE
            showFace(sentiment)
        }
        view.visibility = View.VISIBLE; state = State.SHOWN
    }

    /** Flips to the Sentiment ([toBack]) or Summary side: a quarter turn out, swap, a quarter turn in (~300 ms). */
    fun flipTo(toBack: Boolean) {
        if (state != State.SHOWN || wide || toBack == sentiment || flipping) return
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
        background = GoldEdge.make(a, 20, a.getColor(R.color.card)) // the gold frame (not on the tabs, chips or scale)
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

    companion object {
        const val FLIP_MS = 300L
        const val LOADING = "Writing a summary…"
        const val FAILED = "Couldn't write a summary."
    }
}
