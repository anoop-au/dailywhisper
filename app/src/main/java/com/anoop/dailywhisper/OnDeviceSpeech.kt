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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
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
 *   With the "Sharper English" add-on (two-pass), the voice-activity detector marks the end of each sentence and
 *   Parakeet writes that sentence again, replacing the live words. With "English punctuation" (and no Sharper
 *   English), each finished sentence gets punctuation and capitals.
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
    /** Quiet speech boost (AutoGain): up to 10× (20 dB). */
    const val DEFAULT_BOOST = 10
    /** English pack search width: 4 = modified beam search with 4 paths; 1 = greedy. */
    const val DEFAULT_BEAM = 4
    /** Silence fed after the last word when the mic stops, so the streaming model writes it (was 0.6 s). */
    const val TAIL_S = 1.0f
    /**
     * Sharper English's sentence pause (the voice-activity detector's), separate from the live sentence pause (1.3 s):
     * people who speak slowly pause mid-sentence, and each piece written again on its own loses its context. Box test,
     * slowly spoken sentences with 1.5 s pauses: 25% word errors at 1.3 s, 16% at 2.0 s; ordinary speech 9.1% → 8.9%.
     */
    const val DEFAULT_RECHECK_PAUSE_MS = 2000
    const val SAMPLE_RATE = 16000

    /** The crash guard for on-device speech (its own marker files; the processor check is OpusGuard's, see there). */
    fun guard(c: Context): OpusGuard {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0L)
        return OpusGuard(File(c.noBackupFilesDir, "speech-guard"), version, OpusPacks.cpuFeatures)
    }

    /** What a session needs to start on-device speech, worked out on the main thread. */
    class Choice(val pack: SpeechPackCatalog.Pack, val dir: File, val threads: Int, val pauseMs: Int, val maxSentenceS: Int,
                 val beam: Int = DEFAULT_BEAM, val boost: Int = DEFAULT_BOOST,
                 /** "Sharper English" files, when it is on the phone and "Recheck each sentence" is on. */
                 val recheck: File? = null,
                 /** "English punctuation" files, when it is on the phone, switched on, and Sharper English isn't used. */
                 val punct: File? = null,
                 /** Sharper English: how long a pause ends the sentence it writes again (longer = whole sentences). */
                 val recheckPauseMs: Int = DEFAULT_RECHECK_PAUSE_MS)

    fun choice(c: Context, language: String): Choice? {
        val installed = SpeechPacks.installed(c)
        val pack = SpeechPackCatalog.choose(language, installed) ?: return null
        val p = c.settings()
        val addons = SpeechPackCatalog.addons(pack, installed).map { it.id }
        // The most accurate Sharper English on the phone (both installed: the big one).
        val recheck = SpeechPackCatalog.RECHECKS.firstOrNull { it in addons }?.takeIf { p[Prefs.OD_RECHECK] }?.let { SpeechPacks.dir(c, it) }
        val punct = SpeechPackCatalog.PUNCT.takeIf { it in addons && p[Prefs.OD_PUNCT] && recheck == null }?.let { SpeechPacks.dir(c, it) }
        return Choice(pack, SpeechPacks.dir(c, pack.id), p[Prefs.OD_THREADS], p[Prefs.PAUSE_MS], p[Prefs.OD_MAX_SENTENCE],
            p[Prefs.OD_BEAM], p[Prefs.OD_BOOST], recheck, punct, p[Prefs.OD_RECHECK_PAUSE_MS])
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

    private fun keyOf(ch: Choice) = "${ch.pack.id}/${ch.threads}/${ch.pauseMs}/${ch.maxSentenceS}/${ch.beam}/" +
        "${ch.recheck?.name}/${ch.punct != null}/${ch.recheckPauseMs}"

    private fun engine(ch: Choice): Engine = synchronized(lock) {
        cached?.takeIf { it.key == keyOf(ch) }?.let { return it }
        cached?.release(); cached = null
        val e = if (ch.pack.mode == SpeechPackCatalog.Mode.LIVE) LiveEngine(ch, keyOf(ch)) else SentenceEngine(ch, keyOf(ch))
        cached = e
        e
    }

    /** Frees a pack's memory (pack deleted, or Android is short of memory). */
    fun release(packId: String? = null) = synchronized(lock) {
        val addon = packId?.let { SpeechPackCatalog.pack(it)?.addonFor }
        if (packId == null || cached?.key?.startsWith("${addon ?: packId}/") == true) { cached?.release(); cached = null }
    }

    private class LiveEngine(private val ch: Choice, override val key: String) : Engine {
        private fun f(name: String) = File(ch.dir, name).absolutePath
        private val twoPass = ch.recheck != null
        private val rec = OnlineRecognizer(null, OnlineRecognizerConfig(
            featConfig = FeatureConfig(SAMPLE_RATE, 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(f("encoder.onnx"), f("decoder.onnx"), f("joiner.onnx")),
                tokens = f("tokens.txt"), numThreads = ch.threads),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),                          // long silence with nothing said
                rule2 = EndpointRule(true, ch.pauseMs / 1000f, 0f),              // the sentence pause setting
                rule3 = EndpointRule(false, 0f, ch.maxSentenceS.toFloat())),     // longest sentence
            enableEndpoint = !twoPass, // two-pass: the voice-activity detector decides where a sentence ends
            decodingMethod = if (ch.beam > 1) "modified_beam_search" else "greedy_search",
            maxActivePaths = ch.beam.coerceAtLeast(1),
        ))
        private var stream: OnlineStream = rec.createStream()
        private var last = ""

        // Second pass ("Sharper English"): sentence ends from Silero VAD, the sentence again with Parakeet TDT.
        private fun g(name: String) = File(ch.recheck, name).absolutePath
        private val vad = ch.recheck?.let {
            Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = g("silero_vad.onnx"), threshold = 0.5f,
                    minSilenceDuration = ch.recheckPauseMs / 1000f, minSpeechDuration = 0.25f, windowSize = 512,
                    maxSpeechDuration = ch.maxSentenceS.toFloat()),
                sampleRate = SAMPLE_RATE, numThreads = 1,
            ))
        }
        private val second = ch.recheck?.let {
            OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(SAMPLE_RATE, 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(g("encoder.onnx"), g("decoder.onnx"), g("joiner.onnx")),
                    tokens = g("tokens.txt"), numThreads = ch.threads, modelType = "nemo_transducer"),
            ))
        }
        private val punct = ch.punct?.let {
            OnlinePunctuation(null, OnlinePunctuationConfig(OnlinePunctuationModelConfig(
                cnnBilstm = File(it, "model.onnx").absolutePath, bpeVocab = File(it, "bpe.vocab").absolutePath, numThreads = 1)))
        }

        /** A finished sentence from the live pack: punctuation when that pack is on, then tidied. */
        private fun finalText(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            val p = punct?.let { pu -> runCatching { pu.addPunctuation(t.lowercase()) }.getOrNull() } ?: t
            return OnDeviceText.tidy(p, final = true, english = true)
        }

        override fun accept(samples: FloatArray, out: (TranscriptParser.Update) -> Unit) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            if (vad != null) {
                vad.acceptWaveform(samples)
                if (drain(out)) return
            } else if (rec.isEndpoint(stream)) {
                out(TranscriptParser.Update(finalText(text), true))
                rec.reset(stream); last = ""
                return
            }
            if (text != last) {
                last = text; out(TranscriptParser.Update(OnDeviceText.tidy(text, final = false, english = true), false))
            }
        }

        /** Two-pass: each sentence the detector closed is written again and replaces the live words. True if any. */
        private fun drain(out: (TranscriptParser.Update) -> Unit): Boolean {
            val v = vad ?: return false
            val r = second ?: return false
            var any = false
            while (!v.empty()) {
                val seg = v.front(); v.pop()
                val s = r.createStream()
                val t = try { s.acceptWaveform(seg.samples, SAMPLE_RATE); r.decode(s); r.getResult(s).text } finally { s.release() }
                // Parakeet heard nothing (a cough): keep what the live pack wrote, if anything.
                val words = t.ifBlank { rec.getResult(stream).text }
                out(TranscriptParser.Update(if (t.isBlank()) finalText(words) else OnDeviceText.tidy(t, final = true, english = true), true))
                rec.reset(stream); last = ""; any = true
            }
            return any
        }

        override fun finish(out: (TranscriptParser.Update) -> Unit) {
            stream.acceptWaveform(FloatArray((SAMPLE_RATE * TAIL_S).toInt()), SAMPLE_RATE) // the model looks a little ahead
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val v = vad
            if (v != null) {
                v.flush()
                drain(out)
                v.reset()
            }
            // What the live pack still holds (no detector, or the detector never heard speech in it).
            out(TranscriptParser.Update(finalText(rec.getResult(stream).text), true))
            stream.release(); stream = rec.createStream(); last = ""
        }

        override fun release() {
            runCatching { stream.release() }; rec.release()
            vad?.release(); second?.release(); punct?.release()
        }
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
        private val gain = AutoGain(ch.boost.toFloat(), SAMPLE_RATE)
        @Volatile var dead = false; private set
        private val thread = Thread(::loop, "dw-on-device-speech")
        private val end = FloatArray(0)

        fun start(): Runner { thread.start(); return this }
        fun push(pcm16: ByteArray, n: Int) {
            val s = FloatArray(n / 2)
            for (i in s.indices) s[i] = ((pcm16[2 * i].toInt() and 0xff) or (pcm16[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            queue.put(gain.apply(s)) // 16 kHz mono float in [-1, 1], quiet speech raised (Quiet speech boost)
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
