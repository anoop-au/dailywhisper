package com.anoop.dailywhisper

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device translation; note text never leaves the phone. Opus-MT (OpusMt.kt) when the chosen pair's models are
 * downloaded (OpusPacks), else Google ML Kit, whose ~30 MB per-language pack is fetched once on first use (or ahead of
 * time by [prefetch] from setup). If Opus-MT fails for any reason, ML Kit does the note instead. [translate] works on
 * a background thread, a line at a time so the note's layout comes back exactly, and reports on the main thread.
 */
class NoteTranslator(c: Context) {
    private val app: Context = c.applicationContext
    /** The app context (for translation providers). */
    val context: Context get() = app
    private val main = Handler(Looper.getMainLooper())

    interface Callbacks {
        fun onStatus(message: String)
        /** [engine]: "Opus-MT" or "ML Kit", shown small on the panel. */
        fun onDone(translation: String, engine: String)
        fun onError(message: String)
    }

    fun interface Job { fun cancel() }

    /** Both languages are ones the app offers (Malayalam too: Opus-MT only, see [work]) and they differ. */
    fun supports(source: String, target: String): Boolean =
        source != target && TranslateConfig.lang(source) != null && TranslateConfig.lang(target) != null

    fun translate(text: String, source: String, target: String, callbacks: Callbacks): Job {
        val cancelled = AtomicBoolean(false)
        WORKER.execute {
            fun post(block: () -> Unit) = main.post { if (!cancelled.get()) block() }
            // Nothing may escape this thread: an uncaught exception here would close the whole app.
            try {
                work(text, source, target, cancelled, callbacks, ::post)
            } catch (e: Throwable) {
                Log.w(TAG, "Translation failed", e)
                post { callbacks.onError("Couldn't translate this note") }
            }
        }
        return Job { cancelled.set(true) }
    }

    /**
     * Live dictation: one spoken segment, on the same single translation thread (segments finish in the order they were
     * sent; loaded models are reused). The engines come from TranslationRegistry.route for the pair (OpenRouter when
     * set up and preferred, Opus-MT when its models are on the phone, ML Kit, whose pack is fetched once in the
     * background while this segment shows nothing); the first answer wins, a failure falls through to the next.
     * [stale] is checked just before the work starts (a superseded partial is skipped). [notice] (main thread) says in
     * plain words why nothing shows yet. [done] runs on the main thread with the translation, or null.
     */
    fun translateLive(text: String, source: String, target: String, stale: () -> Boolean = { false },
                      notice: (String) -> Unit = {}, done: (String?) -> Unit): Job {
        val cancelled = AtomicBoolean(false)
        WORKER.execute {
            var out: String? = null
            var why: String? = null
            try {
                if (!cancelled.get() && !stale()) {
                    val preferOnline = app.settings().getBoolean(OpenRouterTranslation.KEY_FIRST, true)
                    val online = TranslationRegistry.ALL.any { it.online && it.usable(app, source, target) } && app.isOnline()
                    val route = TranslationRegistry.route(source, target, online, preferOnline, { it.usable(app, source, target) })
                    for (p in route) {
                        if (cancelled.get()) break
                        val r = try { p.translate(this, text, source, target) { cancelled.get() } } catch (e: Throwable) {
                            Log.w(TAG, "Live ${p.label} failed", e); null
                        }
                        if (r?.text != null) { out = r.text; why = null; break }
                        if (why == null) why = r?.notice
                    }
                    if (out == null && why == null && route.isEmpty())
                        why = OpusPacks.unavailable(app, source, target)?.let { "Live translation: $it" }
                            ?: "Live translation: ${TranslateConfig.languageName(source)} → ${TranslateConfig.languageName(target)} " +
                                "needs OpenRouter (Settings → Providers)"
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Live translation failed", e)
            }
            val result = out
            val message = why
            main.post {
                if (cancelled.get()) return@post
                if (message != null) notice(message)
                done(result)
            }
        }
        return Job { cancelled.set(true) }
    }

    /** Opus-MT for one live segment (translation thread): null when its models aren't ready, it's held back, or it failed. */
    internal fun liveOpus(text: String, source: String, target: String, cancelled: () -> Boolean): String? {
        val route = OpusPacks.readyRoute(app, source, target) ?: return null
        val guard = OpusPacks.guard(app)
        if (guard.blocked() != null) return null
        val beams = app.settings()[Prefs.LIVE_TR_BEAMS]
        return try { guard.run { opus(route, text, beams, cancelled) } } catch (e: Throwable) {
            Log.w(TAG, "Live Opus-MT failed", e); releaseOpus(); null
        }
    }

    /** ML Kit for one live segment (translation thread): (translation, null), or (null, why) while its pack is fetched. */
    internal fun mlKitLive(text: String, source: String, target: String, cancelled: () -> Boolean): Pair<String?, String?> {
        val src = code(source) ?: return null to null
        val tgt = code(target) ?: return null to null
        val key = "$src-$tgt"
        val client = synchronized(liveClients) {
            liveClients.getOrPut(key) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            }
        }
        val pair = "${TranslateConfig.languageName(source)} → ${TranslateConfig.languageName(target)}"
        if (!packsReady(src, tgt, cancelled)) {
            val wifiOnly = app.settings()[Prefs.TR_WIFI_ONLY]
            if (liveFetching.add(key)) {
                val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
                client.downloadModelIfNeeded(cond).addOnCompleteListener { liveFetching.remove(key) }
            }
            return null to if (wifiOnly && metered()) "Live translation: $pair starts once its language pack (~30 MB) " +
                "downloads. Waiting for Wi-Fi (Settings → Translation → Download on Wi-Fi only)"
            else "Live translation: getting $pair ready (a one-time ~30 MB download). It starts on its own when that's done"
        }
        val r = await(client.translate(text), cancelled, "Couldn't translate") ?: return null to null
        return r.value?.let { TranslateConfig.cleanReply(it) } to null
    }

