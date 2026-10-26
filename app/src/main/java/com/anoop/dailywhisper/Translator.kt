package com.anoop.dailywhisper

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
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
 * One offline translator. Both run fully on the phone; note text never leaves it.
 * [translate] runs on a background thread and reports on the main thread.
 */
interface Translator {
    val backend: String
    /** Short name for the result panel. */
    val label: String
    fun supports(source: String, target: String): Boolean

    /** [onStatus]: preparing messages ("Getting the English–Hindi pack…"); [onPartial]: text so far (Hy-MT streams). */
    fun translate(text: String, source: String, target: String, callbacks: Callbacks): Job

    interface Callbacks {
        fun onStatus(message: String) {}
        fun onPartial(textSoFar: String) {}
        fun onDone(translation: String)
        fun onError(message: String)
    }

    fun interface Job { fun cancel() }
}

/** Splits the note into lines / sentence groups, translates each in turn, and puts the layout back. */
internal abstract class ChunkedTranslator(private val maxChars: Int) : Translator {
    private val main = Handler(Looper.getMainLooper())

    /** Blocking, on the worker thread. Return null when cancelled. */
    protected abstract fun translateChunk(text: String, source: String, target: String, cancelled: AtomicBoolean,
                                          status: (String) -> Unit, partial: (String) -> Unit): String?

    override fun translate(text: String, source: String, target: String, callbacks: Translator.Callbacks): Translator.Job {
        val cancelled = AtomicBoolean(false)
        WORKER.execute {
            try {
                val pieces = TranslateConfig.chunks(text, maxChars)
                val done = StringBuilder()
                for (p in pieces) {
                    if (cancelled.get()) return@execute
                    if (p.text.isNotBlank()) {
                        val before = done.toString()
                        val t = translateChunk(p.text, source, target, cancelled,
                            status = { m -> main.post { if (!cancelled.get()) callbacks.onStatus(m) } },
                            partial = { s -> main.post { if (!cancelled.get()) callbacks.onPartial(before + s) } },
                        ) ?: run {
                            // Not cancelled by the user: the model was unloaded (app went to the background) or failed.
                            if (!cancelled.get()) main.post { callbacks.onError("Translation stopped. Tap the note to try again") }
                            return@execute
                        }
                        done.append(TranslateConfig.cleanReply(t))
                    } else done.append(p.text)
                    done.append(p.sep)
                    val so = done.toString()
                    main.post { if (!cancelled.get()) callbacks.onPartial(so) }
                }
                val result = done.toString()
                main.post { if (!cancelled.get()) callbacks.onDone(result) }
            } catch (e: TranslateException) {
                main.post { if (!cancelled.get()) callbacks.onError(e.message ?: "Couldn't translate") }
            } catch (e: Throwable) {
                main.post { if (!cancelled.get()) callbacks.onError("Couldn't translate this note") }
            }
        }
        return Translator.Job { cancelled.set(true); onCancel() }
    }

    protected open fun onCancel() {}

    companion object {
        /** One translation at a time (the model uses all the CPU threads it's given). */
        val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "dw-translate").apply { priority = Thread.NORM_PRIORITY - 1 } }
    }
}

class TranslateException(message: String) : Exception(message)

/** Google ML Kit on-device translation. Each language needs a ~30 MB pack, fetched once on first use. */
internal class MlKitTranslator(c: Context) : ChunkedTranslator(TranslateConfig.MLKIT_CHUNK_CHARS) {
        private val app: Context = c.applicationContext
        override val backend = TranslateConfig.BACKEND_MLKIT
        override val label = "Built-in translator"

        override fun supports(source: String, target: String): Boolean =
            source != target && code(source) != null && code(target) != null

        private fun code(c: String): String? = TranslateConfig.lang(c)?.mlkit?.let { TranslateLanguage.fromLanguageTag(it) }

