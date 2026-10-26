// Box tests for the translation code the app compiles (pure Kotlin): TranslateConfig (languages vs ML Kit's AAR,
// chunking), OpusMt (routes, JSON, tokenizer, real Opus-MT translation through ONNX Runtime), OpusMtCatalog, and
// ResumableDownload (Range resume, verify, rename). Run via run.sh.
import com.anoop.dailywhisper.MiniJson
import com.anoop.dailywhisper.OpusMt
import com.anoop.dailywhisper.OpusMtCatalog
import com.anoop.dailywhisper.OpusMtModel
import com.anoop.dailywhisper.ResumableDownload
import com.anoop.dailywhisper.TranslateConfig
import com.anoop.dailywhisper.UnigramTokenizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("  ok   $name") } else { fail++; println("  FAIL $name $detail") }
}
fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** args: port workdir "mlkit codes" [--opus <models root>] [--hf] */
fun main(args: Array<String>) {
    val port = args[0]; val dir = File(args[1]); val mlkitCodes = args[2].split(' ').filter { it.isNotBlank() }.toSet()
    val opusRoot = args.indexOf("--opus").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val hf = "--hf" in args

    println("languages")
    val ours = TranslateConfig.LANGUAGES.map { it.code }.toSet()
    check("codes match TranslateLanguage in the ML Kit AAR (${mlkitCodes.size})", ours == mlkitCodes, "diff=${(ours - mlkitCodes) + (mlkitCodes - ours)}")
    check("codes unique", ours.size == TranslateConfig.LANGUAGES.size)
    check("names sorted", TranslateConfig.LANGUAGES.map { it.name } == TranslateConfig.LANGUAGES.map { it.name }.sortedBy { it.lowercase() })
    check("defaults are listed", listOf(TranslateConfig.DEFAULT_SOURCE, TranslateConfig.DEFAULT_TARGET).all { TranslateConfig.lang(it) != null })
    check("options() = every language", TranslateConfig.options().size == TranslateConfig.LANGUAGES.size)
    check("unknown code -> no language, name falls back to code", TranslateConfig.lang("bo") == null && TranslateConfig.languageName("xx") == "xx")

    println("chunks")
    val samples = listOf("", "one line", "a\nb\n\nc\n", "  lead\n\n\ttabs  \n", "x".repeat(5000),
        ("This is a sentence. ".repeat(300)).trim(), "Para one.\n\n" + "Long words ".repeat(400) + "\nlast",
        "नमस्ते दुनिया। ".repeat(200), "emoji 😀 test\r\nwindows line")
    for (max in listOf(TranslateConfig.CHUNK_CHARS, OpusMt.CHUNK_CHARS)) for ((i, s) in samples.withIndex()) {
        val c = TranslateConfig.chunks(s, max)
        check("max $max sample $i round-trips, pieces <= max (${c.size})",
            c.joinToString("") { it.text + it.sep } == s && c.all { it.text.length <= max }, c.map { it.text.length }.toString())
    }
    val c2 = TranslateConfig.chunks("Hello there.\n\nSecond para.", TranslateConfig.CHUNK_CHARS)
    check("paragraphs kept apart", c2.size == 2 && c2[0].sep == "\n\n" && c2[1].text == "Second para.", c2.toString())
    val c3 = TranslateConfig.chunks(("Short sentence here. ".repeat(50)).trim(), 200)
    check("long line cut at sentence ends", c3.dropLast(1).all { it.text.endsWith(".") }, c3.map { it.text.takeLast(3) }.toString())

    println("opus-mt catalog + routes")
    val cat = OpusMtCatalog.MODELS
    check("${cat.size} models, every language is one the app offers (ML Kit list)",
        cat.keys.all { k -> k.split('-').let { it.size == 2 && it.all { c -> c in ours } } }, cat.keys.filter { k -> k.split('-').any { it !in ours } }.toString())
    check("each model: 4 files, 40-hex revision, 64-hex sha256, sizes > 0", cat.values.all { m ->
        m.files.map { it.local } == listOf("encoder.onnx", "decoder.onnx", "tokenizer.json", "config.json") &&
            Regex("[0-9a-f]{40}").matches(m.revision) && m.files.all { Regex("[0-9a-f]{64}").matches(it.sha256) && it.size > 0 } })
    check("URLs pin the revision", cat.getValue("en-de").let { m -> m.url(m.files[0]) ==
        "https://huggingface.co/Xenova/opus-mt-en-de/resolve/${m.revision}/onnx/encoder_model_quantized.onnx" })
    check("sizes ~100-140 MB per pair", cat.values.all { it.bytes in 100_000_000..140_000_000 }, cat.values.map { it.pair + "=" + it.bytes / 1_000_000 }.toString())
    check("route en->de direct", OpusMt.route("en", "de") == listOf("en-de"))
    check("route de->en direct", OpusMt.route("de", "en") == listOf("de-en"))
    check("route fr->de direct (no pivot when a direct model exists)", OpusMt.route("fr", "de") == listOf("fr-de"))
    check("route ja->hi via English", OpusMt.route("ja", "hi") == listOf("ja-en", "en-hi"))
    check("route ko->es via English", OpusMt.route("ko", "es") == listOf("ko-en", "en-es"))
    check("route same language -> none (ML Kit quietly)", OpusMt.route("en", "en") == null)
    check("route en->ja: no model (en-jap left out) -> ML Kit", OpusMt.route("en", "ja") == null)
    check("route en->ko: no model -> ML Kit", OpusMt.route("en", "ko") == null)
    check("route hi->ko: no second half -> ML Kit", OpusMt.route("hi", "ko") == null)
    check("route with an unknown code -> ML Kit", OpusMt.route("xx", "en") == null)

    println("json")
    val j = MiniJson.parse("""{"a":[1,-2.5e1,true,false,null],"s":"x\"y\\z\n\u00e9\u2581","o":{}, "e":[] }""") as Map<*, *>
    check("objects, arrays, numbers, literals", (j["a"] as List<*>) == listOf(1.0, -25.0, true, false, null) && j["o"] == emptyMap<String, Any>() && j["e"] == emptyList<Any>())
    check("string escapes incl. \\u", j["s"] == "x\"y\\z\né▁")
    check("bad JSON throws", runCatching { MiniJson.parse("{\"a\":}") }.isFailure && runCatching { MiniJson.parse("[1] 2") }.isFailure)

    println("tokenizer (toy vocab)")
    val toy = UnigramTokenizer(listOf("</s>" to 0.0, "<unk>" to 0.0, "▁" to -2.0, "▁he" to -3.0, "llo" to -3.0, "▁hello" to -4.0,
        "h" to -5.0, "e" to -5.0, "l" to -5.0, "o" to -5.0, "<pad>" to 0.0))
    check("best split wins (▁hello over ▁he+llo)", toy.encode("hello").toList() == listOf(5, 0))
    check("unknown chars -> one <unk> per run, then </s>", toy.encode("hexx").toList() == listOf(3, 1, 0), toy.encode("hexx").toList().toString())
    check("decode joins pieces, ▁ -> space, drops specials", toy.decode(intArrayOf(5, 3, 4, 1, 0, 10), setOf(0, 10)) == "hello hello")

    if (opusRoot != null) opus(opusRoot)
    downloads(port, dir)
    if (hf) huggingFace(dir)

    println("\n$pass passed, $fail failed")
    if (fail > 0) System.exit(1)
}

