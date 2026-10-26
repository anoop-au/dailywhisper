package com.anoop.dailywhisper

import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * One speech provider's wire protocol: where to connect, how to send audio and how to read the replies.
 * Built by [deepgram] / [sarvam] from the settings. Pure Kotlin (no Android) so tools/ws-harness tests it.
 * Never put the key anywhere but [headers]; nothing here logs.
 */
class SttProtocol(
    val provider: SpeechProvider,
    val url: String,
    val headers: Map<String, String>,
    val endMessage: String,
    /** Audio as JSON text frames (Sarvam) instead of raw binary frames (Deepgram). */
    private val jsonAudio: Boolean,
    /** Wait for the server's "ready" message before streaming (Sarvam sends session.begin; bad keys get an error). */
    val waitForReady: Boolean,
    val parser: TranscriptParser,
) {
    fun sendAudio(ws: WebSocketClient, buf: ByteArray, n: Int) {
        if (jsonAudio) ws.sendText(SarvamConfig.audioMessage(buf, 0, n)) else ws.sendBinary(buf, 0, n)
    }

    fun isReady(message: String): Boolean = when (provider) {
        SpeechProvider.SARVAM -> event(message) == SarvamConfig.READY_EVENT
        else -> true
    }

    /** A fatal error message from the server, as (HTTP-like status or 0, short text); null for anything else. */
    fun fatalError(message: String): Pair<Int, String>? {
        if (provider != SpeechProvider.SARVAM) return null
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (o.optString("event") != "error" || !o.optBoolean("is_fatal", false)) return null
        return o.optInt("status_code", 0) to o.optString("code", "error")
    }

    private fun event(message: String): String? = runCatching { JSONObject(message).optString("event") }.getOrNull()

    companion object {
        fun deepgram(key: String, region: String, model: String, language: String, smartFormat: Boolean, pauseMs: Int) = SttProtocol(
            SpeechProvider.DEEPGRAM, LiveDictationConfig.url(region, model, language, smartFormat, pauseMs),
            LiveDictationConfig.headers(key), LiveDictationConfig.END_MESSAGE, jsonAudio = false, waitForReady = false,
            TranscriptParser(LiveDictationConfig.TEXT_PATH, LiveDictationConfig.FINAL_FIELD, LiveDictationConfig.FINAL_VALUE),
        )

        fun sarvam(key: String, model: String, mode: String, language: String, pauseMs: Int, base: String = "wss://${SarvamConfig.HOST}") = SttProtocol(
            SpeechProvider.SARVAM, SarvamConfig.url(model, mode, language, pauseMs, base), SarvamConfig.headers(key),
            SarvamConfig.END_MESSAGE, jsonAudio = true, waitForReady = true,
            TranscriptParser(SarvamConfig.TEXT_PATH, SarvamConfig.FINAL_FIELD, SarvamConfig.FINAL_VALUE, SarvamConfig.LANGUAGE_PATH),
        )
    }
}

/** Why a provider couldn't be used. [status]: HTTP-like code when known (401/403 = the key), 0 otherwise. */
class SttFailure(val provider: SpeechProvider, val status: Int, val network: Boolean) : IOException("${provider.label} failed ($status)") {
    /** Plain words for the user. Never includes the key or the URL. */
    fun describe(): String = when {
        status == 401 || status == 403 -> "${provider.label} refused the key (HTTP $status). Check the API key in Settings"
        status == 1003 -> "${provider.label} refused the key, or the account is out of quota. Check the API key in Settings"
        status == 429 -> "${provider.label} says the account is out of quota or rate-limited"
        network -> "couldn't reach ${provider.label}. Check your connection"
        status > 0 -> "${provider.label} answered HTTP $status. Check its Model and Language settings"
        else -> "${provider.label} closed the connection before it was ready"
    }
}

/**
 * One open connection to one provider. Server messages arrive on its own reader thread and go to [sink].
 * [open] connects and (if the protocol says so) waits for the ready message; it throws [SttFailure] otherwise.
 */
