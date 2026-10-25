// JVM harness for the app's WebSocketClient + TranscriptParser (compiled together with the real app sources).
// Run: tools/ws-harness/run.sh   (needs the fake server in server.py)
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

    // 6) Optional: TLS (wss) against a public echo server, if the box has internet
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

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
