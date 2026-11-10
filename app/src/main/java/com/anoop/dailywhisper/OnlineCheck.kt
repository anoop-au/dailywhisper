package com.anoop.dailywhisper

import android.content.Context
import android.os.SystemClock

/**
 * Settings → Insights → Test summary & translation: one tiny request ("Reply with OK", never a note) to each service
 * that can write summaries and translate (AssemblyAI's LLM Gateway, OpenRouter), and plain lines on what works, what's
 * missing (switched off, no key, no OK) and why a request failed (HTTP code and the service's own short reason).
 * Never shows or logs keys. Background thread only.
 */
object OnlineCheck {
    /** Every line, for [c] now (runs the requests; takes up to the summary wait per service). */
    fun run(c: Context, chat: List<ChatProvider> = InsightsMaker.testProviders
        ?: c.translationOrder().filterIsInstance<ChatProvider>()): List<String> {
        if (InsightsMaker.testProviders == null && !c.isOnline()) return listOf("No internet right now. Try again when you're online.")
        val lines = chat.map { "${it.label}: ${line(c, it)}" }.toMutableList()
        if (SarvamTranslation.usable(c, "en", "hi")) lines += "${SarvamTranslation.label}: translates Indian languages only. It can't write summaries"
        val writer = InsightsMaker(c).providers().firstOrNull()
        lines += if (writer == null) "Summaries: nothing set up can write them. Set up OpenRouter, or AssemblyAI with a paid account (Settings → Providers)"
            else "Summaries and online translation use ${writer.label} first"
        return lines
    }

    /** One service's line: what's missing, or the result of one tiny request. */
    fun line(c: Context, p: ChatProvider): String {
        if (p is TranslationProvider) {
            if (!c.settings().getBoolean(p.useKey, true)) return "switched off (Settings → Providers → ${p.label})"
            if (p.keyName?.let { SecretStore.has(c, it) } == false) return "no key (Settings → Providers → ${p.label})"
            if (!p.chatUsable(c)) return "needs your OK to send text there (Settings → Providers → ${p.label})"
        } else if (!p.chatUsable(c)) return "not set up"
        val t0 = SystemClock.elapsedRealtime()
        val reply = try { p.chat(c, "Reply with the single word OK.", "OK", 5) } catch (e: Throwable) { null }
        val s = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
        if (reply != null) return "works ($s s)"
        val f = NetDiag.last(p.label) ?: return "no answer"
        val why = NetDiag.plain(f, c.settings()[Prefs.INSIGHTS_TIMEOUT])
        val server = f.message.takeIf { it.isNotBlank() }?.let { " – \"$it\"" }.orEmpty()
        val hint = if (p === AssemblyAiTranslation && f.status in setOf(401, 402, 403))
            ". AssemblyAI's LLM Gateway needs a paid account (a free account can transcribe, but not translate or summarise)" else ""
        return "failed: $why$server$hint"
    }
}
