package com.anoop.dailywhisper

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout

/** "Live translation into" now: a language code, or LiveTranslation.OFF. */
fun Context.liveTranslationInto(): String = settings()[Prefs.LIVE_TR_INTO]

/** The pairs these settings will most likely need for live translation. */
fun LiveTranslation.expectedPairs(c: Context): List<Pair<String, String>> {
    val p = c.settings()
    return expectedPairs(p[Prefs.LIVE_TR_INTO], p[Prefs.TR_SOURCE])
}

/**
 * Live translation can work for these settings now: some engine can translate a pair it will need (ML Kit for
 * English ↔ Chinese and most languages, fetched on first use; Opus-MT models already on the phone; OpenRouter set up).
 */
fun LiveTranslation.usable(c: Context): Boolean = expectedPairs(c).any { (s, t) ->
    TranslationRegistry.ALL.any { it.supports(s, t) && it.usable(c, s, t) }
}

/**
 * Opus-MT models live translation needs and only Opus-MT has on the phone (Malayalam): kept and downloaded once
 * "Live translation into" was picked by hand (a value carried over automatically never starts a big download).
 */
fun LiveTranslation.opusPairs(c: Context): List<String> {
    val s = c.settings()
    if (!s.contains(Prefs.LIVE_TR_INTO.key) || c.uiState().getBoolean(SettingsMigration.LIVE_TR_CARRIED, false)) return emptyList()
    return expectedPairs(c).filter { (s, t) -> TranslateConfig.opusOnly(s, t) != null }
        .flatMap { (s, t) -> OpusMt.route(s, t) ?: emptyList() }
}

/**
 * Live translation for one dictation transcript (the Short composer or a Long session). Finals are translated one by
 * one, in order, on the single translation thread (by whichever engine TranslationRegistry picks for the pair); with "Translate words before the sentence ends" on, a partial that
 * stays the same for the debounce time is translated too and replaced by its final. [onUpdate] gets the text to show
 * (empty: hide). The phone's engines work offline; OpenRouter only with its key, the user's OK and internet. Main thread only.
 */
class LiveTranslator(private val context: Context, private val onNotice: (String) -> Unit = {}, private val onUpdate: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val translator = NoteTranslator(context)
    private var segments = LiveTranslation.Segments()
    private val jobs = ArrayList<NoteTranslator.Job>()
    private var inflight = 0
    private val settled = ArrayList<() -> Unit>()
    @Volatile private var partialGen = 0
    private var partialTask: Runnable? = null
    private var muted = false
    private val noticed = HashSet<String>()

    val enabled: Boolean get() = context.liveTranslationInto() != LiveTranslation.OFF

    /** Start a transcript that already has [original] text with [translation] (a Long session being continued). */
    fun begin(original: String = "", translation: String = "") {
        cancel()
        segments = LiveTranslation.Segments(original, translation)
        noticed.clear()
        if (enabled) OpusPacks.sync(context) // a Malayalam model it needs starts downloading (no-op if it's there)
        show()
    }

    fun onFinal(text: String) {
        if (!enabled || text.isBlank()) return
        cancelPartialTask()
        val seq = segments.addFinal(text)
        partialGen = -1
        val dir = direction(text)
        if (dir == null) { segments.setTranslation(seq, null); show(); return }
        val (src, tgt) = dir
        inflight++
        jobs += translator.translateLive(text, src, tgt, notice = ::notice) { res ->
            segments.setTranslation(seq, res.takeIf { LiveTranslation.sane(text, it, tgt) })
            inflight--
            show()
            if (inflight == 0) runSettled()
        }
        show()
    }

    fun onPartial(text: String) {
        if (!enabled) return
        val p = context.settings()
        if (!p[Prefs.LIVE_TR_PARTIALS]) return
        val gen = segments.setPartial(text)
        partialGen = gen
        cancelPartialTask()
        if (text.isBlank()) { show(); return }
        val task = Runnable {
            val dir = direction(text) ?: return@Runnable
            val (src, tgt) = dir
            jobs += translator.translateLive(text, src, tgt, stale = { partialGen != gen }, notice = ::notice) { res ->
                if (segments.setPartialTranslation(gen, res.takeIf { LiveTranslation.sane(text, it, tgt) })) show()
            }
        }
        partialTask = task
        main.postDelayed(task, p[Prefs.LIVE_TR_PARTIAL_MS].toLong())
    }

    /** The mic stopped: an unfinished partial's translation goes (its words become a final, or are kept as typed). */
    fun dropPartial() { cancelPartialTask(); segments.clearPartial(); partialGen = -1; show() }

    /** What to save with the note: finished segments only. */
    fun finalTranslation(): String = segments.finalTranslation()

    /** Runs [block] once every final sent so far has its translation (now, if none are waiting). */
    fun whenSettled(block: () -> Unit) { if (inflight == 0) block() else settled += block }

    fun cancel() {
        cancelPartialTask()
        jobs.forEach { it.cancel() }; jobs.clear()
        inflight = 0
        settled.clear()
        partialGen = -1
    }

    private fun runSettled() { val s = settled.toList(); settled.clear(); s.forEach { it() } }
    private fun cancelPartialTask() { partialTask?.let { main.removeCallbacks(it) }; partialTask = null }
    private fun show() { if (!muted) onUpdate(segments.translation()) }

    /** Stop showing (the transcript was saved and the box cleared); translations still in flight still finish. */
    fun mute() { muted = true; cancelPartialTask() }

    private fun direction(text: String): Pair<String, String>? {
        val p = context.settings()
        return LiveTranslation.direction(text, p[Prefs.DG_LANGUAGE], p[Prefs.LIVE_TR_INTO], p[Prefs.TR_SOURCE]) { TranslateConfig.lang(it) != null }
    }

    /** Once per session per message: why a translation isn't showing yet (getting a language ready, or downloading). */
    private fun notice(message: String) { if (!muted && noticed.add(message)) onNotice(message) }

    companion object {
        /** Wide window (the unfolded Fold): side by side; otherwise original on top, translation under it. */
        fun sideBySide(c: Context, config: Configuration = c.resources.configuration): Boolean =
            config.screenWidthDp >= c.settings()[Prefs.LIVE_TR_SIDE_DP]

        /** Lays out [pair] (original, then translation) for the current width. Hides the translation when empty. */
        fun arrange(c: Context, pair: LinearLayout, original: View, translation: View, config: Configuration = c.resources.configuration) {
            val side = sideBySide(c, config) && translation.visibility == View.VISIBLE
            pair.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            for (v in listOf(original, translation)) {
                v.layoutParams = if (side) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }
}
