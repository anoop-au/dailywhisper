package com.anoop.dailywhisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Opus-MT (Helsinki-NLP MarianMT) on the phone with ONNX Runtime. Pure Kotlin + the ai.onnxruntime API, no Android
 * imports, so tools/translate-harness runs exactly this code on the box against the same model files.
 */
object OpusMt {
    /** Which models turn [source] into [target]: the direct pair, else through English, else null (use ML Kit). */
    fun route(source: String, target: String, catalog: Map<String, OpusMtCatalog.Model> = OpusMtCatalog.MODELS): List<String>? {
        if (source == target) return null
        "$source-$target".let { if (it in catalog) return listOf(it) }
        if (source == "en" || target == "en") return null
        val a = "$source-en"; val b = "en-$target"
        return if (a in catalog && b in catalog) listOf(a, b) else null
    }

    /** Opus-MT does best a sentence or short paragraph at a time; lines are kept exactly (TranslateConfig.chunks). */
    const val CHUNK_CHARS = 400
}

/** Just enough JSON for tokenizer.json and config.json: objects, arrays, strings, numbers, true/false/null. */
class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run { val v = value(); ws(); require(i == s.length) { "Trailing JSON at $i" }; v }
    }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "Unexpected end of JSON" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("Bad JSON at $i")
        }
    }

    private fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "Bad JSON at $i" }; i += w.length; return v }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws()
            require(s[i] == ':') { "Expected : at $i" }; i++
            m[k] = value(); ws()
            when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("Expected , or } at ${i - 1}") }
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value()); ws()
            when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("Expected , or ] at ${i - 1}") }
        }
    }

    private fun str(): String {
        require(s[i] == '"') { "Expected string at $i" }
        i++
        val start = i
        while (s[i] != '"' && s[i] != '\\') i++
        if (s[i] == '"') return s.substring(start, i++)
        val b = StringBuilder().append(s, start, i)
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }
}

/**
 * The SentencePiece unigram tokenizer from a Marian tokenizer.json: NFKC, split on whitespace, "▁" before each word,
 * best-scoring split (Viterbi), then </s>. Unknown characters become <unk> (runs of them fused into one, as
 * SentencePiece does).
 */
class UnigramTokenizer(pieces: List<Pair<String, Double>>) {
    private val ids = HashMap<String, Int>(pieces.size * 2)
    private val scores = DoubleArray(pieces.size)
    private val text = Array(pieces.size) { pieces[it].first }
    private val maxLen: Int
    private val unkScore: Double
    val unk: Int
    val eos: Int

    init {
        var longest = 1
        pieces.forEachIndexed { id, (p, sc) -> ids.putIfAbsent(p, id); scores[id] = sc; longest = maxOf(longest, p.length) }
        maxLen = longest
        unkScore = (scores.minOrNull() ?: 0.0) - 10.0
        unk = ids["<unk>"] ?: 1
        eos = ids["</s>"] ?: 0
    }

    fun encode(input: String): IntArray {
        val out = ArrayList<Int>()
        for (word in Normalizer.normalize(input, Normalizer.Form.NFKC).split(WS)) {
            if (word.isEmpty()) continue
            viterbi("▁$word", out)
        }
        out.add(eos)
        return out.toIntArray()
    }

    private fun viterbi(w: String, out: MutableList<Int>) {
        val n = w.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1); val tok = IntArray(n + 1)
        best[0] = 0.0
        for (start in 0 until n) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            var single = false
            val charEnd = start + Character.charCount(w.codePointAt(start))
            val limit = minOf(n, start + maxLen)
            for (end in start + 1..limit) {
                val id = ids[w.substring(start, end)] ?: continue
                if (end == charEnd) single = true
                val sc = best[start] + scores[id]
                if (sc > best[end]) { best[end] = sc; from[end] = start; tok[end] = id }
            }
            if (!single) {
                val sc = best[start] + unkScore
                if (sc > best[charEnd]) { best[charEnd] = sc; from[charEnd] = start; tok[charEnd] = -1 }
            }
        }
        val rev = ArrayList<Int>()
        var at = n
        while (at > 0) { rev.add(tok[at]); at = from[at] }
        var lastUnk = false
        for (k in rev.indices.reversed()) {
            val t = rev[k]
            if (t == -1) { if (!lastUnk) out.add(unk); lastUnk = true } else { out.add(t); lastUnk = false }
        }
    }

    /** Ids back to text; special tokens dropped, "▁" becomes a space. */
    fun decode(tokens: IntArray, skip: Set<Int>): String {
        val b = StringBuilder()
        for (t in tokens) if (t !in skip && t != unk && t in text.indices) b.append(text[t])
        return b.toString().replace('▁', ' ').trim()
    }

    companion object {
        private val WS = Regex("\\s+")

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): UnigramTokenizer {
            val root = MiniJson.parse(json) as Map<String, Any?>
            val model = root["model"] as Map<String, Any?>
            require(model["type"] == "Unigram") { "Not a unigram tokenizer" }
            val vocab = (model["vocab"] as List<List<Any?>>).map { (it[0] as String) to (it[1] as Number).toDouble() }
            return UnigramTokenizer(vocab)
        }
    }
}

