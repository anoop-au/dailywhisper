package com.anoop.dailywhisper

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline translation: language tables, the Hy-MT model file, prompt format and text chunking.
 * Pure Kotlin (no Android imports) so tools/translate-harness tests exactly this code on the box.
 */
object TranslateConfig {
    const val BACKEND_MLKIT = "mlkit"
    const val BACKEND_HYMT = "hymt"

    const val DEFAULT_SOURCE = "en"
    const val DEFAULT_TARGET = "es"
    const val DEFAULT_BACKEND = BACKEND_MLKIT

    /**
     * One language as the app shows it. [mlkit] = ML Kit's code (null = ML Kit can't do it);
     * [hymt] = Hy-MT1.5 supports it (33 languages + 5 dialects, model card); [zh] = its Chinese name, used in
     * Hy-MT's Chinese prompt template.
     */
    data class Lang(val code: String, val name: String, val mlkit: String?, val hymt: Boolean, val zh: String? = null)

    /** Union of ML Kit (59, TranslateLanguage in translate 17.0.3) and Hy-MT1.5 (38) languages, by English name. */
    val LANGUAGES: List<Lang> = listOf(
        Lang("af", "Afrikaans", "af", false),
        Lang("sq", "Albanian", "sq", false),
        Lang("ar", "Arabic", "ar", true, "阿拉伯语"),
        Lang("be", "Belarusian", "be", false),
        Lang("bn", "Bengali", "bn", true, "孟加拉语"),
        Lang("bg", "Bulgarian", "bg", false),
        Lang("my", "Burmese", null, true, "缅甸语"),
        Lang("yue", "Cantonese", null, true, "粤语"),
        Lang("ca", "Catalan", "ca", false),
        Lang("zh", "Chinese (Simplified)", "zh", true, "中文"),
        Lang("zh-Hant", "Chinese (Traditional)", null, true, "繁体中文"),
        Lang("hr", "Croatian", "hr", false),
        Lang("cs", "Czech", "cs", true, "捷克语"),
        Lang("da", "Danish", "da", false),
        Lang("nl", "Dutch", "nl", true, "荷兰语"),
        Lang("en", "English", "en", true, "英语"),
        Lang("eo", "Esperanto", "eo", false),
        Lang("et", "Estonian", "et", false),
        Lang("tl", "Filipino (Tagalog)", "tl", true, "菲律宾语"),
        Lang("fi", "Finnish", "fi", false),
        Lang("fr", "French", "fr", true, "法语"),
        Lang("gl", "Galician", "gl", false),
        Lang("ka", "Georgian", "ka", false),
        Lang("de", "German", "de", true, "德语"),
        Lang("el", "Greek", "el", false),
        Lang("gu", "Gujarati", "gu", true, "古吉拉特语"),
        Lang("ht", "Haitian Creole", "ht", false),
        Lang("he", "Hebrew", "he", true, "希伯来语"),
        Lang("hi", "Hindi", "hi", true, "印地语"),
        Lang("hu", "Hungarian", "hu", false),
        Lang("is", "Icelandic", "is", false),
        Lang("id", "Indonesian", "id", true, "印尼语"),
        Lang("ga", "Irish", "ga", false),
        Lang("it", "Italian", "it", true, "意大利语"),
        Lang("ja", "Japanese", "ja", true, "日语"),
        Lang("kn", "Kannada", "kn", false),
        Lang("kk", "Kazakh", null, true, "哈萨克语"),
        Lang("km", "Khmer", null, true, "高棉语"),
        Lang("ko", "Korean", "ko", true, "韩语"),
        Lang("lv", "Latvian", "lv", false),
        Lang("lt", "Lithuanian", "lt", false),
        Lang("mk", "Macedonian", "mk", false),
        Lang("ms", "Malay", "ms", true, "马来语"),
        Lang("mt", "Maltese", "mt", false),
        Lang("mr", "Marathi", "mr", true, "马拉地语"),
        Lang("mn", "Mongolian", null, true, "蒙古语"),
        Lang("no", "Norwegian", "no", false),
        Lang("fa", "Persian", "fa", true, "波斯语"),
        Lang("pl", "Polish", "pl", true, "波兰语"),
        Lang("pt", "Portuguese", "pt", true, "葡萄牙语"),
        Lang("ro", "Romanian", "ro", false),
        Lang("ru", "Russian", "ru", true, "俄语"),
        Lang("sk", "Slovak", "sk", false),
        Lang("sl", "Slovenian", "sl", false),
        Lang("es", "Spanish", "es", true, "西班牙语"),
        Lang("sw", "Swahili", "sw", false),
        Lang("sv", "Swedish", "sv", false),
        Lang("ta", "Tamil", "ta", true, "泰米尔语"),
        Lang("te", "Telugu", "te", true, "泰卢固语"),
        Lang("th", "Thai", "th", true, "泰语"),
        Lang("bo", "Tibetan", null, true, "藏语"),
        Lang("tr", "Turkish", "tr", true, "土耳其语"),
        Lang("uk", "Ukrainian", "uk", true, "乌克兰语"),
        Lang("ur", "Urdu", "ur", true, "乌尔都语"),
        Lang("ug", "Uyghur", null, true, "维吾尔语"),
        Lang("vi", "Vietnamese", "vi", true, "越南语"),
        Lang("cy", "Welsh", "cy", false),
    )