        override fun translateChunk(text: String, source: String, target: String, cancelled: AtomicBoolean,
                                    status: (String) -> Unit, partial: (String) -> Unit): String? {
            val src = code(source) ?: throw TranslateException("The built-in translator can't read ${TranslateConfig.languageName(source)}")
            val tgt = code(target) ?: throw TranslateException("The built-in translator can't write ${TranslateConfig.languageName(target)}")
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            try {
                val cond = DownloadConditions.Builder().apply { if (app.settings()[Prefs.TR_WIFI_ONLY]) requireWifi() }.build()
                status("Getting ready… the first time for ${TranslateConfig.languageName(source)} → " +
                    "${TranslateConfig.languageName(target)} fetches a small language pack (~30 MB) once")
                await(client.downloadModelIfNeeded(cond), cancelled, if (app.settings()[Prefs.TR_WIFI_ONLY])
                    "Waiting for Wi-Fi to fetch the language pack. Turn off \"Wi-Fi only\" in Settings → Translation to use mobile data"
                else "Couldn't fetch the language pack. Check your connection and try again") ?: return null
                return await(client.translate(text), cancelled, "Couldn't translate this note")
            } finally {
                client.close()
            }
        }

        /** Waits for a Play-services Task on the worker thread, giving up after a while. */
        private fun <T> await(task: com.google.android.gms.tasks.Task<T>, cancelled: AtomicBoolean, failMsg: String): T? {
            val latch = CountDownLatch(1)
            task.addOnCompleteListener { latch.countDown() } // runs on the main thread; this worker just waits
            var waited = 0
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) return null
                if (++waited > 4 * 180) throw TranslateException(failMsg) // 3 minutes
            }
            if (!task.isSuccessful) throw TranslateException(failMsg)
            return task.result
        }

    companion object {
        /** Setup: fetch the chosen pair's packs now, so the first tap is quick. Fire and forget. */
        fun prefetch(c: Context, source: String, target: String) {
            val src = TranslateConfig.lang(source)?.mlkit ?: return
            val tgt = TranslateConfig.lang(target)?.mlkit ?: return
            if (src == tgt) return
            val client = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: return)
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(tgt) ?: return).build())
            val cond = DownloadConditions.Builder().apply { if (c.settings()[Prefs.TR_WIFI_ONLY]) requireWifi() }.build()
            client.downloadModelIfNeeded(cond).addOnCompleteListener { client.close() }
        }
    }
}

/** Tencent Hy-MT1.5-1.8B (1.25-bit), run by llama.cpp on the CPU. Needs the one-time ~440 MB download. */
internal class HyMtTranslator(c: Context) : ChunkedTranslator(TranslateConfig.HYMT_CHUNK_CHARS) {
        private val app: Context = c.applicationContext
        override val backend = TranslateConfig.BACKEND_HYMT
        override val label = "Hy-MT (offline model)"

        override fun supports(source: String, target: String): Boolean =
            ModelDownloads.isReady(app) && TranslateConfig.supports(TranslateConfig.BACKEND_HYMT, target)

        override fun translateChunk(text: String, source: String, target: String, cancelled: AtomicBoolean,
                                    status: (String) -> Unit, partial: (String) -> Unit): String? {
            if (!HyMtEngine.isLoaded()) status("Waking up the translation model…")
            val engine = HyMtEngine.get(app) ?: throw TranslateException("Couldn't load the translation model. Try again, or delete and re-download it in Settings")
            val prompt = TranslateConfig.hymtPrompt(text, source, target)
            // Room for the reply: translations can be ~3x the source tokens; the engine also caps at the context.
            return engine.generate(prompt, maxNew = TranslateConfig.CONTEXT_TOKENS, cancelled = cancelled, partial = partial)
        }

        override fun onCancel() = HyMtEngine.cancel()
}

/** Picks the translator for the saved choice, falling back to built-in when Hy-MT isn't ready or can't do the pair. */
object Translators {
    data class Pick(val translator: Translator?, val note: String?)