/** One Opus-MT model on disk (encoder.onnx, decoder.onnx = merged decoder with KV cache, tokenizer.json, config.json). */
class OpusMtModel(dir: File, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val tokenizer: UnigramTokenizer
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val layers: Int; private val heads: Int; private val headDim: Int
    private val startId: Int; private val eosId: Int; private val padId: Int; private val maxLength: Int

    init {
        @Suppress("UNCHECKED_CAST")
        val cfg = MiniJson.parse(File(dir, "config.json").readText()) as Map<String, Any?>
        fun int(k: String, def: Int) = (cfg[k] as? Number)?.toInt() ?: def
        layers = int("decoder_layers", 6); heads = int("decoder_attention_heads", 8); headDim = int("d_model", 512) / heads
        padId = int("pad_token_id", 0); eosId = int("eos_token_id", 0)
        startId = int("decoder_start_token_id", padId); maxLength = int("max_length", 512)
        tokenizer = UnigramTokenizer.fromJson(File(dir, "tokenizer.json").readText())
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceAtLeast(1))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(File(dir, "encoder.onnx").path, opts)
        decoder = try { env.createSession(File(dir, "decoder.onnx").path, opts) } catch (e: Exception) { encoder.close(); throw e }
        opts.close()
    }

    /** Greedy decoding. Returns null if [cancelled] said stop. */
    fun translate(text: String, cancelled: () -> Boolean = { false }): String? {
        if (text.isBlank()) return text
        val src = tokenizer.encode(text).let { if (it.size > maxLength) it.copyOfRange(0, maxLength - 1) + tokenizer.eos else it }
        val n = src.size.toLong()
        val opened = ArrayList<AutoCloseable>()
        try {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(src.size) { src[it].toLong() }), longArrayOf(1, n)).also(opened::add)
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(src.size) { 1L }), longArrayOf(1, n)).also(opened::add)
            val enc = encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).also(opened::add)
            val hidden = enc.get(0) as OnnxTensor
            val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong())).also(opened::add)
            val noCache = OnnxTensor.createTensor(env, booleanArrayOf(false)).also(opened::add)
            val useCache = OnnxTensor.createTensor(env, booleanArrayOf(true)).also(opened::add)
            val past = HashMap<String, OnnxTensor>()
            for (l in 0 until layers) for (k in arrayOf("decoder", "encoder")) for (v in arrayOf("key", "value")) past["past_key_values.$l.$k.$v"] = empty
            val out = ArrayList<Int>()
            var cur = startId
            var prev: OrtSession.Result? = null
            val steps = minOf(maxLength, src.size * 3 + 10)
            for (step in 0 until steps) {
                if (cancelled()) return null
                val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(cur.toLong())), longArrayOf(1, 1))
                val feed = HashMap<String, OnnxTensor>(past)
                feed["encoder_attention_mask"] = mask; feed["input_ids"] = tok; feed["encoder_hidden_states"] = hidden
                feed["use_cache_branch"] = if (step == 0) noCache else useCache
                val res = try { decoder.run(feed) } finally { tok.close() }
                val logits = (res.get(0) as OnnxTensor).floatBuffer
                var bestId = -1; var bestVal = Float.NEGATIVE_INFINITY
                for (v in 0 until logits.limit()) {
                    if (v == padId) continue
                    val x = logits.get(v)
                    if (x > bestVal) { bestVal = x; bestId = v }
                }
                // Keep this step's cache: the encoder part from step 0 (kept open), the decoder part from the latest step.
                for (e in res) {
                    val name = e.key
                    if (!name.startsWith("present.")) continue
                    val key = "past_key_values." + name.removePrefix("present.")
                    if (step == 0 || ".decoder." in key) past[key] = e.value as OnnxTensor
                }
                if (step == 0) opened.add(res) else prev?.close()
                prev = if (step == 0) null else res
                if (bestId == eosId || bestId < 0) break
                out.add(bestId); cur = bestId
            }
            prev?.close()
            return tokenizer.decode(out.toIntArray(), setOf(eosId, padId))
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    override fun close() { runCatching { decoder.close() }; runCatching { encoder.close() } }
}
