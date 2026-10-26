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
    private val main = Handler(Looper.getMainLooper())

    interface Callbacks {
        fun onStatus(message: String)
        /** [engine]: "Opus-MT" or "ML Kit", shown small on the panel. */
        fun onDone(translation: String, engine: String)
        fun onError(message: String)
    }

    fun interface Job { fun cancel() }

    fun supports(source: String, target: String): Boolean = source != target && code(source) != null && code(target) != null

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

    private fun work(text: String, source: String, target: String, cancelled: AtomicBoolean, callbacks: Callbacks,
                     post: (() -> Unit) -> Unit) {
        val src = code(source); val tgt = code(target)
        if (src == null || tgt == null) { post { callbacks.onError("This language pair isn't available") }; return }
        val route = OpusPacks.readyRoute(app, source, target)
        if (route != null) {
            // Opus-MT runs native code (ONNX Runtime) that can take the app down where no catch reaches; the guard
            // skips it on processors it's known to fault on, and after a run that died (OpusGuard).
            val guard = OpusPacks.guard(app)
            val blocked = guard.blocked()
            if (blocked == null) {
                post { callbacks.onStatus("Translating on this phone…") }
                val result = try { guard.run { opus(route, text) { cancelled.get() } } } catch (e: Throwable) {
                    Log.w(TAG, "Opus-MT failed; ML Kit takes over", e); releaseOpus(); null
                }
                if (cancelled.get()) return
                if (result != null) { post { callbacks.onDone(result, "Opus-MT") }; return }
            } else Log.i(TAG, "Opus-MT skipped ($blocked); ML Kit translates")
        }
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        try {
            val wifiOnly = app.settings()[Prefs.TR_WIFI_ONLY]
            post { callbacks.onStatus("Getting ready… the first time for ${TranslateConfig.languageName(source)} → " +
                "${TranslateConfig.languageName(target)} fetches a small language pack (~30 MB) once") }
            // On mobile data with Wi-Fi only on, ML Kit would just sit waiting; say so now rather than after 3 minutes.
            if (wifiOnly && metered() && !packsReady(src, tgt, cancelled)) post { callbacks.onStatus(WIFI_WAIT) }
            await(client.downloadModelIfNeeded(DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()), cancelled,
                if (wifiOnly) WIFI_WAIT else "Couldn't fetch the language pack. Check your connection and try again") ?: return
            post { callbacks.onStatus("Translating…") }
            val done = StringBuilder()
            for (p in TranslateConfig.chunks(text, TranslateConfig.CHUNK_CHARS)) {
                if (p.text.isNotBlank()) {
                    val t = await(client.translate(p.text), cancelled, "Couldn't translate this note") ?: return
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
    private fun packsReady(src: String, tgt: String, cancelled: AtomicBoolean): Boolean = runCatching {
        val mm = RemoteModelManager.getInstance()
        listOf(src, tgt).all { await(mm.isModelDownloaded(TranslateRemoteModel.Builder(it).build()), cancelled, "") == true }
    }.getOrDefault(false)

    private class TranslateException(message: String) : Exception(message)

    /** Waits for a Play-services Task on the worker thread; null if cancelled, throws after 3 minutes or on failure. */
    private fun <T> await(task: com.google.android.gms.tasks.Task<T>, cancelled: AtomicBoolean, failMsg: String): T? {
        val latch = CountDownLatch(1)
        task.addOnCompleteListener { latch.countDown() } // runs on the main thread; this worker just waits
        var waited = 0
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) return null
            if (++waited > 4 * 180) { Log.w(TAG, "ML Kit task still running after 3 minutes"); throw TranslateException(failMsg) }
        }
        if (!task.isSuccessful) { Log.w(TAG, "ML Kit task failed", task.exception); throw TranslateException(failMsg) }
        return task.result
    }

    companion object {
        private const val TAG = "DailyWhisper"

        /** One translation at a time. */
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "dw-translate") }

        /** Loaded Opus-MT models (at most the two of an English pivot), reused across taps; worker thread only. */
        private val loaded = LinkedHashMap<String, OpusMtModel>()

        private fun model(c: Context, pair: String): OpusMtModel = loaded.getOrPut(pair) {
            OpusMtModel(OpusPacks.dir(c, pair), Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }

        private fun releaseOpus() { loaded.values.forEach { runCatching { it.close() } }; loaded.clear() }

        /** Whole note through the route (one model, or source→English→target). Null if cancelled. */
        private fun NoteTranslator.opus(route: List<String>, text: String, cancelled: () -> Boolean): String? {
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
                        for (m in models) t = m.translate(t, cancelled) ?: return null
                        Log.i("DailyWhisper", "Opus-MT ${route.joinToString("+")}: ${s.text.length} chars in ${(System.nanoTime() - t0) / 1_000_000} ms")
                        done.append(t).append(if (s.sep.isEmpty() && spaced && i < parts.lastIndex) " " else s.sep)
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
