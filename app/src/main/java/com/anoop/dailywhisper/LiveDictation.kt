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
 * Streams mono PCM16 mic audio (16 kHz) to the first speech slot that opens (SpeechSlots: the user's provider order, then on this phone) and
 * turns the JSON replies into partial / final text via [TranscriptParser]. If a slot can't connect or refuses the key, the
 * next one is tried before the mic starts. Only the audio, the end message and the key go to that provider. Nothing is logged.
 * Slot 3, "On this phone" (OnDeviceSpeech), comes last: it is used when the online slots can't be, when there is no
 * internet when the mic is tapped, and (Settings → Providers → On this phone → Switch when the internet drops) when the connection
 * goes mid-sentence. Then the audio stays on the phone.
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
        val online = context.isOnline()
        val setup = try { setup(context, online) } catch (e: IllegalArgumentException) {
            ui.onError(e.message ?: "Check the live dictation settings", openSettings = true); return
        }
        session = Session(setup).also { it.begin() }
        if (setup.plan.offline) ui.onNotice(SpeechSlots.OFFLINE_NOTE)
        // Auto (Indian languages) with Sarvam AI unusable runs on a "multi" provider: say so once per app run, quietly.
        val multi = setup.plan.online.firstOrNull()?.provider
        if (!fallbackNoted && setup.plan.fellBack && multi != null) {
            fallbackNoted = true
            ui.onNotice(SpeechSlots.autoFallbackNote(multi))
        }
    }

    override fun stop() { session?.requestStop() }

    override fun cancel() {
        val s = session ?: return
        session = null
        s.abortNow()
        ui.onState(State.IDLE)
    }

    /** What one mic tap will use: the online [protocols] in order, then on this phone ([device]) if it can. */
    class Setup(val plan: SpeechSlots.Plan, val protocols: List<SttProtocol>, val device: OnDeviceSpeech.Choice?, val switchOnDrop: Boolean)

    private inner class Session(private val setup: Setup) : SttLink.Sink {
        @Volatile private var stopRequested = false
        @Volatile private var aborted = false
        @Volatile private var link: SttLink? = null
        @Volatile private var device: OnDeviceSpeech.Runner? = null
        @Volatile private var deviceError: String? = null
        /** The online link was given up for on this phone; anything it still sends is ignored. */
        @Volatile private var switched = false
        /** The last words the online provider showed but hadn't finished (kept when switching). Reader thread. */
        @Volatile private var lastPartial = ""
        private val worker = Thread(::run, "dw-live-dictation")

        fun begin() { ui.onState(State.CONNECTING); worker.start() }
        fun requestStop() { if (!stopRequested) { stopRequested = true; ui.onState(State.STOPPING) } }
        fun abortNow() { aborted = true; stopRequested = true; link?.abort(); device?.abort() }

        /** Runs [block] on the main thread only if this session is still the current one. */
        private fun post(block: () -> Unit) = main.post { if (session === this && !aborted) block() }

        private fun startDevice(ch: OnDeviceSpeech.Choice): OnDeviceSpeech.Runner =
            OnDeviceSpeech.Runner(context, ch, { u -> post { if (u.isFinal) ui.onFinal(u.text) else ui.onPartial(u.text) } },
                { why -> deviceError = why }).start().also { device = it }

        /** The connection went mid-way: keep what was heard, carry on with on this phone. False if it can't. */
        private fun switchToDevice(): Boolean {
            val ch = setup.device ?: return false
            if (!setup.switchOnDrop || stopRequested || device != null) return false
            switched = true
            link?.abort(); link = null
            val kept = lastPartial
            post { if (kept.isNotBlank()) ui.onFinal(kept); ui.onNotice(SpeechSlots.DROPPED_NOTE) }
            startDevice(ch)
            return true
        }

        @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO before start()
        private fun run() {
            var record: AudioRecord? = null
            var error: String? = null
            try {
                var failures: List<SttFailure> = emptyList()
                if (setup.protocols.isNotEmpty()) {
                    val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    val opened = SttConnector.open(setup.protocols, { p -> WebSocketClient(URI(p.url), p.headers, verifier) }, this,
                        READY_WAIT_MS, stop = { aborted })
                    link = opened.link
                    failures = opened.failures
                }
                if (aborted) return
                val l0 = link
                val ch = setup.device
                // Say which engine took the mic when it isn't the one the last session used (or on the first session).
                val engine = l0?.protocol?.provider?.label ?: ch?.let { "${SpeechProvider.ON_DEVICE.label} · ${it.pack.name}" }
                val changed = engine != null && engine != lastEngine
                lastEngine = engine
                if (l0 == null) {
                    if (ch == null) { error = SttConnector.describe(failures); return }
                    startDevice(ch)
                    if (failures.isEmpty() && !setup.plan.offline && changed) post { ui.onNotice(SpeechSlots.engineNote(SpeechProvider.ON_DEVICE, ch.pack.name)) }
                    if (failures.isNotEmpty()) {
                        val why = failures.first().describe().substringBefore(". ")
                        post { ui.onNotice(if (failures.any { it.network }) SpeechSlots.NO_CONNECT_NOTE else "Using ${SpeechProvider.ON_DEVICE.label}: $why") }
                    }
                } else if (failures.isNotEmpty()) {
                    val why = failures.first()
                    post { ui.onNotice("Using ${l0.protocol.provider.label}: ${why.describe().substringBefore(". ")}") }
                } else if (changed) {
                    post { ui.onNotice(SpeechSlots.engineNote(l0.protocol.provider)) }
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
                while (!stopRequested) {
                    val l = link
                    if (l != null && l.closed && !(l.closeCode != 1000 && switchToDevice())) break
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("The microphone stopped working")
                    if (n == 0) continue
                    val cur = link
                    if (cur != null) {
                        try { cur.sendAudio(buf, n) } catch (e: IOException) { if (!switchToDevice()) throw e; device?.push(buf, n) }
                    } else {
                        val d = device ?: break
                        if (d.dead) { error = deviceError ?: "${SpeechProvider.ON_DEVICE.label} stopped"; break }
                        d.push(buf, n)
                    }
                }
                rec.stop()

                val l = link
                if (l != null && !l.closed && !aborted) {
                    post { ui.onState(State.STOPPING) }
                    l.finish(END_WAIT_MS, CLOSE_WAIT_MS)
                } else if (l != null && l.closed && !stopRequested && l.closeCode != 1000) {
                    error = "Live dictation stopped: ${l.protocol.provider.label} closed the connection" +
                        (if (l.closeCode > 0) " (${l.closeCode})" else "")
                }
                device?.let { d ->
                    if (!aborted) { post { ui.onState(State.STOPPING) }; d.finish(DEVICE_END_WAIT_MS) }
                    if (error == null && d.dead) error = deviceError
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
                device?.takeIf { aborted }?.abort()
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
            if (switched) return
            lastPartial = if (update.isFinal) "" else update.text
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
        /** A sentence-at-a-time pack may still be writing the last sentence when the mic stops. */
        private const val DEVICE_END_WAIT_MS = 15000L
        /** The engine the last session used ("Sarvam AI", "On this phone · Malayalam"), to name it when it changes. */
        @Volatile private var lastEngine: String? = null
        /** The Auto fallback note was shown this app run. */
        @Volatile private var fallbackNoted = false

        /**
         * The online protocols to try, in slot order, for the current settings (with internet). Throws
         * IllegalArgumentException with the words to show when no slot can dictate (no key, slot off, language not
         * supported, audio not allowed to leave the region, no offline pack).
         */
        fun protocols(c: Context): List<SttProtocol> = setup(c, online = true).protocols

        /** Everything one mic tap needs. [online]: the phone has internet now (else only on this phone is planned). */
        fun setup(c: Context, online: Boolean): Setup {
            val p = c.settings()
            val chosen = p[Prefs.DG_LANGUAGE]
            val pause = p[Prefs.PAUSE_MS]
            val slots = c.speechSlots()
            val plan = SpeechSlots.plan(slots, chosen, online)
            require(plan.slots.isNotEmpty()) { SpeechSlots.whyNone(slots, chosen, online) }
            val language = plan.language // "multi" when Auto (Indian languages) fell back
            val device = plan.onDevice?.let { OnDeviceSpeech.choice(c, chosen) }
            // Every online provider through the same interface (SpeechProvider): its key, its own settings.
            val options = SttOptions(p.all)
            val protocols = plan.online.mapNotNull { slot ->
                val key = slot.provider.keyName?.let { SecretStore.get(c, it) }.orEmpty()
                slot.provider.protocol(key, language, pause, options)
            }.filter { SttConnector.uri(it) != null }.also {
                require(it.isNotEmpty() || device != null) {
                    "Check the Model in Settings → Providers → ${plan.online.first().provider.label} and the language in Settings → I dictate in"
                }
            }
            return Setup(plan, protocols, device, p[Prefs.OFFLINE_SWITCH])
        }
    }
}