/** The real thing: tokenizer ids and greedy translations must equal the Python reference (opus-ref.json). */
@Suppress("UNCHECKED_CAST")
fun opus(root: File) {
    println("opus-mt (real models in $root)")
    val ref = MiniJson.parse(File("opus-ref.json").readText()) as List<Map<String, Any?>>
    val models = HashMap<String, OpusMtModel>()
    val toks = HashMap<String, UnigramTokenizer>()
    for (r in ref) {
        val pair = r["pair"] as String; val text = r["text"] as String
        val tok = toks.getOrPut(pair) { UnigramTokenizer.fromJson(File(root, "$pair/tokenizer.json").readText()) }
        val want = (r["ids"] as List<Number>).map { it.toInt() }
        check("$pair ids == HF tokenizers: \"${text.take(40)}\"", tok.encode(text).toList() == want, "got ${tok.encode(text).toList()} want $want")
        val m = models.getOrPut(pair) { OpusMtModel(File(root, pair), 1) } // 1 thread = exactly repeatable (see make_ref.py)
        val t0 = System.nanoTime()
        val got = m.translate(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        check("$pair translation == Python onnxruntime ($ms ms): \"$got\"", got == r["translation"], "want \"${r["translation"]}\"")
    }
    // A whole note the way the app does it: line by line, layout kept, through the English pivot.
    val note = "Weekend plan\n\n- Call mum on Sunday.\n- Buy flowers for the party."
    val de = TranslateConfig.chunks(note, OpusMt.CHUNK_CHARS).joinToString("") { p ->
        (if (p.text.isBlank()) p.text else models.getValue("en-de").translate(p.text)!!) + p.sep }
    println("     note en->de: ${de.replace("\n", " ⏎ ")}")
    check("note keeps its lines", de.count { it == '\n' } == 3 && de.split("\n")[1].isEmpty())
    val pivot = models.getValue("de-en").translate("Ich treffe Sarah morgen früh am Bahnhof.")!!.let { models.getValue("en-hi").translate(it)!! }
    println("     pivot de->en->hi: $pivot")
    check("pivot de->en->hi gives Devanagari", pivot.any { it in '\u0900'..'\u097F' })
    var calls = 0
    check("cancel stops a translation (null)", models.getValue("en-de").translate("A fairly long sentence to translate here.") { ++calls > 3 } == null)
    check("blank text comes back as is", models.getValue("en-de").translate("   ") == "   ")
    models.values.forEach { it.close() }
}

fun downloads(port: String, dir: File) {
    println("download")
    val data = Random(7).nextBytes(3_500_000)
    File(dir, "m.bin").writeBytes(data)
    val want = sha(data)
    fun dl(mode: String, part: File, dest: File, s: String = want, size: Long = data.size.toLong()) =
        ResumableDownload("http://127.0.0.1:$port/$mode/m.bin", part, dest, size, s)
    fun fresh(n: String): Pair<File, File> { val d = File(dir, n); d.deleteRecursively(); d.mkdirs(); return File(d, "m.part") to File(d, "m.bin") }
    run {
        val (part, dest) = fresh("full")
        var last = 0L
        val r = dl("redirect", part, dest).run({ true }, { b, _ -> last = b })
        check("full download via redirect", r == ResumableDownload.Result.DONE && dest.readBytes().contentEquals(data) && !part.exists())
        check("progress reached total", last == data.size.toLong())
        check("already done is instant", dl("range", part, dest).run({ error("no network expected") }, { _, _ -> }) == ResumableDownload.Result.DONE)
    }
    run {
        val (part, dest) = fresh("pause")
        var reads = 0
        val r1 = dl("range", part, dest).run({ ++reads < 5 }, { _, _ -> })
        val mid = part.length()
        check("pause leaves a partial file", r1 == ResumableDownload.Result.STOPPED && mid in 1 until data.size, "len=$mid")
        val r2 = dl("range", part, dest).run({ true }, { _, _ -> })
        check("resume with Range completes and verifies", r2 == ResumableDownload.Result.DONE && dest.readBytes().contentEquals(data))
    }
    run {
        val (part, dest) = fresh("drop")
        val d = dl("drop", part, dest)
        val r1 = d.run({ true }, { _, _ -> })
        check("dropped connection -> FAILED, partial kept", r1 == ResumableDownload.Result.FAILED && part.length() in 1 until data.size,
            "r=$r1 len=${part.length()} err=${d.error}")
        val r2 = dl("drop", part, dest).run({ true }, { _, _ -> })
        check("retry picks up from the partial file", r2 == ResumableDownload.Result.DONE && dest.readBytes().contentEquals(data))
    }
    run {
        val (part, dest) = fresh("norange")
        part.writeBytes(data.copyOf(1_234_567))
        val r = dl("norange", part, dest).run({ true }, { _, _ -> })
        check("server ignoring Range -> starts over, still correct", r == ResumableDownload.Result.DONE && dest.readBytes().contentEquals(data))
    }
    run {
        val (part, dest) = fresh("badstart")
        part.writeBytes(data.copyOf(1_000_000))
        val d = dl("badstart", part, dest)
        val r = d.run({ true }, { _, _ -> })
        check("wrong Content-Range -> partial dropped, FAILED", r == ResumableDownload.Result.FAILED && !part.exists(), "r=$r ${d.error}")
        check("then a clean retry works", dl("range", part, dest).run({ true }, { _, _ -> }) == ResumableDownload.Result.DONE)
    }
    run {
        val (part, dest) = fresh("badsha")
        val d = dl("range", part, dest, s = "00".repeat(32))
        val r = d.run({ true }, { _, _ -> })
        check("bad SHA-256 -> removed, FAILED", r == ResumableDownload.Result.FAILED && !part.exists() && !dest.exists(), "${d.error}")
    }
    run {
        val (part, dest) = fresh("toobig")
        val d = dl("range", part, dest, size = 1_000_000)
        val r = d.run({ true }, { _, _ -> })
        check("bigger than expected -> FAILED, removed", r == ResumableDownload.Result.FAILED && !part.exists(), "${d.error}")
    }
    run {
        val (part, dest) = fresh("full416")
        part.writeBytes(data) // complete part but not yet verified/renamed (e.g. killed during verify)
        val r = dl("range", part, dest).run({ error("no network expected") }, { _, _ -> })
        check("complete part file -> verify + rename without network", r == ResumableDownload.Result.DONE && dest.exists())
    }
}

/** Real Hugging Face: every catalog file exists at its pinned revision with the listed size; Range resume works there. */
fun huggingFace(dir: File) {
    println("hugging face (pinned revisions)")
    val bad = ArrayList<String>()
    for (m in OpusMtCatalog.MODELS.values) for (f in m.files) {
        val c = URL(m.url(f)).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept-Encoding", "identity")
        val code = c.responseCode
        val size = c.contentLengthLong
        if (code != 200 || size != f.size) bad.add("${m.pair}/${f.local} code=$code size=$size")
        c.disconnect()
    }
    check("all ${OpusMtCatalog.MODELS.size * 4} catalog files exist with the listed size", bad.isEmpty(), bad.take(5).toString())
    val m = OpusMtCatalog.MODELS.getValue("en-hi")
    val tokF = m.files.first { it.local == "tokenizer.json" }
    val d = File(dir, "hf").apply { deleteRecursively(); mkdirs() }
    run {
        var reads = 0
        val r1 = ResumableDownload(m.url(tokF), File(d, "t.part"), File(d, "tokenizer.json"), tokF.size, tokF.sha256).run({ ++reads < 6 }, { _, _ -> })
        val mid = File(d, "t.part").length()
        val r2 = ResumableDownload(m.url(tokF), File(d, "t.part"), File(d, "tokenizer.json"), tokF.size, tokF.sha256).run({ true }, { _, _ -> })
        check("HF tokenizer.json: pause at ${mid / 1000} kB, resume, SHA-256 matches", r1 == ResumableDownload.Result.STOPPED &&
            r2 == ResumableDownload.Result.DONE && File(d, "tokenizer.json").length() == tokF.size)
    }
    run {
        val enc = m.files.first { it.local == "encoder.onnx" }
        var reads = 0
        val dl1 = ResumableDownload(m.url(enc), File(d, "e.part"), File(d, "encoder.onnx"), enc.size, enc.sha256)
        val r1 = dl1.run({ ++reads < 8 }, { _, _ -> })
        val first = File(d, "e.part").length()
        reads = 0
        val r2 = ResumableDownload(m.url(enc), File(d, "e.part"), File(d, "encoder.onnx"), enc.size, enc.sha256).run({ ++reads < 8 }, { _, _ -> })
        check("HF encoder (LFS via CDN redirect): Range resume appended (${first / 1000} kB -> ${File(d, "e.part").length() / 1000} kB)",
            r1 == ResumableDownload.Result.STOPPED && r2 == ResumableDownload.Result.STOPPED && File(d, "e.part").length() > first)
    }
}
