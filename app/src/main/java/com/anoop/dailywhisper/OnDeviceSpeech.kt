package com.anoop.dailywhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Speech slot 3, "On this phone": speech-to-text with Sherpa-ONNX on the app's own ONNX Runtime 1.28.0, from a pack in
 * SpeechPacks. The audio never leaves the phone and nothing is logged. Two kinds of pack:
 * - live (English): a streaming recogniser; words appear as you speak and a sentence closes after the sentence pause.
 * - sentences (Malayalam, all languages): the Silero voice-activity detector cuts the speech at each pause (the same
 *   sentence pause setting), then the whole sentence is recognised at once. While you speak, "…" shows.
 * A [Runner] does the work on its own thread, so the mic is never held up by a slow sentence. Its whole run sits inside
 * the crash guard ([guard], the same mechanism as Opus-MT's): if the native code ever takes the app down, On this phone
 * stays off until the app is updated or the user taps "Try again" on its Settings page.
 */
object OnDeviceSpeech {
    const val DEFAULT_THREADS = 2
    const val MAX_THREADS = 4
    const val DEFAULT_MAX_SENTENCE_S = 20
    const val SAMPLE_RATE = 16000

    /** The crash guard for on-device speech (its own marker files; the processor check is OpusGuard's, see there). */
    fun guard(c: Context): OpusGuard {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0L)
        return OpusGuard(File(c.noBackupFilesDir, "speech-guard"), version, OpusPacks.cpuFeatures)
    }

    /** What a session needs to start on-device speech, worked out on the main thread. */
    class Choice(val pack: SpeechPackCatalog.Pack, val dir: File, val threads: Int, val pauseMs: Int, val maxSentenceS: Int)

    fun choice(c: Context, language: String): Choice? {
        val pack = SpeechPackCatalog.choose(language, SpeechPacks.installed(c)) ?: return null
        val p = c.settings()
        return Choice(pack, SpeechPacks.dir(c, pack.id), p[Prefs.OD_THREADS], p[Prefs.PAUSE_MS], p[Prefs.OD_MAX_SENTENCE])
    }

    /** One loaded pack. Not thread-safe: only one [Runner] uses it at a time. */
    interface Engine {
        val key: String
        fun accept(samples: FloatArray, out: (TranscriptParser.Update) -> Unit)
        /** End of the audio: the last words come out, and the engine is ready for the next session. */
        fun finish(out: (TranscriptParser.Update) -> Unit)
        fun release()
    }

    private val lock = Any()
    private var cached: Engine? = null // keeps the last pack loaded between sessions (loading takes a second or two)

    private fun keyOf(ch: Choice) = "${ch.pack.id}/${ch.threads}/${ch.pauseMs}/${ch.maxSentenceS}"

    private fun engine(ch: Choice): Engine = synchronized(lock) {
        cached?.takeIf { it.key == keyOf(ch) }?.let { return it }
        cached?.release(); cached = null
        val e = if (ch.pack.mode == SpeechPackCatalog.Mode.LIVE) LiveEngine(ch, keyOf(ch)) else SentenceEngine(ch, keyOf(ch))
        cached = e
        e
    }

    /** Frees a pack's memory (pack deleted, or Android is short of memory). */
    fun release(packId: String? = null) = synchronized(lock) {
        if (packId == null || cached?.key?.startsWith("$packId/") == true) { cached?.release(); cached = null }
    }

    private class LiveEngine(private val ch: Choice, override val key: String) : Engine {
        private fun f(name: String) = File(ch.dir, name).absolutePath
        private val rec = OnlineRecognizer(null, OnlineRecognizerConfig(
            featConfig = FeatureConfig(SAMPLE_RATE, 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(f("encoder.onnx"), f("decoder.onnx"), f("joiner.onnx")),
                tokens = f("tokens.txt"), numThreads = ch.threads),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),                          // long silence with nothing said
                rule2 = EndpointRule(true, ch.pauseMs / 1000f, 0f),              // the sentence pause setting
                rule3 = EndpointRule(false, 0f, ch.maxSentenceS.toFloat())),     // longest sentence
            enableEndpoint = true,
        ))
        private var stream: OnlineStream = rec.createStream()
        private var last = ""

        override fun accept(samples: FloatArray, out: (TranscriptParser.Update) -> Unit) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            if (rec.isEndpoint(stream)) {
                out(TranscriptParser.Update(OnDeviceText.tidy(text, final = true, english = true), true))
                rec.reset(stream); last = ""
            } else if (text != last) {
                last = text; out(TranscriptParser.Update(OnDeviceText.tidy(text, final = false, english = true), false))
            }
        }

        override fun finish(out: (TranscriptParser.Update) -> Unit) {
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * 6 / 10), SAMPLE_RATE) // the model looks a little ahead
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            out(TranscriptParser.Update(OnDeviceText.tidy(rec.getResult(stream).text, final = true, english = true), true))
            stream.release(); stream = rec.createStream(); last = ""
        }

        override fun release() { runCatching { stream.release() }; rec.release() }
    }

    private class SentenceEngine(private val ch: Choice, override val key: String) : Engine {
        private fun f(name: String) = File(ch.dir, name).absolutePath
        private val vad = Vad(null, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = f("silero_vad.onnx"), threshold = 0.5f,
                minSilenceDuration = ch.pauseMs / 1000f, minSpeechDuration = 0.25f, windowSize = 512,
                maxSpeechDuration = ch.maxSentenceS.toFloat()),
            sampleRate = SAMPLE_RATE, numThreads = 1,
        ))
        private val rec = OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(SAMPLE_RATE, 80),
            modelConfig = when (ch.pack.kind) {
                SpeechPackCatalog.Kind.OMNILINGUAL_CTC -> OfflineModelConfig(omnilingual = OfflineOmnilingualAsrCtcModelConfig(f("model.onnx")),
                    tokens = f("tokens.txt"), numThreads = ch.threads)
                else -> OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(f("model.onnx")),
                    tokens = f("tokens.txt"), numThreads = ch.threads)
            },
        ))
        private var speaking = false

        override fun accept(samples: FloatArray, out: (TranscriptParser.Update) -> Unit) {
            vad.acceptWaveform(samples)
            drain(out)
            val now = vad.isSpeechDetected()
            if (now && !speaking) out(TranscriptParser.Update("…", false))
            speaking = now
        }

        private fun drain(out: (TranscriptParser.Update) -> Unit) {
            while (!vad.empty()) {
                val seg = vad.front(); vad.pop()
                val s = rec.createStream()
                try {
                    s.acceptWaveform(seg.samples, SAMPLE_RATE); rec.decode(s)
                    out(TranscriptParser.Update(OnDeviceText.tidy(rec.getResult(s).text, final = true), true))
                } finally { s.release() }
                speaking = false
            }
        }

        override fun finish(out: (TranscriptParser.Update) -> Unit) {
            vad.flush(); drain(out); vad.reset()
            if (speaking) out(TranscriptParser.Update("", true)) // clear a "…" that never became words
            speaking = false
        }

        override fun release() { vad.release(); rec.release() }
    }

    /**
     * Runs one session's on-device speech on its own thread: [push] mic audio (from the mic thread), [finish] at the
     * end. [out] and [failed] are called on the runner thread. Audio pushed while the pack is still loading waits.
     */
    class Runner(private val c: Context, private val ch: Choice,
                 private val out: (TranscriptParser.Update) -> Unit, private val failed: (String) -> Unit) {
        private val queue = LinkedBlockingQueue<FloatArray>()
        @Volatile var dead = false; private set
        private val thread = Thread(::loop, "dw-on-device-speech")
        private val end = FloatArray(0)

        fun start(): Runner { thread.start(); return this }
        fun push(pcm16: ByteArray, n: Int) {
            val s = FloatArray(n / 2)
            for (i in s.indices) s[i] = ((pcm16[2 * i].toInt() and 0xff) or (pcm16[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            queue.put(s)
        }
        /** Waits (up to [waitMs]) for the last sentence. */
        fun finish(waitMs: Long) { queue.put(end); thread.join(waitMs) }
        /** Stop now, dropping what's left. */
        fun abort() { dead = true; queue.clear(); queue.put(end) }

        private fun loop() {
            try {
                guard(c).run {
                    val e = engine(ch)
                    while (true) {
                        val s = queue.poll(1, TimeUnit.SECONDS) ?: if (dead) break else continue
                        if (s === end) { if (!dead) e.finish(out); break }
                        if (!dead) e.accept(s, out)
                    }
                }
            } catch (e: Throwable) { // a bad or missing file: say so, never crash the app
                Log.w("DailyWhisper", "On-device speech failed: ${e.javaClass.simpleName}")
                release(ch.pack.id)
                dead = true
                failed("${SpeechProvider.ON_DEVICE.label} couldn't start the ${ch.pack.name} pack. Try downloading it again")
            }
        }
    }
}
