// JVM harness for the app's WebSocketClient + TranscriptParser (compiled together with the real app sources).
// Run: tools/ws-harness/run.sh   (needs the fake server in server.py)
import com.anoop.dailywhisper.LiveDictationConfig as C
import com.anoop.dailywhisper.Residency
import com.anoop.dailywhisper.SarvamConfig as S
import com.anoop.dailywhisper.SpeechProvider
import com.anoop.dailywhisper.SpeechSlots
import com.anoop.dailywhisper.SttConnector
import com.anoop.dailywhisper.SttFailure
import com.anoop.dailywhisper.SttLink
import com.anoop.dailywhisper.SttProtocol
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
    residency()
    pauseSetting()
    sarvamConfig()
    slotOrder()
    sarvamAuto()
    fallbackEndToEnd(port)
    sarvamAutoEndToEnd(port)

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
        // Real Sarvam AI host, NO key (there is no Sarvam key on the box): TLS + path reach it and it demands auth.
        try {
            val r = SttConnector.open(listOf(SttProtocol.sarvam("", S.DEFAULT_MODEL, S.DEFAULT_MODE, "ml", 1300)),
                { p -> WebSocketClient(URI(p.url), p.headers) }, object : SttLink.Sink {
                    override fun onUpdate(update: TranscriptParser.Update) {}
                    override fun onClosed(code: Int) {}
                }, 5000)
            val f = r.failures.singleOrNull()
            println("  real Sarvam no-key: status=${f?.status} network=${f?.network}")
            check("real api.sarvam.ai refuses no-key session (${f?.status})", r.link == null && f != null && !f.network && f.status in setOf(401, 403, 1003))
        } catch (e: Exception) { println("SKIP real Sarvam probe: ${e.javaClass.simpleName}") }
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Mirrors what StreamingLiveDictation does with the fixed Deepgram setup, including the composer text assembly. */
fun deepgramDefaults(port: String) {
    val url = C.url(C.DEFAULT_REGION, C.DEFAULT_MODEL, C.DEFAULT_LANGUAGE, C.DEFAULT_SMART_FORMAT)
    println("  default URL: $url")
    check("default URL (AU, nova-3, en, smart_format, linear16/16000/mono, interim, mip_opt_out, endpointing 1300 ms)",
        url == "wss://api.au.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true" +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&mip_opt_out=true&endpointing=1300")
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
    check("region in -> api.in.deepgram.com", C.url("in", "nova-3", "en", true).startsWith("wss://api.in.deepgram.com/v1/listen?"))
    check("unknown region -> Australia", C.url("xx", "nova-3", "en", true).startsWith("wss://api.au.deepgram.com/"))
    val u = C.url("au", " nova-2 ", "en-AU", false)
    check("model/language/smart_format from settings", u.contains("model=nova-2&language=en-AU&smart_format=false"))
    check("blank model/language -> defaults", C.url("au", "", " ", true).contains("model=nova-3&language=en&"))
    check("multi language", C.url("au", "nova-3", "multi", true).contains("language=multi"))
    check("odd input is URL-encoded", C.url("au", "nova 3&x=1", "en", true).contains("model=nova+3%26x%3D1&"))
    check("fixed params always present", listOf("encoding=linear16", "sample_rate=16000", "channels=1", "interim_results=true", "mip_opt_out=true")
        .all { C.url("eu", "nova-2", "multi", false).contains(it) })
    check("old keys list never includes the API key", "stt_api_key" !in C.OLD_KEYS)
}

fun languages() {
    val l = C.LANGUAGES
    check("languages: ${l.size} entries, codes unique", l.map { it.first }.toSet().size == l.size && l.size > 60)
    check("languages: multi first, labelled as Deepgram's", l.first() == ("multi" to "Multilingual (Deepgram)"))
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

/** Data residency: which Deepgram endpoint each region gets, and when the user must say yes first. */
fun residency() {
    val dg = C.REGIONS.map { it.first }.toSet()
    check("residency: every region maps to a real Deepgram endpoint",
        Residency.REGIONS.all { (it.home == null || it.home in dg) && it.nearest in dg })
    check("residency: codes unique", Residency.REGIONS.map { it.code }.toSet().size == Residency.REGIONS.size)
    check("residency: default is Australia, in-region", Residency.DEFAULT == "au" && Residency.endpointFor("au") == "au" && Residency.inRegion("au", "au"))
    check("residency: AU/EU/IN/US stay in region", listOf("au", "eu", "in", "us").all { Residency.inRegion(it, Residency.endpointFor(it)) })
    check("residency: NZ -> Australia, needs consent", Residency.endpointFor("nz") == "au" && !Residency.inRegion("nz", "au"))
    check("residency: UK & rest of Europe -> EU, needs consent", Residency.endpointFor("europe") == "eu" && !Residency.allowed("europe", "eu", ""))
    check("residency: consent for that endpoint allows it", Residency.allowed("nz", "au", "au"))
    check("residency: consent for another endpoint doesn't", !Residency.allowed("nz", "us", "au"))
    check("residency: in-region needs no consent", Residency.allowed("au", "au", ""))
    check("residency: AU user switched to US endpoint needs consent", !Residency.allowed("au", "us", ""))
    check("residency: unknown region -> default", Residency.region("zz").code == "au")
    check("residency: consent line names where audio goes", Residency.consentLine("nz", "au").contains("Australia") &&
        Residency.consentLine("nz", "au").contains("New Zealand"))
}

/** "Pause before a sentence ends" -> Deepgram endpointing (ms), clamped to the setting's range. */
fun pauseSetting() {
    check("pause: default 1300 ms", SpeechSlots.DEFAULT_PAUSE_MS == 1300)
    check("pause: endpointing=2000 when set", C.url("au", "nova-3", "en", true, 2000).endsWith("&endpointing=2000"))
    check("pause: clamped low/high", C.url("au", "nova-3", "en", true, 5).contains("endpointing=300") &&
        C.url("au", "nova-3", "en", true, 99999).contains("endpointing=5000"))
    check("pause: mip_opt_out still sent", C.url("au", "nova-3", "en", true, 2000).contains("mip_opt_out=true"))
}

/** Sarvam AI request building and reply parsing, with sample messages shaped like docs.sarvam.ai's realtime API. */
fun sarvamConfig() {
    val u = S.url(S.DEFAULT_MODEL, S.DEFAULT_MODE, "ml", 1300)
    println("  sarvam URL: $u")
    check("sarvam: default URL", u == "wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=ml-IN&model=saaras%3Av3-realtime" +
        "&mode=transcribe&stream_type=balanced&endpointing=vad&silence_duration_ms=1300&encoding=linear16&sample_rate=16000")
    check("sarvam: v4 + codemix", S.url("saaras:v4", "codemix", "hi", 900).let { it.contains("model=saaras%3Av4") && it.contains("mode=codemix") && it.contains("silence_duration_ms=900") })
    check("sarvam: unknown model/mode -> defaults", S.url("nova-3", "x", "ta", 1300).let { it.contains("model=saaras%3Av3-realtime") && it.contains("mode=transcribe") })
    check("sarvam: language map", S.languageCode("ml") == "ml-IN" && S.languageCode("en-AU") == "en-IN" && S.languageCode("multi") == "auto" &&
        S.languageCode("kok") == "kok-IN" && S.languageCode("or") == "or-IN" && S.languageCode("fr") == null && S.languageCode("zh-CN") == null)
    check("sarvam: 23 languages (22 Indian + English)", S.LANGUAGES.size == 23)
    check("sarvam: header auth only", S.headers("k") == mapOf("api-subscription-key" to "k") && S.headers("").isEmpty() && !u.contains("key"))
    val pcm = ByteArray(3200) { (it * 7).toByte() }
    val msg = S.audioMessage(pcm, 0, pcm.size)
    val audio = org.json.JSONObject(msg)
    check("sarvam: audio_input JSON with base64 PCM", audio.getString("event") == "audio_input" &&
        java.util.Base64.getDecoder().decode(audio.getString("audio")).contentEquals(pcm))
    val p = SttProtocol.sarvam("k", S.DEFAULT_MODEL, S.DEFAULT_MODE, "ml", 1300)
    check("sarvam: partial", p.parser.parse("""{"event":"transcript.partial","utterance_idx":0,"text":"നമസ്"}""") == TranscriptParser.Update("നമസ്", false))
    check("sarvam: final", p.parser.parse("""{"event":"transcript.final","utterance_idx":0,"text":"നമസ്കാരം.","language":"ml-IN"}""") == TranscriptParser.Update("നമസ്കാരം.", true, "ml-IN"))
    check("sarvam: no language field -> null", p.parser.parse("""{"event":"transcript.final","utterance_idx":0,"text":"x"}""")?.language == null)
    check("deepgram parser never reads a language", SttProtocol.deepgram("k", "au", "nova-3", "en", true, 1300).parser
        .parse("""{"channel":{"alternatives":[{"transcript":"a"}]},"is_final":true,"language":"en"}""")?.language == null)
    check("sarvam: session/vad/pong/end carry no text", listOf(
        """{"event":"session.begin","request_id":"r","config":{"model":"saaras:v3-realtime"}}""",
        """{"event":"vad.speech_start","utterance_idx":0}""", """{"event":"pong"}""",
        """{"event":"session.end","request_id":"r","audio_duration_s":"1.2"}""").all { p.parser.parse(it) == null })
    check("sarvam: error event is not text", p.parser.parse("""{"event":"error","code":"x","is_fatal":true,"message":"bad","status_code":401}""") == null)
    check("sarvam: session.begin = ready", p.isReady("""{"event":"session.begin","request_id":"r"}""") && !p.isReady("""{"event":"pong"}"""))
    check("sarvam: fatal error read", p.fatalError("""{"event":"error","code":"unauthorized","is_fatal":true,"message":"m","status_code":401}""") == (401 to "unauthorized"))
    check("sarvam: non-fatal error ignored", p.fatalError("""{"event":"error","code":"invalid_config","is_fatal":false,"message":"m"}""") == null)
    check("sarvam: India only", Residency.sarvamInRegion("in") && !Residency.sarvamInRegion("au") &&
        Residency.sarvamAllowed("au", true) && !Residency.sarvamAllowed("asia", false) && Residency.sarvamConsentLine("au").contains("India"))
}

/** Which slot is used: order, key, switch, language, residency. */
fun slotOrder() {
    fun slots(k1: Boolean = true, k2: Boolean = true, on1: Boolean = true, on2: Boolean = true, r1: Boolean = true, r2: Boolean = true) = listOf(
        SpeechSlots.Slot(1, SpeechProvider.DEEPGRAM, on1, k1, r1), SpeechSlots.Slot(2, SpeechProvider.SARVAM, on2, k2, r2),
        SpeechSlots.Slot(3, SpeechProvider.NONE, false, false, false), SpeechSlots.Slot(4, SpeechProvider.NONE, false, false, false))
    fun order(l: List<SpeechSlots.Slot>, lang: String) = SpeechSlots.candidates(l, lang).map { it.number }
    check("slots: 4 slots, Deepgram then Sarvam then empty", SpeechSlots.PROVIDERS == listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.NONE, SpeechProvider.NONE))
    check("slots: both set up -> 1 then 2", order(slots(), "en") == listOf(1, 2))
    check("slots: Hindi -> 1 then 2", order(slots(), "hi") == listOf(1, 2))
    check("slots: Malayalam -> only Sarvam", order(slots(), "ml") == listOf(2))
    check("slots: French -> only Deepgram", order(slots(), "fr") == listOf(1))
    check("slots: no Deepgram key -> Sarvam", order(slots(k1 = false), "en") == listOf(2))
    check("slots: Deepgram switched off -> Sarvam", order(slots(on1 = false), "en") == listOf(2))
    check("slots: Deepgram not allowed here -> Sarvam", order(slots(r1 = false), "en") == listOf(2))
    check("slots: unsorted input still in slot order", SpeechSlots.candidates(slots().reversed(), "en").map { it.number } == listOf(1, 2))
    check("slots: nothing for French without Deepgram", order(slots(k1 = false), "fr").isEmpty() &&
        SpeechSlots.whyNone(slots(k1 = false), "fr").contains("Deepgram API key"))
    check("slots: Malayalam without Sarvam key says so", SpeechSlots.whyNone(slots(k2 = false), "ml").let { it.contains("Sarvam AI API key") && it.contains("Malayalam") })
    check("slots: Malayalam needs India OK", SpeechSlots.whyNone(slots(r2 = false), "ml").contains("allow Sarvam AI"))
    check("slots: language list has Malayalam and everything Deepgram has", SpeechSlots.LANGUAGES.any { it.first == "ml" } &&
        C.LANGUAGES.all { d -> SpeechSlots.LANGUAGES.any { it.first == d.first } } &&
        SpeechSlots.LANGUAGES.map { it.first }.toSet().size == SpeechSlots.LANGUAGES.size)
    check("slots: every listed language has a provider", SpeechSlots.LANGUAGES.all { (c, _) ->
        SpeechSlots.supports(SpeechProvider.DEEPGRAM, c) || SpeechSlots.supports(SpeechProvider.SARVAM, c) })
    check("slots: Malayalam shows as Malayalam", SpeechSlots.languageName("ml") == "Malayalam")
}

/** "Auto (Sarvam AI)": Sarvam's own language detection (language_code=auto). Sarvam only, never Deepgram. */
fun sarvamAuto() {
    val a = SpeechSlots.AUTO_SARVAM
    fun slots(k1: Boolean = true, k2: Boolean = true, on2: Boolean = true, r2: Boolean = true) = listOf(
        SpeechSlots.Slot(1, SpeechProvider.DEEPGRAM, true, k1, true), SpeechSlots.Slot(2, SpeechProvider.SARVAM, on2, k2, r2),
        SpeechSlots.Slot(3, SpeechProvider.NONE, false, false, false), SpeechSlots.Slot(4, SpeechProvider.NONE, false, false, false))
    check("auto: listed second, right after Deepgram's Multilingual, before English",
        SpeechSlots.LANGUAGES.take(3).map { it.first } == listOf("multi", a, "en"))
    check("auto: label", SpeechSlots.LANGUAGES[1].second == "Auto (Sarvam AI)" && SpeechSlots.LANGUAGES[0].second == "Multilingual (Deepgram)")
    check("auto: hint names Malayalam and English", SpeechSlots.LANGUAGE_HINTS[a]!!.let { it.contains("Malayalam") && it.contains("English") } &&
        SpeechSlots.LANGUAGE_HINTS["multi"]!!.contains("Deepgram"))
    check("auto: English variants still follow", SpeechSlots.LANGUAGES.subList(2, 9).all { it.first.startsWith("en") })
    check("auto: Deepgram doesn't take it", !SpeechSlots.supports(SpeechProvider.DEEPGRAM, a) && SpeechSlots.supports(SpeechProvider.SARVAM, a))
    check("auto: only slot 2", SpeechSlots.candidates(slots(), a).map { it.number } == listOf(2))
    check("auto: no Sarvam key -> nothing, even with Deepgram", SpeechSlots.candidates(slots(k2 = false), a).isEmpty())
    check("auto: no Sarvam key message points to slot 2", SpeechSlots.whyNone(slots(k2 = false), a) ==
        "Add your Sarvam AI API key in Settings → Speech slot 2 to use Auto (Sarvam AI)")
    check("auto: Sarvam off message", SpeechSlots.whyNone(slots(on2 = false), a) == "Turn on speech slot 2 (Sarvam AI) in Settings to use Auto (Sarvam AI)")
    check("auto: not allowed message", SpeechSlots.whyNone(slots(r2 = false), a).let { it.contains("allow Sarvam AI (speech slot 2)") })
    check("auto: Sarvam code is auto", S.languageCode(a) == "auto" && S.AUTO_CODE == "auto")
    val u = S.url(S.DEFAULT_MODEL, S.DEFAULT_MODE, a, 1300)
    check("auto: URL carries language_code=auto", u.startsWith("wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=auto&model=saaras%3Av3-realtime"))
    check("auto: multi still Deepgram first, Sarvam auto as fallback", SpeechSlots.candidates(slots(), "multi").map { it.number } == listOf(1, 2) &&
        S.languageCode("multi") == "auto")
    check("auto: detected names", S.detectedName("ml-IN") == "Malayalam" && S.detectedName("en-IN") == "English" &&
        S.detectedName("or-IN") == "Odia" && S.detectedName("hi-IN") == "Hindi" && S.detectedName("") == null && S.detectedName(null) == null &&
        S.detectedName("xx-YY") == "xx-YY")
    check("auto: name for messages", SpeechSlots.languageName(a) == "Auto")
}

/** A whole "Auto (Sarvam AI)" session against the fake server: the detected language comes through with the text. */
fun sarvamAutoEndToEnd(port: String) {
    val updates = java.util.Collections.synchronizedList(mutableListOf<TranscriptParser.Update>())
    val sink = object : SttLink.Sink {
        override fun onUpdate(update: TranscriptParser.Update) { updates += update }
        override fun onClosed(code: Int) {}
    }
    val p = SttProtocol.sarvam("harness-test-key", S.DEFAULT_MODEL, S.DEFAULT_MODE, SpeechSlots.AUTO_SARVAM, 1300, "ws://127.0.0.1:$port")
    val r = SttConnector.open(listOf(p), { WebSocketClient(URI(it.url), it.headers) }, sink, 3000)
    val link = r.link
    check("auto e2e: opened", link != null && r.failures.isEmpty())
    if (link == null) return
    val chunk = ByteArray(3200) { (it % 199).toByte() }
    repeat(6) { link.sendAudio(chunk, chunk.size); Thread.sleep(40) }
    Thread.sleep(300)
    link.finish(3000, 1500)
    val got = synchronized(updates) { updates.toList() }
    println("  auto updates: $got")
    check("auto e2e: partial with detected Malayalam", got.contains(TranscriptParser.Update("നമസ്കാരം", false, "ml-IN")))
    check("auto e2e: final with detected Malayalam", got.contains(TranscriptParser.Update("നമസ്കാരം.", true, "ml-IN")))
    check("auto e2e: server got language_code=auto; later switch to English is reported",
        got.lastOrNull() == TranscriptParser.Update("lang=auto silence=1300 audio=19200 chunks=6", true, "en-IN") && link.closeCode == 1000)
}

/** Deepgram refuses the key -> Sarvam AI is used; the whole Sarvam session against the fake server. */
fun fallbackEndToEnd(port: String) {
    val updates = java.util.Collections.synchronizedList(mutableListOf<TranscriptParser.Update>())
    val sink = object : SttLink.Sink {
        override fun onUpdate(update: TranscriptParser.Update) { updates += update }
        override fun onClosed(code: Int) {}
    }
    val base = "ws://127.0.0.1:$port"
    val dgLocal = C.url("au", "nova-3", "en", true).replace("wss://api.au.deepgram.com", base)
    fun dg(key: String) = SttProtocol(SpeechProvider.DEEPGRAM, dgLocal, C.headers(key), C.END_MESSAGE, jsonAudio = false, waitForReady = false,
        parser = TranscriptParser(C.TEXT_PATH, C.FINAL_FIELD, C.FINAL_VALUE))
    fun sv(key: String) = SttProtocol.sarvam(key, S.DEFAULT_MODEL, S.DEFAULT_MODE, "ml", 1300, base)
    val client = { p: SttProtocol -> WebSocketClient(URI(p.url), p.headers) }

    val r = SttConnector.open(listOf(dg("wrong-secret"), sv("harness-test-key")), client, sink, 3000)
    check("fallback: Deepgram 401 -> Sarvam AI opened", r.link?.protocol?.provider == SpeechProvider.SARVAM &&
        r.failures.size == 1 && r.failures[0].provider == SpeechProvider.DEEPGRAM && r.failures[0].status == 401)
    check("fallback: failure text names Deepgram and the key, not the key value", r.failures[0].describe().let {
        it.contains("Deepgram refused the key") && !it.contains("wrong-secret") })
    val link = r.link!!
    val chunk = ByteArray(3200) { (it % 199).toByte() }
    repeat(6) { link.sendAudio(chunk, chunk.size); Thread.sleep(40) }
    Thread.sleep(300)
    link.finish(3000, 1500)
    val got = synchronized(updates) { updates.toList() }
    println("  sarvam updates: $got")
    check("sarvam e2e: partial Malayalam", got.contains(TranscriptParser.Update("നമസ്കാരം", false)))
    check("sarvam e2e: final Malayalam", got.contains(TranscriptParser.Update("നമസ്കാരം.", true)))
    check("sarvam e2e: server got all audio as JSON + the pause + ml-IN, then closed 1000",
        got.lastOrNull() == TranscriptParser.Update("lang=ml-IN silence=1300 audio=19200 chunks=6", true) && link.closed && link.closeCode == 1000)
    check("sarvam e2e: no session/vad/end noise in the text", got.none { it.text.isEmpty() || it.text.contains("session") })

    // Slot 1 fine -> it is used, slot 2 not touched.
    updates.clear()
    val r2 = SttConnector.open(listOf(dg("harness-test-key"), sv("harness-test-key")), client, sink, 3000)
    check("fallback: working Deepgram is used first", r2.link?.protocol?.provider == SpeechProvider.DEEPGRAM && r2.failures.isEmpty())
    r2.link?.abort()

    // Sarvam wrong key: accepted, then a fatal error + 1003 -> reported as a key problem, nothing opened.
    val r3 = SttConnector.open(listOf(sv("wrong-secret")), client, sink, 3000)
    check("sarvam: bad key -> fatal 401 before streaming", r3.link == null && r3.failures.single().status == 401)
    // Sarvam with no key at all: refused at HTTP level.
    val r4 = SttConnector.open(listOf(sv("")), client, sink, 3000)
    check("sarvam: no key -> HTTP 403", r4.link == null && r4.failures.single().status == 403)
    // Nothing reachable at all.
    val dead = SttProtocol.sarvam("k", S.DEFAULT_MODEL, S.DEFAULT_MODE, "ml", 1300, "ws://127.0.0.1:9")
    val r5 = SttConnector.open(listOf(dg("wrong-secret"), dead), client, sink, 1000)
    val all = SttConnector.describe(r5.failures)
    println("  all failed: $all")
    check("fallback: both fail -> one message naming both", r5.link == null && all.contains("Deepgram") && all.contains("Sarvam AI") &&
        r5.failures[1].network && !all.contains("wrong-secret"))
}
