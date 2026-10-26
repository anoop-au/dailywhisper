package com.anoop.dailywhisper

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Live, in-app speech-to-text from the composer mic (separate from Vion, which stays the polished
 * multi-app dictation). [isListening] is true from [start] until the session has fully ended.
 */
interface LiveDictation {
    val isListening: Boolean
    /** Connect, then stream the mic. The caller checks the URL setting and RECORD_AUDIO first. */
    fun start()
    /** Graceful stop: stop the mic, send the end message, let last results arrive, close. */
    fun stop()
    /** Immediate stop: drop the connection, no more callbacks. */
    fun cancel()
}

/**
 * Streams mono PCM16 mic audio (16 kHz) to the first speech slot that opens (SpeechSlots: Deepgram, then Sarvam AI) and
 * turns the JSON replies into partial / final text via [TranscriptParser]. If a slot can't connect or refuses the key, the
 * next one is tried before the mic starts. Only the audio, the end message and the key go to that provider. Nothing is logged.
 */
class StreamingLiveDictation(private val context: Context, private val ui: Callbacks) : LiveDictation {

    enum class State { CONNECTING, LISTENING, STOPPING, IDLE }

    /** All called on the main thread. */
    interface Callbacks {
        fun onState(state: State)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, openSettings: Boolean = false)
        /** A short heads-up, e.g. that a later slot is being used. */
        fun onNotice(message: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null // main thread only

    override val isListening: Boolean get() = session != null

    override fun start() {
        if (session != null) return
        val protocols = try { protocols(context) } catch (e: IllegalArgumentException) {
            ui.onError(e.message ?: "Check the live dictation settings", openSettings = true); return
        }
        session = Session(protocols).also { it.begin() }
    }

    override fun stop() { session?.requestStop() }

    override fun cancel() {
        val s = session ?: return
        session = null
        s.abortNow()
        ui.onState(State.IDLE)
    }

    private inner class Session(private val protocols: List<SttProtocol>) : SttLink.Sink {
        @Volatile private var stopRequested = false
        @Volatile private var aborted = false
        @Volatile private var link: SttLink? = null
        private val worker = Thread(::run, "dw-live-dictation")

        fun begin() { ui.onState(State.CONNECTING); worker.start() }
        fun requestStop() { if (!stopRequested) { stopRequested = true; ui.onState(State.STOPPING) } }
        fun abortNow() { aborted = true; stopRequested = true; link?.abort() }

        /** Runs [block] on the main thread only if this session is still the current one. */
        private fun post(block: () -> Unit) = main.post { if (session === this && !aborted) block() }

        @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO before start()
        private fun run() {
            var record: AudioRecord? = null
            var error: String? = null
            try {
                val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                val opened = SttConnector.open(protocols, { p -> WebSocketClient(URI(p.url), p.headers, verifier) }, this,
                    READY_WAIT_MS, stop = { aborted })
                val l = opened.link
                if (l == null) { if (!aborted) error = SttConnector.describe(opened.failures); return }
                link = l
                if (aborted) return
                if (opened.failures.isNotEmpty()) {
                    val why = opened.failures.first()
                    post { ui.onNotice("Using ${l.protocol.provider.label}: ${why.describe().substringBefore(". ")}") }
                }

                val rate = LiveDictationConfig.SAMPLE_RATE
                val chunkBytes = (rate * LiveDictationConfig.CHUNK_MS / 1000) * 2
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) throw IllegalStateException("This phone can't record at $rate Hz")
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunkBytes * 4))
                record = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("The microphone is busy")
                rec.startRecording()
                if (!stopRequested) post { ui.onState(State.LISTENING) }

                val buf = ByteArray(chunkBytes)
                while (!stopRequested && !l.closed) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("The microphone stopped working")
                    if (n > 0) l.sendAudio(buf, n)
                }
                rec.stop()

                if (!l.closed && !aborted) {
                    post { ui.onState(State.STOPPING) }
                    l.finish(END_WAIT_MS, CLOSE_WAIT_MS)
                } else if (l.closed && !stopRequested && l.closeCode != 1000) {
                    error = "Live dictation stopped: ${l.protocol.provider.label} closed the connection" +
                        (if (l.closeCode > 0) " (${l.closeCode})" else "")
                }
            } catch (e: IOException) {
                if (!aborted) error = "Live dictation: lost the connection to ${link?.protocol?.provider?.label ?: "the speech provider"}"
            } catch (e: IllegalStateException) {
                error = e.message
            } catch (e: SecurityException) {
                error = "Daily Whisper needs microphone access for live dictation"
            } finally {
                runCatching { record?.release() }
                link?.abort()
                val msg = error
                main.post {
                    if (session === this) {
                        session = null
                        if (msg != null && !aborted) ui.onError(msg)
                        ui.onState(State.IDLE)
                    }
                }
            }
        }

        // ---- SttLink.Sink (reader thread) ----
        /** Last detected language announced (Sarvam auto-detect only); reader thread only. */
        private var heard: String? = null

        override fun onUpdate(update: TranscriptParser.Update) {
            // With Sarvam's auto-detect, finals name the language: say it once, and again only when it changes.
            val said = if (update.isFinal) SarvamConfig.detectedName(update.language)?.takeIf { it != heard } else null
            if (said != null) heard = said
            post {
                if (update.isFinal) ui.onFinal(update.text) else ui.onPartial(update.text)
                if (said != null) ui.onNotice("Sarvam AI hears $said")
            }
        }

        override fun onClosed(code: Int) {} // the mic loop sees link.closed
    }

    companion object {
        private const val END_WAIT_MS = 3000L
        private const val CLOSE_WAIT_MS = 1500L
        private const val READY_WAIT_MS = 6000L

        /**
         * The protocols to try, in slot order, for the current settings. Throws IllegalArgumentException with the words to
         * show when no slot can dictate (no key, slot off, language not supported, audio not allowed to leave the region).
         */
        fun protocols(c: Context): List<SttProtocol> {
            val p = c.settings()
            val language = p[Prefs.DG_LANGUAGE]
            val pause = p[Prefs.PAUSE_MS]
            val slots = c.speechSlots()
            val use = SpeechSlots.candidates(slots, language)
            require(use.isNotEmpty()) { SpeechSlots.whyNone(slots, language) }
            return use.map { slot ->
                when (slot.provider) {
                    SpeechProvider.DEEPGRAM -> SttProtocol.deepgram(SecretStore.get(c, Prefs.STT_API_KEY.key), p[Prefs.DG_REGION],
                        p[Prefs.DG_MODEL], language, p[Prefs.DG_SMART_FORMAT], pause)
                    SpeechProvider.SARVAM -> SttProtocol.sarvam(SecretStore.get(c, Prefs.SLOT2_KEY.key), p[Prefs.SARVAM_MODEL],
                        p[Prefs.SARVAM_MODE], language, pause)
                    SpeechProvider.NONE -> error("empty slot")
                }
            }.filter { SttConnector.uri(it) != null }.also {
                require(it.isNotEmpty()) { "Check the Model and Language in Settings → Speech slot 1" }
            }
        }
    }
}