    private fun work(text: String, source: String, target: String, cancelled: AtomicBoolean, callbacks: Callbacks,
                     post: (() -> Unit) -> Unit) {
        if (!supports(source, target)) { post { callbacks.onError("This language pair isn't available") }; return }
        var opusFailed = false
        val route = OpusPacks.readyRoute(app, source, target)
        if (route != null) {
            // Opus-MT runs native code (ONNX Runtime) that can take the app down where no catch reaches; the guard
            // skips it on processors it's known to fault on, and after a run that died (OpusGuard).
            val guard = OpusPacks.guard(app)
            val blocked = guard.blocked()
            if (blocked == null) {
                post { callbacks.onStatus("Translating on this phone…") }
                val beams = app.settings()[Prefs.OPUS_BEAMS]
                val result = try { guard.run { opus(route, text, beams) { cancelled.get() } } } catch (e: Throwable) {
                    Log.w(TAG, "Opus-MT failed; ML Kit takes over", e); releaseOpus(); opusFailed = true; null
                }
                if (cancelled.get()) return
                if (result != null) { post { callbacks.onDone(result, "Opus-MT") }; return }
            } else Log.i(TAG, "Opus-MT skipped ($blocked); ML Kit translates")
        }
        // ML Kit has no Malayalam: say plainly why it can't be done now (not downloaded, or Opus-MT can't run here).
        if (TranslateConfig.opusOnly(source, target) != null) {
            val why = if (opusFailed) "Couldn't translate this note" else OpusPacks.unavailable(app, source, target) ?: "Couldn't translate this note"
            post { callbacks.onError(why) }
            return
        }
        val src = code(source); val tgt = code(target)
        if (src == null || tgt == null) { post { callbacks.onError("This language pair isn't available") }; return }
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        try {
            val wifiOnly = app.settings()[Prefs.TR_WIFI_ONLY]
            post { callbacks.onStatus("Getting ready… the first time for ${TranslateConfig.languageName(source)} → " +
                "${TranslateConfig.languageName(target)} fetches a small language pack (~30 MB) once") }
            // On mobile data with Wi-Fi only on, ML Kit would just sit waiting; say so now rather than after 3 minutes.
            if (wifiOnly && metered() && !packsReady(src, tgt) { cancelled.get() }) post { callbacks.onStatus(WIFI_WAIT) }
            // Task<Void>: a finished download has a null result, so only a null Done means cancelled.
            await(client.downloadModelIfNeeded(DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()), { cancelled.get() },
                if (wifiOnly) WIFI_WAIT else "Couldn't fetch the language pack. Check your connection and try again") ?: return
            post { callbacks.onStatus("Translating…") }
            val done = StringBuilder()
            for (p in TranslateConfig.chunks(text, TranslateConfig.CHUNK_CHARS)) {
                if (p.text.isNotBlank()) {
                    val t = (await(client.translate(p.text), { cancelled.get() }, "Couldn't translate this note") ?: return).value
                        ?: throw TranslateException("Couldn't translate this note")
                    done.append(TranslateConfig.cleanReply(t))
                } else done.append(p.text)
                done.append(p.sep)
            }
            val result = done.toString()
            post { callbacks.onDone(result, "ML Kit") }
        } catch (e: TranslateException) {
            post { callbacks.onError(e.message ?: "Couldn't translate") }
        } catch (e: Throwable) {
            Log.w(TAG, "ML Kit failed", e)
            post { callbacks.onError("Couldn't translate this note") }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun metered(): Boolean =
        runCatching { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    /** Both ML Kit language packs already on the phone (no download needed). */
    private fun packsReady(src: String, tgt: String, cancelled: () -> Boolean): Boolean = runCatching {
        val mm = RemoteModelManager.getInstance()
        listOf(src, tgt).all { await(mm.isModelDownloaded(TranslateRemoteModel.Builder(it).build()), cancelled, "")?.value == true }
    }.getOrDefault(false)

    private class TranslateException(message: String) : Exception(message)

    /** A finished Task's result. Its [value] may itself be null (downloadModelIfNeeded is a Task<Void>). */
    private class Done<T>(val value: T?)

    /**
     * Waits for a Play-services Task on the worker thread: [Done] when it succeeded, null only if cancelled; throws
     * after 3 minutes or on failure. (Returning the bare result made a finished download, whose result is null, look
     * like a cancel: translation stopped silently at "Getting ready…" and ML Kit never translated.)
     */
    private fun <T> await(task: com.google.android.gms.tasks.Task<T>, cancelled: () -> Boolean, failMsg: String): Done<T>? {
        val latch = CountDownLatch(1)
        task.addOnCompleteListener { latch.countDown() } // runs on the main thread; this worker just waits
        var waited = 0
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled()) return null
            if (++waited > 4 * 180) { Log.w(TAG, "ML Kit task still running after 3 minutes"); throw TranslateException(failMsg) }
        }
        if (!task.isSuccessful) { Log.w(TAG, "ML Kit task failed", task.exception); throw TranslateException(failMsg) }
        return Done(task.result)
    }

    companion object {
        private const val TAG = "DailyWhisper"

        /** One translation at a time. */
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "dw-translate") }