    fun lang(code: String): Lang? = LANGUAGES.firstOrNull { it.code == code }
    fun languageName(code: String): String = lang(code)?.name ?: code

    fun supports(backend: String, code: String): Boolean {
        val l = lang(code) ?: return false
        return if (backend == BACKEND_HYMT) l.hymt else l.mlkit != null
    }

    /** Languages a backend can use, as (code, name) for pickers. */
    fun options(backend: String?): List<Pair<String, String>> =
        LANGUAGES.filter { backend == null || supports(backend, it.code) }.map { it.code to it.name }

    // ---- Hy-MT model file ----
    /** Hugging Face repo + pinned revision (tencent/Hy-MT1.5-1.8B-1.25bit-GGUF, same file as AngelSlim's). */
    const val MODEL_REPO = "tencent/Hy-MT1.5-1.8B-1.25bit-GGUF"
    const val MODEL_REVISION = "3d09c4ce7dc00aa7182cb348a760c7d8f6983224"
    const val MODEL_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"
    const val MODEL_URL = "https://huggingface.co/$MODEL_REPO/resolve/$MODEL_REVISION/$MODEL_FILE"
    const val MODEL_SIZE = 461_860_704L
    const val MODEL_SHA256 = "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab"
    const val CONTEXT_TOKENS = 2048

    // ---- Hy-MT prompt (HY-MT1.5 model card templates + the GGUF's chat template) ----
    private const val BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val USER = "<｜hy_User｜>"
    private const val ASSISTANT = "<｜hy_Assistant｜>"
    private val CHINESE = setOf("zh", "zh-Hant", "yue")

    /** The full prompt for one chunk. Chinese on either side uses the model's Chinese template. */
    fun hymtPrompt(text: String, source: String, target: String): String {
        val t = lang(target) ?: error("unknown target $target")
        val instruction = if (target in CHINESE || source in CHINESE)
            "将以下文本翻译为${t.zh ?: t.name}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        else
            "Translate the following segment into ${t.name}, without additional explanation.\n\n$text"
        return BOS + USER + instruction + ASSISTANT
    }

    /** Leaves room in the context for the reply: translations can run ~3x the source tokens (e.g. into Hindi). */
    const val HYMT_CHUNK_CHARS = 600
    const val MLKIT_CHUNK_CHARS = 2000

    // ---- chunking ----
    /** A piece of the note: [text] to translate (may be blank: kept as is) followed by [sep] (kept as is). */
    data class Piece(val text: String, val sep: String)

