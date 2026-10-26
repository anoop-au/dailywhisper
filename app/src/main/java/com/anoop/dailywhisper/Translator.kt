package com.anoop.dailywhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device translation with Google ML Kit; note text never leaves the phone. Each language needs a ~30 MB pack,
 * fetched once on first use (or ahead of time by [prefetch] from setup). [translate] works on a background thread,
 * a line at a time so the note's layout comes back exactly, and reports on the main thread.
 */
class NoteTranslator(c: Context) {
    private val app: Context = c.applicationContext
    private val main = Handler(Looper.getMainLooper())

    interface Callbacks {
        fun onStatus(message: String)
        fun onDone(translation: String)
        fun onError(message: String)
    }

    fun interface Job { fun cancel() }

    fun supports(source: String, target: String): Boolean = source != target && code(source) != null && code(target) != null

    fun translate(text: String, source: String, target: String, callbacks: Callbacks): Job {
        val cancelled = AtomicBoolean(false)
        WORKER.execute {
            fun post(block: () -> Unit) = main.post { if (!cancelled.get()) block() }
            val src = code(source); val tgt = code(target)
            if (src == null || tgt == null) { post { callbacks.onError("This language pair isn't available") }; return@execute }
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            try {
                val wifiOnly = app.settings()[Prefs.TR_WIFI_ONLY]
                post { callbacks.onStatus("Getting ready… the first time for ${TranslateConfig.languageName(source)} → " +
                    "${TranslateConfig.languageName(target)} fetches a small language pack (~30 MB) once") }
                await(client.downloadModelIfNeeded(DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()), cancelled,
                    if (wifiOnly) "Waiting for Wi-Fi to fetch the language pack. Turn off \"Language packs on Wi-Fi only\" in Settings → Translation to use mobile data"
                    else "Couldn't fetch the language pack. Check your connection and try again") ?: return@execute
                post { callbacks.onStatus("Translating…") }
                val done = StringBuilder()
                for (p in TranslateConfig.chunks(text, TranslateConfig.CHUNK_CHARS)) {
                    if (p.text.isNotBlank()) {
                        val t = await(client.translate(p.text), cancelled, "Couldn't translate this note") ?: return@execute
                        done.append(TranslateConfig.cleanReply(t))
                    } else done.append(p.text)
                    done.append(p.sep)
                }
                val result = done.toString()
                post { callbacks.onDone(result) }
            } catch (e: TranslateException) {
                post { callbacks.onError(e.message ?: "Couldn't translate") }
            } catch (e: Throwable) {
                post { callbacks.onError("Couldn't translate this note") }
            } finally {
                client.close()
            }
        }
        return Job { cancelled.set(true) }
    }

    private class TranslateException(message: String) : Exception(message)

    /** Waits for a Play-services Task on the worker thread; null if cancelled, throws after 3 minutes or on failure. */
    private fun <T> await(task: com.google.android.gms.tasks.Task<T>, cancelled: AtomicBoolean, failMsg: String): T? {
        val latch = CountDownLatch(1)
        task.addOnCompleteListener { latch.countDown() } // runs on the main thread; this worker just waits
        var waited = 0
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) return null
            if (++waited > 4 * 180) throw TranslateException(failMsg)
        }
        if (!task.isSuccessful) throw TranslateException(failMsg)
        return task.result
    }

    companion object {
        /** One translation at a time. */
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "dw-translate") }

        private fun code(c: String): String? = TranslateConfig.lang(c)?.code?.let { TranslateLanguage.fromLanguageTag(it) }

        /** Setup: fetch the chosen pair's packs now, so the first tap is quick. Fire and forget. */
        fun prefetch(c: Context, source: String, target: String) {
            if (source == target) return
            val src = code(source) ?: return
            val tgt = code(target) ?: return
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            val cond = DownloadConditions.Builder().apply { if (c.settings()[Prefs.TR_WIFI_ONLY]) requireWifi() }.build()
            client.downloadModelIfNeeded(cond).addOnCompleteListener { client.close() }
        }
    }
}