        /** ML Kit clients for live translation, by pair, kept while the app runs; and the pairs being fetched. */
        private val liveClients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        private val liveFetching: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** Loaded Opus-MT models (at most the two of an English pivot), reused across taps; worker thread only. */
        private val loaded = LinkedHashMap<String, OpusMtModel>()

        private fun model(c: Context, pair: String): OpusMtModel = loaded.getOrPut(pair) {
            OpusMtModel(OpusPacks.dir(c, pair), Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }

        private fun releaseOpus() { loaded.values.forEach { runCatching { it.close() } }; loaded.clear() }

        /** Whole note through the route (one model, or source→English→target). Null if cancelled. */
        private fun NoteTranslator.opus(route: List<String>, text: String, beams: Int, cancelled: () -> Boolean): String? {
            loaded.keys.filter { it !in route }.forEach { loaded.remove(it)?.close() }
            val models = route.map { model(app, it) }
            val done = StringBuilder()
            for (p in TranslateConfig.chunks(text, OpusMt.CHUNK_CHARS)) {
                if (p.text.isNotBlank()) {
                    // A sentence at a time: given several, these models translate the first and drop the rest.
                    val spaced = route.last().substringAfter('-') !in setOf("zh", "ja")
                    val parts = TranslateConfig.sentences(p.text)
                    for ((i, s) in parts.withIndex()) {
                        if (s.text.isBlank()) { done.append(s.text).append(s.sep); continue }
                        val t0 = System.nanoTime()
                        var t = s.text
                        for (m in models) t = m.translate(t, beams, cancelled) ?: return null
                        if (!spaced) t = TranslateConfig.cjkEnd(s.text, t)
                        Log.i("DailyWhisper", "Opus-MT ${route.joinToString("+")}: ${s.text.length} chars in ${(System.nanoTime() - t0) / 1_000_000} ms ($beams beams)")
                        done.append(t).append(TranslateConfig.sentenceSep(s.sep, spaced, i < parts.lastIndex))
                    }
                } else done.append(p.text)
                done.append(p.sep)
            }
            return done.toString()
        }

        private const val WIFI_WAIT = "Waiting for Wi-Fi to fetch the language pack (~30 MB). To use mobile data, " +
            "turn off \"Download on Wi-Fi only\" in Settings → Translation"

        private fun code(c: String): String? = TranslateConfig.lang(c)?.code?.let { TranslateLanguage.fromLanguageTag(it) }

        /** Setup: fetch the chosen pair's packs now, so the first tap is quick. Fire and forget. */
        fun prefetch(c: Context, source: String, target: String) = runCatching { prefetchPacks(c, source, target) }

        private fun prefetchPacks(c: Context, source: String, target: String) {
            if (source == target) return
            val src = code(source) ?: return
            val tgt = code(target) ?: return
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            val cond = DownloadConditions.Builder().apply { if (c.settings()[Prefs.TR_WIFI_ONLY]) requireWifi() }.build()
            client.downloadModelIfNeeded(cond).addOnCompleteListener { client.close() }
        }
    }
}