    fun pick(c: Context, source: String, target: String): Pick {
        val backend = c.settings()[Prefs.TR_BACKEND]
        val ml = MlKitTranslator(c)
        val hy = HyMtTranslator(c)
        val tgtName = TranslateConfig.languageName(target)
        if (backend == TranslateConfig.BACKEND_HYMT) {
            if (hy.supports(source, target)) return Pick(hy, null)
            val why = when {
                !ModelDownloads.isReady(c) -> ModelDownloads.state(c).let { s ->
                    if (s.status == ModelDownloads.Status.DOWNLOADING) "The better model is still downloading (${s.percent}%)"
                    else "The better model isn't downloaded yet" }
                else -> "The better model doesn't do $tgtName"
            }
            return if (ml.supports(source, target)) Pick(ml, "$why, so this used the built-in translator.")
            else Pick(null, "$why, and the built-in translator can't do this pair either.")
        }
        if (ml.supports(source, target)) return Pick(ml, null)
        if (hy.supports(source, target)) return Pick(hy, "The built-in translator can't do this pair, so this used Hy-MT.")
        if (source == target) return Pick(null, "Your notes are already in $tgtName. Change the languages in Settings → Translation.")
        return Pick(null, "The built-in translator can't do ${TranslateConfig.languageName(source)} → $tgtName." +
            if (TranslateConfig.supports(TranslateConfig.BACKEND_HYMT, target)) " The better model can: download it in Settings → Translation." else "")
    }
}

/** The loaded Hy-MT model (one per app process, ~0.5 GB mapped). Released when the app goes to the background. */
object HyMtEngine {
    @Volatile private var handle = 0L
    private var threads = 0
    private val lock = Any()
    private var registered = false

    fun isLoaded() = handle != 0L

    fun get(c: Context): HyMtEngine? = synchronized(lock) {
        val want = c.settings()[Prefs.HYMT_THREADS]
        if (handle != 0L && threads != want) { HyMtNative.free(handle); handle = 0 }
        if (handle == 0L) {
            if (!HyMtNative.available) return null
            val f = ModelDownloads.modelFile(c)
            if (GgufPatch.unpatched(f) > 0) GgufPatch.patch(f) // older download or interrupted patch
            handle = HyMtNative.load(f.absolutePath, want, TranslateConfig.CONTEXT_TOKENS)
            threads = want
            if (!registered) {
                registered = true
                c.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) release() }
                    override fun onConfigurationChanged(newConfig: Configuration) {}
                    @Deprecated("Deprecated in Java") override fun onLowMemory() = release()
                })
            }
        }
        if (handle == 0L) null else this
    }

    /** Holds the lock for the whole run, so [release] can't free the model mid-translation (it cancels first). */
    fun generate(prompt: String, maxNew: Int, cancelled: AtomicBoolean, partial: (String) -> Unit): String? = synchronized(lock) {
        val h = handle
        if (h == 0L || cancelled.get()) return null
        val bytes = HyMtNative.generate(h, prompt.toByteArray(Charsets.UTF_8), maxNew, (System.nanoTime() and 0x7fffffff).toInt(),
            object : HyMtNative.Listener { override fun onPartial(utf8: ByteArray) = partial(String(utf8, Charsets.UTF_8)) })
        bytes?.let { String(it, Charsets.UTF_8) }
    }

    fun cancel() { val h = handle; if (h != 0L) HyMtNative.cancel(h) }

    /** Frees the model memory (not while a translation is running: the worker re-loads on next use). */
    fun release() {
        Thread {
            cancel()
            synchronized(lock) { if (handle != 0L) { HyMtNative.free(handle); handle = 0 } }
        }.start()
    }
}

/** JNI bridge to app/src/main/cpp (hymt_jni.cpp). Text crosses as UTF-8 bytes. */
object HyMtNative {
    val available: Boolean = runCatching { System.loadLibrary("dwhymt") }.isSuccess

    interface Listener { fun onPartial(utf8: ByteArray) }

    external fun load(path: String, threads: Int, nCtx: Int): Long
    external fun generate(handle: Long, prompt: ByteArray, maxNew: Int, seed: Int, listener: Listener?): ByteArray?
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}