    /**
     * Splits [text] into pieces of at most [max] chars: one per line (paragraph structure is kept exactly), and a
     * long line is cut at sentence ends, else at a space. Joining every text + sep gives back the original exactly.
     */
    fun chunks(text: String, max: Int): List<Piece> {
        val out = ArrayList<Piece>()
        var last = 0
        fun line(t: String, sep: String) {
            if (t.length <= max) { out += Piece(t, sep); return }
            val parts = splitLong(t, max)
            parts.forEachIndexed { j, p -> out += if (j == parts.lastIndex) Piece(p.text, p.sep + sep) else p }
        }
        for (m in Regex("\\s*\n\\s*").findAll(text)) { line(text.substring(last, m.range.first), m.value); last = m.range.last + 1 }
        line(text.substring(last), "")
        return out.filter { it.text.isNotEmpty() || it.sep.isNotEmpty() }
    }

    private fun splitLong(s: String, max: Int): List<Piece> {
        val out = ArrayList<Piece>()
        var start = 0
        while (s.length - start > max) {
            val window = s.substring(start, start + max)
            var cut = Regex("[.!?。！？](\\s+)").findAll(window).lastOrNull()?.let { it.range.first + 1 }
            if (cut == null || cut < max / 3) cut = window.lastIndexOf(' ').takeIf { it > max / 3 }
            if (cut == null) cut = max
            val end = start + cut
            var sepEnd = end
            while (sepEnd < s.length && s[sepEnd] == ' ') sepEnd++
            out += Piece(s.substring(start, end), s.substring(end, sepEnd))
            start = sepEnd
        }
        out += Piece(s.substring(start), "")
        return out
    }

    /** Model replies sometimes wrap the text in quotes or add a trailing newline; keep the text only. */
    fun cleanReply(reply: String): String = reply.trim()
}

/**
 * The published Hy-MT 1.25-bit GGUF stores its STQ tensors as ggml type 42, the id it had before upstream llama.cpp
 * gave 42 to Q2_0. llama.cpp PR #22836 (vendored) uses 43 (STQ1_0). This rewrites only those 4-byte type ids in the
 * tensor table; tensor data is untouched. Idempotent: an already patched file reports 0.
 */
object GgufPatch {
    const val OLD_TYPE = 42
    const val NEW_TYPE = 43

    /** Returns how many tensor type ids were rewritten. Throws if the file isn't a GGUF v2/v3. */
    fun patch(file: File, dryRun: Boolean = false): Int {
        RandomAccessFile(file, if (dryRun) "r" else "rw").use { f ->
            val r = Reader(f)
            require(r.u32() == 0x46554747L) { "not a GGUF file" }
            val version = r.u32()
            require(version == 2L || version == 3L) { "GGUF version $version" }
            val nTensors = r.u64()
            val nKv = r.u64()
            repeat(nKv.toInt()) { r.skipString(); r.skipValue(r.u32().toInt()) }
            var n = 0
            repeat(nTensors.toInt()) {
                r.skipString()
                val dims = r.u32().toInt()
                r.skip(8L * dims)
                val pos = r.pos
                val type = r.u32()
                if (type == OLD_TYPE.toLong()) {
                    n++
                    if (!dryRun) r.write32(pos, NEW_TYPE)
                }
                r.skip(8) // offset
            }
            return n
        }
    }

    /** Count of tensors still using [OLD_TYPE] (0 = ready for the vendored llama.cpp). */
    fun unpatched(file: File): Int = patch(file, dryRun = true)

    private class Reader(val f: RandomAccessFile) {
        var pos = 0L
        private val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        private fun read(n: Int): ByteBuffer {
            buf.clear(); f.seek(pos); f.readFully(buf.array(), 0, n); pos += n; buf.limit(n); return buf
        }
        fun u32(): Long = read(4).int.toLong() and 0xFFFFFFFFL
        fun u64(): Long = read(8).long
        fun skip(n: Long) { pos += n }
        fun skipString() { skip(u64()) }
        fun skipValue(type: Int) {
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> skipString()
                9 -> {
                    val t = u32().toInt(); val n = u64()
                    val size = when (t) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> 0 }
                    if (size > 0) skip(n * size) else for (i in 0 until n) skipValue(t)
                }
                else -> error("bad GGUF value type $type")
            }
        }
        fun write32(at: Long, v: Int) {
            f.seek(at)
            f.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
    }
}