class SttLink(val protocol: SttProtocol, private val client: WebSocketClient, private val sink: Sink) : WebSocketClient.Listener {
    interface Sink {
        fun onUpdate(update: TranscriptParser.Update)
        fun onClosed(code: Int)
    }

    private val ready = CountDownLatch(1)
    @Volatile private var opened = false
    @Volatile private var fatal: Pair<Int, String>? = null
    @Volatile var closed = false; private set
    @Volatile var closeCode = 0; private set
    private var reader: Thread? = null

    fun open(readyWaitMs: Long) {
        try {
            client.connect()
        } catch (e: WebSocketClient.HandshakeException) {
            throw SttFailure(protocol.provider, e.status, network = false)
        } catch (e: IOException) {
            throw SttFailure(protocol.provider, 0, network = true)
        }
        reader = Thread({ client.readLoop(this) }, "dw-live-dictation-read").apply { isDaemon = true; start() }
        if (protocol.waitForReady) {
            val got = ready.await(readyWaitMs, TimeUnit.MILLISECONDS)
            fatal?.let { client.abort(); throw SttFailure(protocol.provider, it.first, network = false) }
            if (!got || closed) {
                client.abort()
                // Sarvam closes with 1003 for a bad key or exhausted quota, 4000 for an unsupported setting.
                throw SttFailure(protocol.provider, when (closeCode) { 1003 -> 1003; 4000 -> 400; else -> 0 }, network = !got && !closed)
            }
        }
        opened = true
    }

    fun sendAudio(buf: ByteArray, n: Int) = protocol.sendAudio(client, buf, n)

    /** Graceful end: send the end message, let the last finals arrive, close. */
    fun finish(endWaitMs: Long, closeWaitMs: Long) {
        if (closed) return
        if (protocol.endMessage.isNotBlank()) {
            runCatching { client.sendText(protocol.endMessage) }
            reader?.join(endWaitMs) // many servers close by themselves after the end message
        }
        runCatching { client.close(1000, "done") }
        reader?.join(closeWaitMs)
    }

    fun abort() = client.abort()

    override fun onText(text: String) {
        protocol.fatalError(text)?.let { fatal = it; ready.countDown(); return }
        if (protocol.isReady(text)) ready.countDown()
        if (!opened && protocol.waitForReady && ready.count > 0) return
        protocol.parser.parse(text)?.let(sink::onUpdate)
    }

    override fun onClosed(code: Int, reason: String) {
        closeCode = code; closed = true; ready.countDown()
        if (opened) sink.onClosed(code)
    }
}

/** Tries the slots in order and returns the first link that opens. */
object SttConnector {
    class Result(val link: SttLink?, val failures: List<SttFailure>)

    fun open(
        protocols: List<SttProtocol>,
        clientFor: (SttProtocol) -> WebSocketClient,
        sink: SttLink.Sink,
        readyWaitMs: Long = 6000,
        stop: () -> Boolean = { false },
    ): Result {
        val failures = ArrayList<SttFailure>()
        for (p in protocols) {
            if (stop()) break
            val link = SttLink(p, clientFor(p), sink)
            try { link.open(readyWaitMs); return Result(link, failures) } catch (e: SttFailure) { failures += e }
        }
        return Result(null, failures)
    }

    /** The message when no slot opened. */
    fun describe(failures: List<SttFailure>): String = when (failures.size) {
        0 -> "Live dictation: couldn't start the stream"
        1 -> "Live dictation: " + failures[0].describe()
        else -> "Live dictation couldn't start. " + failures.joinToString(" ") { it.describe().replaceFirstChar(Char::uppercase) + "." }
    }

    fun uri(p: SttProtocol): URI? = runCatching { URI(p.url) }.getOrNull()?.takeIf { it.host != null && it.scheme?.lowercase() in setOf("wss", "ws") }
}
