// Box tests for the translation code that has no Android dependencies: TranslateConfig.kt (languages, prompt,
// chunking, GgufPatch) and ResumableDownload.kt (Range resume, verify, rename). Run via run.sh.
import com.anoop.dailywhisper.GgufPatch
import com.anoop.dailywhisper.ResumableDownload
import com.anoop.dailywhisper.TranslateConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("  ok   $name") } else { fail++; println("  FAIL $name $detail") }
}

fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val port = args[0].toInt(); val dir = File(args[1]); val mlkitCodes = args[2].split(' ').filter { it.isNotBlank() }.toSet()
    val realModel = args.getOrNull(3)?.let(::File)?.takeIf { it.exists() }

    println("languages")
    val ml = TranslateConfig.LANGUAGES.mapNotNull { it.mlkit }.toSet()
    check("ML Kit codes match TranslateLanguage in the AAR (${mlkitCodes.size})", ml == mlkitCodes, "diff=${(ml - mlkitCodes) + (mlkitCodes - ml)}")
    val hy = TranslateConfig.LANGUAGES.filter { it.hymt }.map { it.code }.toSet()
    val card = setOf("zh","en","fr","pt","es","ja","tr","ru","ar","ko","th","it","de","vi","ms","id","tl","hi","zh-Hant","pl","cs","nl",
        "km","my","fa","gu","ur","te","mr","he","bn","ta","uk","bo","kk","mn","ug","yue")
    check("Hy-MT codes match the model card (38)", hy == card, "diff=${(hy - card) + (card - hy)}")
    check("every Hy-MT language has a Chinese name", TranslateConfig.LANGUAGES.filter { it.hymt }.all { it.zh != null })
    check("codes unique", TranslateConfig.LANGUAGES.map { it.code }.toSet().size == TranslateConfig.LANGUAGES.size)
    check("names sorted", TranslateConfig.LANGUAGES.map { it.name } == TranslateConfig.LANGUAGES.map { it.name }.sortedBy { it.lowercase() })
    check("defaults are listed", listOf(TranslateConfig.DEFAULT_SOURCE, TranslateConfig.DEFAULT_TARGET).all { TranslateConfig.lang(it) != null })
    check("options(hymt) excludes Afrikaans", TranslateConfig.options("hymt").none { it.first == "af" } && TranslateConfig.options("mlkit").any { it.first == "af" })
    check("options(mlkit) excludes Tibetan", TranslateConfig.options("mlkit").none { it.first == "bo" })

    println("prompt")
    val p1 = TranslateConfig.hymtPrompt("I need milk.", "en", "hi")
    check("XX template", p1 == "<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following segment into Hindi, without additional explanation.\n\nI need milk.<｜hy_Assistant｜>", p1)
    val p2 = TranslateConfig.hymtPrompt("Hello", "en", "zh")
    check("ZH template (target Chinese)", p2.contains("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello"), p2)
    val p3 = TranslateConfig.hymtPrompt("你好", "zh", "fr")
    check("ZH template (source Chinese) uses Chinese target name", p3.contains("翻译为法语"), p3)

    println("chunks")
    val samples = listOf("", "one line", "a\nb\n\nc\n", "  lead\n\n\ttabs  \n", "x".repeat(1500),
        ("This is a sentence. ".repeat(80)).trim(), "Para one.\n\n" + "Long words ".repeat(100) + "\nlast",
        "नमस्ते दुनिया। ".repeat(60), "emoji 😀 test\r\nwindows line")
    for ((i, s) in samples.withIndex()) {
        val c = TranslateConfig.chunks(s, 600)
        check("sample $i round-trips (${c.size} pieces)", c.joinToString("") { it.text + it.sep } == s)
        check("sample $i pieces <= 600", c.all { it.text.length <= 600 }, c.map { it.text.length }.toString())
    }
    val c2 = TranslateConfig.chunks("Hello there.\n\nSecond para.", 600)
    check("paragraphs kept apart", c2.size == 2 && c2[0].sep == "\n\n" && c2[1].text == "Second para.", c2.toString())
    val c3 = TranslateConfig.chunks(("Short sentence here. ".repeat(50)).trim(), 200)
    check("long line cut at sentence ends", c3.dropLast(1).all { it.text.endsWith(".") }, c3.map { it.text.takeLast(3) }.toString())

    println("gguf patch")
    val g = File(dir, "synthetic.gguf"); g.writeBytes(syntheticGguf(listOf(42, 0, 42, 14, 43)))
    check("synthetic: 2 tensors still type 42", GgufPatch.unpatched(g) == 2)
    check("synthetic: patch rewrites 2", GgufPatch.patch(g) == 2)
    check("synthetic: idempotent", GgufPatch.patch(g) == 0 && GgufPatch.unpatched(g) == 0)
    val after = syntheticGguf(listOf(43, 0, 43, 14, 43))
    check("synthetic: only type ids changed", g.readBytes().contentEquals(after))
    val notGguf = File(dir, "x.bin").apply { writeBytes(ByteArray(64)) }
    check("non-GGUF rejected", runCatching { GgufPatch.patch(notGguf) }.isFailure)
    if (realModel != null) {
        val t0 = System.currentTimeMillis()
        val n = GgufPatch.unpatched(realModel)
        check("real Hy-MT GGUF: 224 STQ tensors to patch (scan ${System.currentTimeMillis() - t0} ms)", n == 224, "n=$n")
        System.getenv("PATCHED_REF")?.let(::File)?.takeIf { it.exists() }?.let { ref ->
            val copy = File(dir, "real.gguf"); realModel.copyTo(copy, overwrite = true)
            check("real: patch rewrites 224", GgufPatch.patch(copy) == 224)
            check("real: patched file == the one llama.cpp ran (sha256)", ResumableDownload.sha256Of(copy) == ResumableDownload.sha256Of(ref))
            copy.delete()
        }
    }

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

    if (args.contains("--hf")) {
        println("hugging face (real network, first 3 MB + resume, then aborts)")
        val d = File(dir, "hf"); d.deleteRecursively(); d.mkdirs()
        val part = File(d, "x.part"); val dest = File(d, "x.gguf")
        val dl1 = ResumableDownload(TranslateConfig.MODEL_URL, part, dest, TranslateConfig.MODEL_SIZE, TranslateConfig.MODEL_SHA256)
        var got = 0L
        val r1 = dl1.run({ got < 1_500_000 }, { b, _ -> got = b })
        check("HF: first part downloaded", r1 == ResumableDownload.Result.STOPPED && part.length() >= 1_500_000, "r=$r1 ${dl1.error} len=${part.length()}")
        val first = part.length()
        val r2 = ResumableDownload(TranslateConfig.MODEL_URL, part, dest, TranslateConfig.MODEL_SIZE, TranslateConfig.MODEL_SHA256)
            .run({ part.length() < first + 1_500_000 }, { _, _ -> })
        check("HF: Range resume appended", r2 == ResumableDownload.Result.STOPPED && part.length() > first)
        if (realModel != null) {
            val n = part.length().toInt()
            val mine = part.readBytes()
            val ref = ByteArray(n); java.io.RandomAccessFile(realModel, "r").use { it.readFully(ref) }
            // realModel is the pristine download; bytes before the tensor table must match exactly.
            check("HF: resumed bytes match the reference file", mine.contentEquals(ref))
        }
    }

    println("\n$pass passed, $fail failed")
    if (fail > 0) System.exit(1)
}

/** A minimal GGUF v3: one string KV, one string-array KV, then tensors with the given types. */
fun syntheticGguf(types: List<Int>): ByteArray {
    val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    fun str(s: String) { val x = s.toByteArray(); b.putLong(x.size.toLong()); b.put(x) }
    b.putInt(0x46554747); b.putInt(3); b.putLong(types.size.toLong()); b.putLong(3)
    str("general.name"); b.putInt(8); str("test")
    str("tokens"); b.putInt(9); b.putInt(8); b.putLong(3); str("a"); str("bb"); str("ccc")
    str("scores"); b.putInt(9); b.putInt(6); b.putLong(4); repeat(4) { b.putFloat(it.toFloat()) }
    for ((i, t) in types.withIndex()) { str("blk.$i.w"); b.putInt(2); b.putLong(256); b.putLong(4); b.putInt(t); b.putLong(i * 1024L) }
    b.put(ByteArray(64) { 0x5A })
    return b.array().copyOf(b.position())
}
