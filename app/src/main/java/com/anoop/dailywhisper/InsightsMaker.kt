package com.anoop.dailywhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes a Long note's [Insights] online, on its own background thread (never the mic's or the live translator's), with
 * the chat-capable translators in the translation order (AssemblyAI's LLM Gateway, then OpenRouter by default) and the
 * OK already given for them. The first usable answer wins; a failure falls through to the next. No provider name ever
 * reaches the screen: without one set up (or without internet) the Insights card just stays hidden.
 */
class InsightsMaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun interface Job { fun cancel() }

    /** The ones that can answer now, in the user's translation order. */
    fun providers(): List<ChatProvider> = app.translationOrder().filterIsInstance<ChatProvider>().filter { it.chatUsable(app) }

    /** Insights are on and something can make them now. */
    fun canMake(): Boolean = app.settings()[Prefs.INSIGHTS] && app.isOnline() && providers().isNotEmpty()

    /**
     * Insights for [text] in [lang]: made from the note, or (cheaper) [from] another language's entry translated.
     * [done] runs on the main thread with the item, or null.
     */
    fun make(text: String, lang: String, from: Insights.Item?, done: (Insights.Item?) -> Unit): Job {
        val cancelled = AtomicBoolean(false)
        WORKER.execute {
            var out: Insights.Item? = null
            if (!cancelled.get()) for (p in providers()) {
                if (cancelled.get()) break
                val reply = try {
                    if (from != null) p.chat(app, Insights.translatePrompt(lang), from.toJson().toString(), Insights.MAX_TOKENS)
                    else p.chat(app, Insights.makePrompt(lang), Insights.input(text), Insights.MAX_TOKENS)
                } catch (e: Throwable) { Log.w(TAG, "Insights: ${p.label} failed: ${e.javaClass.simpleName}"); null }
                out = Insights.parse(reply, lang)
                if (out != null) break
                Log.w(TAG, "Insights: ${p.label} gave nothing usable")
            }
            val result = out
            main.post { if (!cancelled.get()) done(result) }
        }
        return Job { cancelled.set(true) }
    }

    companion object {
        private const val TAG = "DailyWhisper"
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "insights").apply { isDaemon = true } }
    }
}
