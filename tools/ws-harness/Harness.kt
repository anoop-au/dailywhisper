// JVM harness for the app's WebSocketClient + TranscriptParser (compiled together with the real app sources).
// Run: tools/ws-harness/run.sh   (needs the fake server in server.py)
import com.anoop.dailywhisper.LiveDictationConfig
import com.anoop.dailywhisper.LiveDictationConfig as C
import com.anoop.dailywhisper.TranscriptParser
import com.anoop.dailywhisper.WebSocketClient
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

var failures = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failures++ }

fun main(args: Array<String>) {
    val port = args.getOrElse(0) { "8765" }

    // 1) Parser alone
    val p = TranscriptParser("text", "is_final", "true")
    check("parser partial", p.parse("""{"text":"hi","is_final":false}""") == TranscriptParser.Update("hi", false))
    check("parser final", p.parse("""{"text":"hi","is_final":true}""") == TranscriptParser.Update("hi", true))
    check("parser missing final field = final", p.parse("""{"text":"hi"}""")?.isFinal == true)
    check("parser no text = null", p.parse("""{"type":"ack"}""") == null)
    check("parser non-JSON = null", p.parse("not json") == null)
    val dg = TranscriptParser("channel.alternatives.0.transcript", "is_final", "true")
    check("parser dotted path", dg.parse("""{"channel":{"alternatives":[{"transcript":"deep"}]},"is_final":false}""") == TranscriptParser.Update("deep", false))
    check("parser bad index = null", dg.parse("""{"channel":{"alternatives":[]}}""") == null)
    val sm = TranscriptParser("metadata.transcript", "message", "AddTranscript")
    check("parser string final value", sm.parse("""{"message":"AddTranscript","metadata":{"transcript":"sm"}}""")?.isFinal == true)
    check("parser string partial value", sm.parse("""{"message":"AddPartialTranscript","metadata":{"transcript":"sm"}}""")?.isFinal == false)
    check("parser blank final field = all final", TranscriptParser("text", "", "true").parse("""{"text":"a","is_final":false}""")?.isFinal == true)

    // 2) Full session against the fake server (bearer header auth)
    val updates = mutableListOf<TranscriptParser.Update>()
    var binaries = 0
    var closed = -1
    val done = CountDownLatch(1)
    val ws = WebSocketClient(URI("ws://127.0.0.1:$port/stream?rate=16000"), mapOf("Authorization" to "Bearer harness-test-key"))
    ws.connect()
    check("handshake + accept check", ws.isOpen)
    val reader = Thread {
        ws.readLoop(object : WebSocketClient.Listener {
            override fun onText(text: String) { p.parse(text)?.let { synchronized(updates) { updates += it } }
                dg.parse(text)?.let { synchronized(updates) { updates += it.copy(text = "DG:" + it.text) } } }
            override fun onBinary(data: ByteArray) { binaries++ }
            override fun onClosed(code: Int, reason: String) { closed = code; done.countDown() }
        })
    }.apply { start() }
    ws.sendText("""{"type":"start","rate":16000}""")
    val chunk = ByteArray(3200) { (it % 251).toByte() } // 100 ms of 16 kHz PCM16
    repeat(5) { ws.sendBinary(chunk); Thread.sleep(50) }
    Thread.sleep(300)
    ws.sendText("""{"type":"end"}""")
    check("server closed cleanly after end message", done.await(5, TimeUnit.SECONDS) && closed == 1000)
    reader.join(2000)
    val texts = synchronized(updates) { updates.toList() }
    check("partial 'hello'", texts.contains(TranscriptParser.Update("hello", false)))
    check("fragmented partial reassembled", texts.contains(TranscriptParser.Update("hello there", false)))
    check("final 'hello there'", texts.contains(TranscriptParser.Update("hello there", true)))
    check("16-bit length frame", texts.any { it.text.startsWith("big ") && it.text.length == 304 })
    check("64-bit length frame", texts.any { it.text.startsWith("huge ") && it.text.length == 70005 })
    check("nested Deepgram-shaped message", texts.any { it.text == "DG:nested ok" && it.isFinal })
    check("binary message received", binaries == 1)
    val last = texts.lastOrNull { !it.text.startsWith("DG:") }
    println("  last final: ${last?.text}")
    check("server got all audio (5 x 3200 bytes) + start msg + pong", last?.text == "final after end; audio=16000 frames=5 start_rate=16000 pong=True")

    // 3) Client-initiated close without end message
    val ws2 = WebSocketClient(URI("ws://127.0.0.1:$port/"), mapOf("Authorization" to "Bearer harness-test-key"))
    ws2.connect()
    var code2 = -1
    val t2 = Thread { ws2.readLoop(object : WebSocketClient.Listener { override fun onClosed(code: Int, reason: String) { code2 = code } }) }.apply { start() }
    ws2.sendBinary(chunk); ws2.close(1000, "done"); t2.join(3000)
    check("client close handshake completes (1000)", code2 == 1000)

    // 4) Query-param auth
    val ws3 = WebSocketClient(URI("ws://127.0.0.1:$port/query?api_key=harness-test-key"))
    ws3.connect(); check("query-param auth accepted", ws3.isOpen); ws3.abort()

    // 5) Wrong key -> HandshakeException 401 (message must not contain the key)
    try {
        WebSocketClient(URI("ws://127.0.0.1:$port/"), mapOf("Authorization" to "Bearer wrong-secret")).connect()
        check("401 rejected", false)
    } catch (e: WebSocketClient.HandshakeException) {
        check("401 rejected with status", e.status == 401)
        check("error text does not leak key", !(e.message ?: "").contains("wrong-secret"))
    }

    // 6) Deepgram defaults end to end (the app's real defaults + request builder + parser), fake Deepgram server
    deepgramDefaults(port)
    migration()

    // 7) Optional: TLS (wss) against a public echo server, if the box has internet
    if (args.contains("--wss")) {
        try {
            val got = CountDownLatch(1); var echo = ""
            val w = WebSocketClient(URI("wss://echo.websocket.org/"))
            w.connect()
            val t = Thread { w.readLoop(object : WebSocketClient.Listener {
                override fun onText(text: String) { if (text.contains("dw-echo")) { echo = text; got.countDown() } } }) }.apply { start() }
            w.sendText("""{"text":"dw-echo","is_final":true}""")
            check("wss echo round-trip (TLS + SNI + hostname check)", got.await(10, TimeUnit.SECONDS) && p.parse(echo)?.text == "dw-echo")
            w.close(); t.join(3000)
        } catch (e: Exception) { println("SKIP wss: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            WebSocketClient(URI("wss://wrong.host.badssl.com/")).connect()
            check("wss wrong-host cert rejected", false)
        } catch (e: Exception) { check("wss wrong-host cert rejected (${e.javaClass.simpleName})", e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException || e is java.io.IOException) }
    }

    if (args.contains("--wss")) {
        // Real Deepgram AU host, NO key: proves TLS/hostname + handshake reach it and that it demands auth.
        try {
            val (u, h) = C.buildRequest(C.DEFAULTS.getValue(C.K_URL), 16000, "token", "", "")
            WebSocketClient(java.net.URI(u), h).connect()
            check("real api.au.deepgram.com refuses no-key handshake", false)
        } catch (e: WebSocketClient.HandshakeException) {
            check("real api.au.deepgram.com: TLS ok, no-key handshake refused (HTTP ${e.status})", e.status == 401)
        } catch (e: Exception) { println("SKIP real Deepgram probe: ${e.javaClass.simpleName}") }
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Mirrors what StreamingLiveDictation does with the new defaults, including the composer text assembly. */
fun deepgramDefaults(port: String) {
    val d = C.DEFAULTS
    check("default URL is Deepgram AU", d[C.K_URL] == "wss://api.au.deepgram.com/v1/listen?encoding=linear16&sample_rate={sample_rate}&channels=1&interim_results=true")
    val (url, headers) = C.buildRequest(d.getValue(C.K_URL), 16000, d.getValue(C.K_AUTH), d.getValue(C.K_AUTH_NAME), "harness-test-key")
    check("sample_rate filled in", url.contains("sample_rate=16000") && !url.contains("{"))
    check("Token auth header", headers == mapOf("Authorization" to "Token harness-test-key"))
    check("key not in URL", !url.contains("harness-test-key"))
    // Same path + query, pointed at the local fake server instead of api.au.deepgram.com
    val local = url.replace("wss://api.au.deepgram.com", "ws://127.0.0.1:$port")
    val parser = TranscriptParser(d.getValue(C.K_TEXT), d.getValue(C.K_FINAL_FIELD), d.getValue(C.K_FINAL_VALUE))

    // Composer model: committed text + one live partial at the end (same rules as MainActivity.putLive).
    val composer = StringBuilder("Earlier typed text")
    var partial = ""
    val partialsSeen = mutableListOf<String>()
    fun put(text: String, final: Boolean) {
        val w = text.trim()
        if (final) { if (w.isNotEmpty()) composer.append(if (composer.isNotEmpty() && !composer.last().isWhitespace()) " " else "").append(w); partial = "" }
        else { partial = w; if (w.isNotEmpty()) partialsSeen += w }
    }
    val ws = WebSocketClient(java.net.URI(local), headers)
    ws.connect()
    check("deepgram: handshake with Token + query accepted", ws.isOpen)
    var code = -1
    val reader = Thread { ws.readLoop(object : WebSocketClient.Listener {
        override fun onText(text: String) { parser.parse(text)?.let { synchronized(composer) { put(it.text, it.isFinal) } } }
        override fun onClosed(c: Int, reason: String) { code = c }
    }) }.apply { start() }
    val chunk = ByteArray(3200) // 100 ms @ 16 kHz PCM16
    repeat(10) { ws.sendBinary(chunk); Thread.sleep(30) }
    Thread.sleep(300)
    synchronized(composer) { check("deepgram: live partial shown before stop", partial == "and eggs") }
    ws.sendText(d.getValue(C.K_END)) // {"type":"CloseStream"}
    reader.join(4000)
    check("deepgram: server closed 1000 after CloseStream", code == 1000)
    check("deepgram: interim results seen", partialsSeen.containsAll(listOf("note to", "note to self", "and eggs")))
    println("  composer: " + composer)
    check("deepgram: finals committed, metadata/UtteranceEnd/SpeechStarted ignored",
        composer.toString() == "Earlier typed text Note to self, buy milk. And eggs. [audio=32000 frames=10]" && partial == "")

    // Wrong auth style (Bearer) is refused like Deepgram does
    try {
        val (u2, h2) = C.buildRequest(d.getValue(C.K_URL), 16000, "bearer", "", "harness-test-key")
        WebSocketClient(java.net.URI(u2.replace("wss://api.au.deepgram.com", "ws://127.0.0.1:$port")), h2).connect()
        check("deepgram: Bearer refused", false)
    } catch (e: WebSocketClient.HandshakeException) { check("deepgram: Bearer refused with 401", e.status == 401) }

    // Idle stream gets closed (Deepgram closes after ~10 s without audio/KeepAlive; fake server: 2 s)
    val idle = WebSocketClient(java.net.URI(local), headers); idle.connect()
    var idleCode = -1
    val t = Thread { idle.readLoop(object : WebSocketClient.Listener { override fun onClosed(c: Int, reason: String) { idleCode = c } }) }.apply { start() }
    t.join(5000)
    check("deepgram: idle stream closed by server (${idleCode})", idleCode == 1011)
}

fun migration() {
    val m = C::migrateFromV013
    val newUrl = C.DEFAULTS.getValue(C.K_URL)
    // Anoop's phone: bare AU URL typed, auth left at old Bearer (stored), other fields never touched
    val r1 = m(mapOf(C.K_URL to "wss://api.au.deepgram.com/v1/listen", C.K_AUTH to "bearer"))
    check("migrate: bare AU URL gets Deepgram query", r1[C.K_URL] == newUrl)
    check("migrate: old Bearer default -> Token", r1[C.K_AUTH] == "token")
    check("migrate: unsaved fields untouched (defaults apply)", r1.keys == setOf(C.K_URL, C.K_AUTH))
    check("migrate: trailing slash + other region keeps host",
        m(mapOf(C.K_URL to "wss://api.deepgram.com/v1/listen/"))[C.K_URL] == "wss://api.deepgram.com/v1/listen?" + C.DEEPGRAM_QUERY)
    check("migrate: URL with own query left alone", m(mapOf(C.K_URL to "wss://api.au.deepgram.com/v1/listen?model=nova-3")).isEmpty())
    check("migrate: other provider URL left alone", m(mapOf(C.K_URL to "wss://streaming.assemblyai.com/v3/ws")).isEmpty())
    check("migrate: empty URL -> default", m(mapOf(C.K_URL to ""))[C.K_URL] == newUrl)
    check("migrate: old text/end defaults -> Deepgram",
        m(mapOf(C.K_TEXT to "text", C.K_END to "")) == mapOf(C.K_TEXT to "channel.alternatives.0.transcript", C.K_END to "{\"type\":\"CloseStream\"}"))
    check("migrate: user choices kept", m(mapOf(C.K_AUTH to "query", C.K_TEXT to "transcript", C.K_END to "{\"type\":\"Terminate\"}", C.K_FINAL_FIELD to "end_of_turn")).isEmpty())
    check("migrate: never touches API key / unknown keys", m(mapOf("stt_api_key" to "", "show_mic" to "")).isEmpty())
    check("migrate: already-new values unchanged", m(C.DEFAULTS).isEmpty())
}
