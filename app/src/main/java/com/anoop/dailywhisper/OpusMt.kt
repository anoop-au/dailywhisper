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

    /** Beam search width: the default (the models' own num_beams) and the most Settings allows. */
    const val DEFAULT_BEAMS = 4
    const val MAX_BEAMS = 6

    /**
     * Hypothesis score = summed log probability / length ** this. With 1.0 (a plain average) en-zh kept going on short
     * sentences ("I was tired." -> 我累了,我累了。 我累了…) because repeats score well on average; 0 (plain sum) fixed that
     * but began dropping words ("almost") from long ones. 0.5 does both (tools/translate-harness/opus-ref.json).
     */
    const val LENGTH_PENALTY = 0.5
}

/**
 * Decoding ran to the step limit without ending: if a span of 2–16 tokens repeats 3+ times in a row (a loop), keep its
 * first copy and drop the rest.
 */
fun loopCut(ids: IntArray): IntArray {
    val n = ids.size
    for (i in 0 until n) for (k in 2..16) {
        if (i + 3 * k > n) break
        var same = true
        for (j in 0 until k) if (ids[i + j] != ids[i + k + j] || ids[i + j] != ids[i + 2 * k + j]) { same = false; break }
        if (same) return ids.copyOfRange(0, i + k)
    }
    return ids
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

    /**
     * Beam search over [beams] candidates (1 = greedy), the way the models were tuned (their config.json says 4): greedy
     * decoding loops on some short sentences ("I came home." -> 我回到家,我回到家,…) and cuts some long ones short. All
     * beams run as one batch, so 4 beams cost far less than 4 greedy runs. A hypothesis' score is its summed log
     * probability (see [OpusMt.LENGTH_PENALTY]); search stops once [beams] have ended and none still running
     * could beat them, or at the step limit (then a run that's looping keeps one copy of the loop, see [loopCut]).
     * tools/translate-harness/make_ref.py implements the same steps in Python. Returns null if [cancelled] said stop.
     */
    fun translate(text: String, beams: Int = 1, cancelled: () -> Boolean = { false }): String? {
        if (text.isBlank()) return text
        val nb = beams.coerceIn(1, OpusMt.MAX_BEAMS)
        val src = tokenizer.encode(text).let { if (it.size > maxLength) it.copyOfRange(0, maxLength - 1) + tokenizer.eos else it }
        val n = src.size
        val opened = ArrayList<AutoCloseable>()
        var decPast = HashMap<String, OnnxTensor>() // this step's decoder self-attention cache (ours, closed when replaced)
        try {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { src[it].toLong() }), longArrayOf(1, n.toLong())).also(opened::add)
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), longArrayOf(1, n.toLong())).also(opened::add)
            val enc = encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).also(opened::add)
            val hidden = enc.get(0) as OnnxTensor
            val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong())).also(opened::add)
            val noCache = OnnxTensor.createTensor(env, booleanArrayOf(false)).also(opened::add)
            val useCache = OnnxTensor.createTensor(env, booleanArrayOf(true)).also(opened::add)
            // Batch-nb copies of the encoder output and mask, used from step 1 on.
            val maskN = if (nb == 1) mask else OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(nb * n) { 1L }), longArrayOf(nb.toLong(), n.toLong())).also(opened::add)
            val hiddenN = if (nb == 1) hidden else repeatRows(hidden, nb).also(opened::add)
            val encPast = HashMap<String, OnnxTensor>() // cross-attention cache: from step 0, the same for every beam
            for (l in 0 until layers) for (v in arrayOf("key", "value")) {
                encPast["past_key_values.$l.encoder.$v"] = empty; decPast["past_key_values.$l.decoder.$v"] = empty
            }
            class Hyp(val tokens: IntArray, val score: Double)
            var live = listOf(Hyp(IntArray(0), 0.0))
            val finished = ArrayList<Hyp>() // score already divided by length
            var cur = longArrayOf(startId.toLong())
            var hitLimit = true
            val steps = minOf(maxLength, n * 3 + 10)
            for (step in 0 until steps) {
                if (cancelled()) return null
                val k = live.size
                val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(cur), longArrayOf(k.toLong(), 1))
                val feed = HashMap<String, OnnxTensor>(encPast); feed.putAll(decPast)
                feed["encoder_attention_mask"] = if (step == 0) mask else maskN
                feed["encoder_hidden_states"] = if (step == 0) hidden else hiddenN
                feed["input_ids"] = tok
                feed["use_cache_branch"] = if (step == 0) noCache else useCache
                val res = try { decoder.run(feed) } finally { tok.close() }
                try {
                    val logits = (res.get(0) as OnnxTensor).floatBuffer
                    val vocab = logits.limit() / k
                    // Every live beam's best 2*nb next tokens, as (total score, beam, token), best first.
                    val cand = ArrayList<Triple<Double, Int, Int>>(k * 2 * nb)
                    for (b in 0 until k) for ((t, lp) in topLogProbs(logits, b * vocab, vocab, 2 * nb)) cand += Triple(live[b].score + lp, b, t)
                    cand.sortWith(compareByDescending<Triple<Double, Int, Int>> { it.first }.thenBy { it.second }.thenBy { it.third })
                    val next = ArrayList<Hyp>(nb); val from = ArrayList<Int>(nb)
                    for ((rank, c) in cand.withIndex()) {
                        val (sc, b, t) = c
                        if (t == eosId) { if (rank < nb) finished += Hyp(live[b].tokens, sc / norm(live[b].tokens.size + 1)) }
                        else { next += Hyp(live[b].tokens + t, sc); from += b }
                        if (next.size == nb) break
                    }
                    // Next step's cache: the decoder part follows each new beam's parent; the encoder part is copied out
                    // of step 0 once, nb times.
                    val order = from.toIntArray()
                    val newDec = HashMap<String, OnnxTensor>()
                    for (e in res) {
                        if (!e.key.startsWith("present.")) continue
                        val key = "past_key_values." + e.key.removePrefix("present.")
                        val t = e.value as OnnxTensor
                        if (".decoder." in key) newDec[key] = gatherRows(t, order)
                        else if (step == 0) encPast[key] = repeatRows(t, order.size).also(opened::add)
                    }
                    decPast.values.forEach { if (it !== empty) it.close() }
                    decPast = newDec
                    live = next
                    cur = LongArray(next.size) { next[it].tokens.last().toLong() }
                } finally { res.close() }
                if (finished.size >= nb) {
                    val bestLive = live[0].score / norm(live[0].tokens.size + 1)
                    if (nb == 1 || finished.minOf { it.score } >= bestLive) { hitLimit = false; break }
                }
            }
            if (hitLimit) live.forEach { finished += Hyp(loopCut(it.tokens), it.score / norm(it.tokens.size + 1)) }
            val best = finished.maxWith(compareBy<Hyp> { it.score }) // first of equals, as Python's max
            return tokenizer.decode(best.tokens, setOf(eosId, padId))
        } finally {
            decPast.values.forEach { runCatching { it.close() } }
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** Length normalisation of a hypothesis score: length ** [OpusMt.LENGTH_PENALTY]. */
    private fun norm(len: Int): Double = Math.pow(len.toDouble(), OpusMt.LENGTH_PENALTY)

    /** The [count] most likely tokens of one row of logits (pad never), with their log probabilities, best first. */
    private fun topLogProbs(logits: FloatBuffer, off: Int, vocab: Int, count: Int): List<Pair<Int, Double>> {
        var max = Double.NEGATIVE_INFINITY
        for (v in 0 until vocab) if (v != padId) max = maxOf(max, logits.get(off + v).toDouble())
        var sum = 0.0
        for (v in 0 until vocab) if (v != padId) sum += Math.exp(logits.get(off + v).toDouble() - max)
        val norm = max + Math.log(sum)
        val ids = IntArray(count) { -1 }; val vals = DoubleArray(count) { Double.NEGATIVE_INFINITY }
        for (v in 0 until vocab) {
            if (v == padId) continue
            val x = logits.get(off + v).toDouble()
            if (x <= vals[count - 1]) continue // ties keep the lower id, already in
            var i = count - 1
            while (i > 0 && x > vals[i - 1]) { vals[i] = vals[i - 1]; ids[i] = ids[i - 1]; i-- }
            vals[i] = x; ids[i] = v
        }
        return (0 until count).filter { ids[it] >= 0 }.map { ids[it] to vals[it] - norm }
    }

    /** A float tensor [1, ...] repeated into [times, ...]. */
    private fun repeatRows(t: OnnxTensor, times: Int): OnnxTensor = gatherRows(t, IntArray(times))

    /** Rows [order] of a float tensor [rows, ...], as a new tensor [order.size, ...]. */
    private fun gatherRows(t: OnnxTensor, order: IntArray): OnnxTensor {
        val shape = t.info.shape
        val src = t.floatBuffer
        val row = if (shape[0] == 0L) 0 else src.limit() / shape[0].toInt()
        val out = FloatBuffer.allocate(row * order.size)
        for (r in order) { val s = src.duplicate(); s.position(r * row); s.limit(r * row + row); out.put(s) }
        out.rewind()
        return OnnxTensor.createTensor(env, out, shape.copyOf().also { it[0] = order.size.toLong() })
    }

    override fun close() { runCatching { decoder.close() }; runCatching { encoder.close() } }
}
