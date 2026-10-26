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
 * Streams mono PCM16 mic audio (little-endian, at the configured sample rate) as binary WebSocket
 * frames to the URL in Settings, and turns the JSON replies into partial / final text via [TranscriptParser].
 * Only the audio, the optional start/end messages and the key (as configured) go to that URL. Nothing is logged.
 */
class StreamingLiveDictation(private val context: Context, private val ui: Callbacks) : LiveDictation {

    enum class State { CONNECTING, LISTENING, STOPPING, IDLE }

    /** All called on the main thread. */
    interface Callbacks {
        fun onState(state: State)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, openSettings: Boolean = false)
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null // main thread only

    override val isListening: Boolean get() = session != null

    override fun start() {
        if (session != null) return
        val cfg = try { Config.load(context) } catch (e: IllegalArgumentException) {
            ui.onError(e.message ?: "Check the live dictation settings", openSettings = true); return
        }
        session = Session(cfg).also { it.begin() }
    }

    override fun stop() { session?.requestStop() }

    override fun cancel() {
        val s = session ?: return
        session = null
        s.abortNow()
        ui.onState(State.IDLE)
    }

    private class Config(
        val uri: URI, val headers: Map<String, String>, val sampleRate: Int, val chunkMs: Int,
        val startMsg: String, val endMsg: String, val parser: TranscriptParser,
    ) {
        companion object {
            fun load(c: Context): Config {
                val p = c.settings()
                val rate = p[Prefs.STT_SAMPLE_RATE]
                val (url, headers) = LiveDictationConfig.buildRequest(
                    p[Prefs.STT_URL], rate, p[Prefs.STT_AUTH], p[Prefs.STT_AUTH_NAME], SecretStore.get(c, Prefs.STT_API_KEY.key))
                val uri = runCatching { URI(url) }.getOrNull()
                require(uri != null && uri.host != null && uri.scheme?.lowercase() in setOf("wss", "ws")) {
                    "The streaming URL should look like wss://… (Settings → Live dictation)"
                }
                return Config(
                    uri, headers, rate, p[Prefs.STT_CHUNK_MS],
                    p[Prefs.STT_START_MSG].replace("{sample_rate}", rate.toString()),
                    p[Prefs.STT_END_MSG].replace("{sample_rate}", rate.toString()),
                    TranscriptParser(p[Prefs.STT_TEXT_FIELD], p[Prefs.STT_FINAL_FIELD], p[Prefs.STT_FINAL_VALUE]),
                )
            }
        }
    }

    private inner class Session(private val cfg: Config) : WebSocketClient.Listener {
        @Volatile private var stopRequested = false
        @Volatile private var aborted = false
        @Volatile private var serverClosed = false
        @Volatile private var closeCode = 0
        @Volatile private var client: WebSocketClient? = null
        private val worker = Thread(::run, "dw-live-dictation")

        fun begin() { ui.onState(State.CONNECTING); worker.start() }
        fun requestStop() { if (!stopRequested) { stopRequested = true; ui.onState(State.STOPPING) } }
        fun abortNow() { aborted = true; stopRequested = true; client?.abort() }

        /** Runs [block] on the main thread only if this session is still the current one. */
        private fun post(block: () -> Unit) = main.post { if (session === this && !aborted) block() }

        @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO before start()
        private fun run() {
            var record: AudioRecord? = null
            var error: String? = null
            try {
                val ws = WebSocketClient(cfg.uri, cfg.headers, HttpsURLConnection.getDefaultHostnameVerifier())
                client = ws
                ws.connect()
                if (aborted) return
                val reader = Thread({ ws.readLoop(this) }, "dw-live-dictation-read").apply { start() }
                if (cfg.startMsg.isNotBlank()) ws.sendText(cfg.startMsg)

                val chunkBytes = (cfg.sampleRate * cfg.chunkMs / 1000) * 2
                val minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) throw IllegalStateException("This phone can't record at ${cfg.sampleRate} Hz. Try 16000 in Settings")
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, cfg.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunkBytes * 4))
                record = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("The microphone is busy")
                rec.startRecording()
                if (!stopRequested) post { ui.onState(State.LISTENING) }

                val buf = ByteArray(chunkBytes)
                while (!stopRequested && !serverClosed) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("The microphone stopped working")
                    if (n > 0) ws.sendBinary(buf, 0, n)
                }
                rec.stop()

                if (!serverClosed && !aborted) {
                    post { ui.onState(State.STOPPING) }
                    if (cfg.endMsg.isNotBlank()) {
                        runCatching { ws.sendText(cfg.endMsg) }
                        reader.join(END_WAIT_MS) // let the last finals arrive; many servers close by themselves
                    }
                    ws.close(1000, "done")
                    reader.join(CLOSE_WAIT_MS)
                } else if (serverClosed && !stopRequested && closeCode != 1000) {
                    error = "Live dictation stopped: the server closed the connection" + (if (closeCode > 0) " ($closeCode)" else "")
                }
                reader.join(CLOSE_WAIT_MS)
            } catch (e: WebSocketClient.HandshakeException) {
                error = when (e.status) {
                    401, 403 -> "Live dictation: the server refused the key (HTTP ${e.status}). Check the API key settings"
                    0 -> "Live dictation: couldn't start the stream"
                    else -> "Live dictation: the server answered HTTP ${e.status}. Check the URL settings"
                }
            } catch (e: IOException) {
                if (!aborted) error = "Live dictation: couldn't connect. Check your connection and the URL"
            } catch (e: IllegalStateException) {
                error = e.message
            } catch (e: SecurityException) {
                error = "Daily Whisper needs microphone access for live dictation"
            } finally {
                runCatching { record?.release() }
                client?.abort()
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

        // ---- WebSocket listener (reader thread) ----
        override fun onText(text: String) {
            val u = cfg.parser.parse(text) ?: return
            post { if (u.isFinal) ui.onFinal(u.text) else ui.onPartial(u.text) }
        }

        override fun onClosed(code: Int, reason: String) { closeCode = code; serverClosed = true }
    }

    private companion object {
        const val END_WAIT_MS = 3000L
        const val CLOSE_WAIT_MS = 1500L
    }
}
