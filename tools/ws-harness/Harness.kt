// JVM harness for the app's WebSocketClient + TranscriptParser (compiled together with the real app sources).
// Run: tools/ws-harness/run.sh   (needs the fake server in server.py)
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
    urlBuilder()
    languages()

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
            val u = C.url(C.DEFAULT_REGION, C.DEFAULT_MODEL, C.DEFAULT_LANGUAGE, C.DEFAULT_SMART_FORMAT)
            WebSocketClient(java.net.URI(u), C.headers("")).connect()
            check("real api.au.deepgram.com refuses no-key handshake", false)
        } catch (e: WebSocketClient.HandshakeException) {
            check("real api.au.deepgram.com: TLS ok, no-key handshake refused (HTTP ${e.status})", e.status == 401)
        } catch (e: Exception) { println("SKIP real Deepgram probe: ${e.javaClass.simpleName}") }
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Mirrors what StreamingLiveDictation does with the fixed Deepgram setup, including the composer text assembly. */
fun deepgramDefaults(port: String) {
    val url = C.url(C.DEFAULT_REGION, C.DEFAULT_MODEL, C.DEFAULT_LANGUAGE, C.DEFAULT_SMART_FORMAT)
    println("  default URL: $url")
    check("default URL (AU, nova-3, en, smart_format, linear16/16000/mono, interim)",
        url == "wss://api.au.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true" +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true")
    val headers = C.headers("harness-test-key")
    check("Token auth header", headers == mapOf("Authorization" to "Token harness-test-key"))
    check("key not in URL", !url.contains("harness-test-key"))
    check("no key -> no auth header", C.headers("").isEmpty())
    val local = url.replace("wss://api.au.deepgram.com", "ws://127.0.0.1:$port")
    val parser = TranscriptParser(C.TEXT_PATH, C.FINAL_FIELD, C.FINAL_VALUE)

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
    check("deepgram: handshake with Token + full query accepted", ws.isOpen)
    var closeCode = -1
    val reader = Thread { ws.readLoop(object : WebSocketClient.Listener {
        override fun onText(text: String) { parser.parse(text)?.let { synchronized(composer) { put(it.text, it.isFinal) } } }
        override fun onClosed(code: Int, reason: String) { closeCode = code }
    }) }.apply { start() }
    val chunk = ByteArray(C.SAMPLE_RATE * C.CHUNK_MS / 1000 * 2) // 100 ms @ 16 kHz PCM16 = 3200 bytes
    repeat(10) { ws.sendBinary(chunk); Thread.sleep(30) }
    Thread.sleep(300)
    synchronized(composer) { check("deepgram: live partial shown before stop", partial == "and eggs") }
    ws.sendText(C.END_MESSAGE)
    reader.join(4000)
    check("deepgram: CloseStream -> server closed 1000", closeCode == 1000)
    check("deepgram: interim results seen", partialsSeen.containsAll(listOf("note to", "note to self", "and eggs")))
    println("  composer: " + composer)
    check("deepgram: finals committed, Metadata/UtteranceEnd/SpeechStarted ignored",
        composer.toString() == "Earlier typed text Note to self, buy milk. And eggs. [audio=32000 frames=10]" && partial == "")

    // Bearer is refused like Deepgram does
    try {
        WebSocketClient(java.net.URI(local), mapOf("Authorization" to "Bearer harness-test-key")).connect()
        check("deepgram: Bearer refused", false)
    } catch (e: WebSocketClient.HandshakeException) { check("deepgram: Bearer refused with 401", e.status == 401) }

    // Idle stream gets closed (Deepgram: ~10 s without audio/KeepAlive; fake server: 2 s)
    val idle = WebSocketClient(java.net.URI(local), headers); idle.connect()
    var idleCode = -1
    val t = Thread { idle.readLoop(object : WebSocketClient.Listener { override fun onClosed(code: Int, reason: String) { idleCode = code } }) }.apply { start() }
    t.join(5000)
    check("deepgram: idle stream closed by server ($idleCode)", idleCode == 1011)
}

fun urlBuilder() {
    check("region us -> api.deepgram.com", C.url("us", "nova-3", "en", true).startsWith("wss://api.deepgram.com/v1/listen?"))
    check("region eu -> api.eu.deepgram.com", C.url("eu", "nova-3", "en", true).startsWith("wss://api.eu.deepgram.com/v1/listen?"))
    check("unknown region -> Australia", C.url("xx", "nova-3", "en", true).startsWith("wss://api.au.deepgram.com/"))
    val u = C.url("au", " nova-2 ", "en-AU", false)
    check("model/language/smart_format from settings", u.contains("model=nova-2&language=en-AU&smart_format=false"))
    check("blank model/language -> defaults", C.url("au", "", " ", true).contains("model=nova-3&language=en&"))
    check("multi language", C.url("au", "nova-3", "multi", true).contains("language=multi"))
    check("odd input is URL-encoded", C.url("au", "nova 3&x=1", "en", true).contains("model=nova+3%26x%3D1&"))
    check("fixed params always present", listOf("encoding=linear16", "sample_rate=16000", "channels=1", "interim_results=true")
        .all { C.url("eu", "nova-2", "multi", false).contains(it) })
    check("old keys list never includes the API key", "stt_api_key" !in C.OLD_KEYS)
}

fun languages() {
    val l = C.LANGUAGES
    check("languages: ${l.size} entries, codes unique", l.map { it.first }.toSet().size == l.size && l.size > 60)
    check("languages: multi first", l.first() == ("multi" to "Multilingual (auto, mixes languages)"))
    check("languages: default 'en' shows as English", C.languageName(C.DEFAULT_LANGUAGE) == "English")
    check("languages: en-AU / en-IN / hi / zh-CN names",
        C.languageName("en-AU") == "English (Australia)" && C.languageName("en-IN") == "English (India)" &&
            C.languageName("hi") == "Hindi" && C.languageName("zh-CN") == "Chinese (Mandarin, Simplified)")
    val rest = l.drop(8).map { it.second }
    check("languages: non-English sorted by name", rest == rest.sorted())
    check("languages: old free text maps case-insensitively", C.canonicalLanguage(" en-au ") == "en-AU" && C.canonicalLanguage("MULTI") == "multi")
    check("languages: unknown free text -> null (falls back to English)", C.canonicalLanguage("klingon") == null)
    check("languages: every code builds a valid URL", l.all { java.net.URI(C.url("au", "nova-3", it.first, true)).host == "api.au.deepgram.com" })
}
