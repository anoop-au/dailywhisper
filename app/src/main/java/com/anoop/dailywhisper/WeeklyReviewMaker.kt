package com.anoop.dailywhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes and keeps the Weekly review. Always on the phone first ([WeeklyReview.build], instant). Online only when every
 * one of these holds: Plus is on, Settings → Privacy → Weekly review online is on (off by default, asked when turned
 * on), and a chat service is set up with its own OK (the same ones as summaries, OpenRouter first). Then the week's notes
 * go to the first that answers; whatever it says replaces the words, and if none answers the phone's review stays.
 */
class WeeklyReviewMaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun interface Job { fun cancel() }

    fun options(): WeeklyReview.Options {
        val p = app.settings()
        return WeeklyReview.Options(p[Prefs.WR_MIN_NOTES], p[Prefs.WR_THEMES], p[Prefs.WR_UNFINISHED], p[Prefs.WR_PROMPTS])
    }

    fun week(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
        WeeklyReview.week(today, app.settings()[Prefs.WR_DAY].toIntOrNull() ?: 7)

    fun weekNotes(start: LocalDate, end: LocalDate): List<Note> = WeeklyReview.inWeek(NoteStore.get(app).active(), start, end)

    /** Online is allowed and possible now (see the class note). */
    fun cloudAllowed(): Boolean = app.settings()[Prefs.WR_CLOUD] && Plus.entitlement(app).active

    fun canUseCloud(): Boolean = cloudAllowed() && (InsightsMaker.testProviders != null || app.isOnline()) &&
        InsightsMaker(app).providers().isNotEmpty()

    /** The phone's review for the week, saved (null: too few notes). */
    fun makeOnPhone(start: LocalDate, end: LocalDate, now: Long = System.currentTimeMillis()): WeeklyReview.Review? =
        WeeklyReview.build(weekNotes(start, end), start, end, options(), now)?.also { ReviewStore.get(app).save(it) }

    /** [base] rewritten online when allowed; [done] on the main thread with the saved result, or null (base stays). */
    fun makeOnline(base: WeeklyReview.Review, done: (WeeklyReview.Review?) -> Unit): Job {
        val cancelled = AtomicBoolean(false)
        if (!canUseCloud()) { main.post { done(null) }; return Job {} }
        val notes = weekNotes(base.start, base.end)
        val o = options()
        WORKER.execute {
            var out: WeeklyReview.Review? = null
            for (p in InsightsMaker(app).providers()) {
                if (cancelled.get()) break
                val reply = try { p.chat(app, WeeklyReview.cloudPrompt(o), WeeklyReview.cloudInput(notes), WeeklyReview.MAX_TOKENS) }
                    catch (e: Throwable) { Log.w(TAG, "Weekly review: ${p.label} failed: ${e.javaClass.simpleName}"); null }
                out = WeeklyReview.parseCloud(reply, base, o, System.currentTimeMillis())
                if (out != null) break
            }
            out?.let { ReviewStore.get(app).save(it) }
            val r = out
            main.post { if (!cancelled.get()) done(r) }
        }
        return Job { cancelled.set(true) }
    }

    companion object {
        private const val TAG = "DailyWhisper"
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "weekly-review").apply { isDaemon = true } }
    }
}
