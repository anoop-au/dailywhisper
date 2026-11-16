package com.anoop.dailywhisper

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Private Weekly Review (Daily Whisper Plus): one week's themes, mood, open thoughts and a few gentle prompts, in gold-rim
 * cards like the Insights card. Reached from Home ⋮ → Weekly review and Settings → Daily Whisper Plus. ‹ › step through
 * weeks. Without Plus it shows what the review is and the switch; with too few notes it says how many it needs.
 */
class WeeklyReviewActivity : ThemedActivity() {
    private lateinit var maker: WeeklyReviewMaker
    private lateinit var body: LinearLayout
    private lateinit var range: TextView
    private lateinit var next: ImageButton
    private lateinit var nav: View
    /** Weeks back from the latest review week (0 = the latest). */
    private var back = 0
    private var job: WeeklyReviewMaker.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maker = WeeklyReviewMaker(this)
        back = savedInstanceState?.getInt(KEY_BACK) ?: 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.bg_icon_btn)
            contentDescription = getString(R.string.back); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = getString(R.string.weekly_review); textSize = 24f; typeface = DwFonts.semibold(context)
            setTextColor(getColor(R.color.text)); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(badge("PLUS"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(12) })
        root.addView(header)
        // The week: ‹ 3 – 9 Nov ›
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        nav.addView(arrow(false) { back++; show() })
        range = TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.text2)); gravity = Gravity.CENTER; typeface = DwFonts.medium(context)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        nav.addView(range, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        next = arrow(true) { if (back > 0) { back--; show() } }
        nav.addView(next)
        root.addView(nav)
        this.nav = nav
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(root) { settings()[Prefs.MAX_WIDTH] }
    }

    override fun onResume() { super.onResume(); show() }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt(KEY_BACK, back) }

    private fun currentWeek(): Pair<LocalDate, LocalDate> {
        val (s, e) = maker.week()
        return s.minusWeeks(back.toLong()) to e.minusWeeks(back.toLong())
    }

    /** Shows the week: locked without Plus, else the saved review, else one made now on the phone. */
    private fun show(remake: Boolean = false) {
        job?.cancel(); job = null
        body.removeAllViews()
        val (start, end) = currentWeek()
        range.text = weekLabel(start, end)
        next.isEnabled = back > 0; next.alpha = if (back > 0) 1f else 0.3f
        val plus = Plus.entitlement(this).active
        nav.visibility = if (plus) View.VISIBLE else View.GONE // no weeks to step through while locked
        if (!plus) { locked(); return }
        val store = ReviewStore.get(this)
        val saved = if (remake) null else store.get(start)
        val review = saved ?: maker.makeOnPhone(start, end)
        if (review == null) { tooFew(start, end); return }
        render(review, working = false)
        // Online only when switched on (and possible): the phone's review shows meanwhile, then the online one replaces it.
        if ((remake || saved == null) && maker.canUseCloud()) {
            render(review, working = true)
            job = maker.makeOnline(review) { r -> job = null; if (!isFinishing) render(r ?: review, working = false, failed = r == null) }
        }
    }

    private fun render(r: WeeklyReview.Review, working: Boolean, failed: Boolean = false) {
        body.removeAllViews()
        range.text = weekLabel(r.start, r.end) + "  ·  " + plural(r.notes, "note") + "  ·  " +
            java.text.NumberFormat.getIntegerInstance().format(r.words) + " words"
        if (r.themes.isNotEmpty()) body.addView(card("Key themes", chips(r.themes)))
        body.addView(card("Mood this week", mood(r)))
        body.addView(card("Unfinished thoughts", if (r.unfinished.isEmpty()) plain("Nothing left hanging this week.", dim = true)
            else list(r.unfinished, numbered = false)))
        body.addView(card("Gentle prompts", list(r.prompts, numbered = true)))
        val foot = when {
            working -> "Made on this phone · writing it online…"
            r.source == WeeklyReview.ONLINE -> "Written online from ${plural(r.notes, "note")} · mood from this phone"
            else -> "Made on this phone · from ${plural(r.notes, "note")}" + if (failed) " · online didn't answer" else ""
        }
        body.addView(plain(foot, dim = true).apply { gravity = Gravity.CENTER; textSize = 13f; setPadding(dp(8), dp(14), dp(8), dp(4)) })
        body.addView(Button(this).apply {
            text = "Make again"; isAllCaps = false; setTextColor(getColor(R.color.accent)); setBackgroundResource(R.drawable.bg_icon_btn)
            isEnabled = !working
            setOnClickListener { show(remake = true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun locked() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "Daily Whisper Plus"; textSize = 20f; typeface = DwFonts.semibold(context); setTextColor(getColor(R.color.text))
        })
        col.addView(plain("Once a week, a private reflection from your notes: the themes that kept coming up, how your mood " +
            "moved, thoughts you left open, and a few gentle prompts for the week ahead.").apply { setPadding(0, dp(8), 0, 0) })
        col.addView(plain("Made on this phone. Nothing is sent unless you choose to in Settings → Privacy.", dim = true)
            .apply { textSize = 13f; setPadding(0, dp(10), 0, 0) })
        col.addView(Button(this).apply {
            text = "Try Plus on this phone"; isAllCaps = false; setTextColor(getColor(R.color.on_accent))
            setPadding(dp(22), 0, dp(22), 0); typeface = DwFonts.semibold(context)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(getColor(R.color.accent)) }
            setOnClickListener { settings().edit().putBoolean(Prefs.PLUS.key, true).apply(); show() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(16) })
        col.addView(plain("A preview: there's no payment yet. Turn it off any time in Settings.", dim = true)
            .apply { textSize = 12f; setPadding(0, dp(8), 0, 0) })
        body.addView(frame(col))
    }

    private fun tooFew(start: LocalDate, end: LocalDate) {
        val need = maker.options().minNotes
        val have = maker.weekNotes(start, end).size
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "Not enough notes yet"; textSize = 18f; typeface = DwFonts.semibold(context); setTextColor(getColor(R.color.text))
        })
        col.addView(plain("A review needs at least ${plural(need, "note")} in the week. This one has $have. " +
            "Use ‹ to look at an earlier week, or change the number in Settings → Advanced → Weekly review.")
            .apply { setPadding(0, dp(8), 0, 0) })
        body.addView(frame(col))
    }

    // ---- pieces ----

    private fun frame(content: View) = FrameLayout(this).apply {
        background = GoldEdge.make(this@WeeklyReviewActivity, 20, getColor(R.color.card))
        setPadding(dp(20), dp(18), dp(20), dp(20))
        addView(content)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun card(title: String, content: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title.uppercase(Locale.getDefault()); textSize = 12f; letterSpacing = 0.08f; typeface = DwFonts.semibold(context)
            setTextColor(getColor(R.color.gilt)); setPadding(0, 0, 0, dp(10))
        })
        col.addView(content)
        return frame(col)
    }

    private fun plain(t: String, dim: Boolean = false) = TextView(this).apply {
        text = t; textSize = 15f; typeface = DwFonts.regular(context); setLineSpacing(0f, 1.25f)
        setTextColor(getColor(if (dim) R.color.text2 else R.color.text))
    }

    private fun badge(t: String) = TextView(this).apply {
        text = t; textSize = 11f; letterSpacing = 0.1f; typeface = DwFonts.semibold(context); setTextColor(getColor(R.color.gilt))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(getColor(R.color.gilt_soft)) }
    }

    private fun arrow(forward: Boolean, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(R.drawable.ic_chevron); setBackgroundResource(R.drawable.bg_icon_btn)
        rotation = if (forward) 0f else 180f
        contentDescription = if (forward) "Next week" else "Earlier week"
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    /** Themes as soft pills, wrapping onto new lines. */
    private fun chips(themes: List<String>): View {
        val flow = FlowLayout(this, dp(8))
        for (t in themes) flow.addView(TextView(this).apply {
            text = t; textSize = 14f; typeface = DwFonts.medium(context); setTextColor(getColor(R.color.accent))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.accent_soft)) }
        })
        return flow
    }

    /** The mood line, the most common moods, and seven small bars (Mon…Sun) for each day's tone. */
    private fun mood(r: WeeklyReview.Review): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(plain(r.moodLine).apply { textSize = 16f })
        if (r.moods.isNotEmpty()) col.addView(plain("Often: " + r.moods.joinToString(" · "), dim = true).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) })
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(14), 0, 0) }
        val maxH = dp(56)
        for (d in r.days) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            val tone = d.tone
            // A low day still shows as a (short, red) bar, not a dot that reads as "no notes".
            val h = if (tone == null) dp(4) else maxH / 4 + maxH * 3 / 4 * tone / 100
            val color = when {
                tone == null -> R.color.outline
                tone >= 60 -> R.color.tag_up
                tone <= 40 -> R.color.tag_down
                else -> R.color.accent
            }
            cell.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(getColor(color)) }
                contentDescription = d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                    if (tone == null) ": no notes to go on" else ": " + Insights.emoji(tone)
            }, LinearLayout.LayoutParams(dp(14), h))
            cell.addView(TextView(this).apply {
                text = d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()); textSize = 11f
                setTextColor(getColor(R.color.text2)); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            bars.addView(cell, LinearLayout.LayoutParams(0, maxH + dp(24), 1f))
        }
        col.addView(bars)
        return col
    }

    private fun list(items: List<String>, numbered: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, if (i == 0) 0 else dp(10), 0, 0) }
            row.addView(TextView(this).apply {
                text = if (numbered) "${i + 1}" else "·"; textSize = 15f; typeface = DwFonts.semibold(context)
                setTextColor(getColor(R.color.gilt)); minWidth = dp(20)
            })
            row.addView(plain(t), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
        }
        return col
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    companion object {
        private const val KEY_BACK = "weeks_back"
        private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        private val DAY = DateTimeFormatter.ofPattern("d", Locale.getDefault())
        /** "3 – 9 Nov", or "27 Oct – 2 Nov" across months. */
        fun weekLabel(start: LocalDate, end: LocalDate): String =
            (if (start.month == end.month) start.format(DAY) else start.format(DAY_MONTH)) + " – " + end.format(DAY_MONTH)
    }
}

/** Children left to right, wrapping to a new line when the next doesn't fit; [gap] between them both ways. */
class FlowLayout(c: android.content.Context, private val gap: Int) : android.view.ViewGroup(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i); if (ch.visibility == GONE) continue
            measureChild(ch, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            x += ch.measuredWidth + gap; line = maxOf(line, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + line + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i); if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth + gap; line = maxOf(line, ch.measuredHeight)
        }
    }
}
