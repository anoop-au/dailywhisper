package com.anoop.dailywhisper

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Long notes' Insights: a short summary and the mood (overall label, a 0-100 tone, 2-3 mood shifts). Made online by a
 * chat model (InsightsMaker), kept with the note in SQLite as one JSON per note with an entry per language, and the
 * hash of the text they were made from (an edit makes them stale; they're remade on the next open / stop / save).
 * Pure apart from org.json (tests).
 */
object Insights {
    const val UP = "up"
    const val DOWN = "down"
    const val FLAT = "flat"
    /** Longest note text sent (characters); the start of a very long session is enough for a summary. */
    const val MAX_INPUT = 12_000
    const val MAX_TOKENS = 2048 // a whole reply in Hindi / Malayalam / Chinese, pretty-printed, with room to spare

    data class Shift(val dir: String, val label: String, val text: String)

    data class Item(
        val lang: String, val summary: List<String>, val mood: String, val tone: Int, val toneLine: String,
        val scale: List<String>, val shifts: List<Shift>,
    ) {
        fun toJson(): JSONObject = JSONObject().put("summary", JSONArray(summary)).put("mood", mood).put("tone", tone)
            .put("tone_line", toneLine).put("scale", JSONArray(scale))
            .put("shifts", JSONArray(shifts.map { JSONObject().put("dir", it.dir).put("label", it.label).put("text", it.text) }))
    }

    /** Everything saved for one note: entries by language, all made from the text with [hash]. */
    data class Saved(val hash: String, val items: Map<String, Item>) {
        fun fresh(text: String): Boolean = hash == hash(text)
        operator fun get(lang: String): Item? = items[lang]
        fun with(item: Item, text: String): Saved =
            if (fresh(text)) Saved(hash, items + (item.lang to item)) else Saved(hash(text), mapOf(item.lang to item))
        fun toJson(): String = JSONObject().put("v", 1).put("hash", hash)
            .put("items", JSONObject().apply { items.forEach { (k, v) -> put(k, v.toJson()) } }).toString()
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun load(json: String?): Saved? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val items = o.optJSONObject("items") ?: return null
        val map = LinkedHashMap<String, Item>()
        for (k in items.keys()) item(items.optJSONObject(k) ?: continue, k)?.let { map[k] = it }
        return Saved(o.optString("hash"), map)
    }

    /** Words, counting Chinese / Japanese characters two to a word (no spaces there). */
    fun words(text: String): Int = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.sumOf { w ->
        val han = w.count { LiveTranslation.Script.isHan(it) }
        if (han == 0) 1 else maxOf(1, han / 2 + if (han < w.length) 1 else 0)
    }

    /** Worth summarising: a Long note with at least [minWords] words (never empty or tiny ones). */
    fun eligible(kind: String, text: String, minWords: Int): Boolean =
        kind == NoteSchema.LONG && text.isNotBlank() && words(text) >= maxOf(minWords, 5)

    /** What to do now for a note whose insights should be in [lang]. */
    enum class Need { SHOW, MAKE, TRANSLATE }

    fun need(saved: Saved?, text: String, lang: String): Need = when {
        saved == null || !saved.fresh(text) || saved.items.isEmpty() -> Need.MAKE
        saved[lang] != null -> Need.SHOW
        else -> Need.TRANSLATE // made in another language from this same text: translating it is cheaper than remaking it
    }

    // ---- prompts ----

    /** The reply's shape, shown to the model as-is: English keys, values in the language asked for. */
    private const val EXAMPLE = "{\"summary\": [\"first point\", \"second point\", \"third point\"], \"mood\": \"Hopeful\", " +
        "\"tone\": 65, \"tone_line\": \"anxious, then settled\", \"scale\": [\"Low\", \"Steady\", \"Upbeat\"], " +
        "\"shifts\": [{\"dir\": \"up\", \"label\": \"Relief\", \"text\": \"what happened\"}]}"

    private fun keepKeys(name: String) = "Keep these English key names exactly as written: \"summary\", \"mood\", \"tone\", " +
        "\"tone_line\", \"scale\", \"shifts\", \"dir\", \"label\", \"text\" (never translate them), and keep \"dir\" as " +
        "\"up\", \"down\" or \"flat\". Only the string values are in $name."

    fun makePrompt(lang: String): String {
        val name = TranslationRegistry.languageName(lang)
        return "You read one person's spoken note and describe it briefly for them, in $name. " +
            "Reply with one JSON object only (no code fences, no other text), shaped like this example: $EXAMPLE. " +
            "\"summary\": an array of 3 or 4 short bullet points (each under 16 words) on what the note is about; " +
            "\"mood\": one or two words for its overall tone (like Hopeful, Calm, Frustrated, Wry); " +
            "\"tone\": a number from 0 (very low) through 50 (steady) to 100 (very upbeat); " +
            "\"tone_line\": a few words on how the tone moves; " +
            "\"scale\": three words for low, steady and upbeat; " +
            "\"shifts\": 2 or 3 moments the mood changes, each with \"dir\", \"label\" (one word) and \"text\" " +
            "(what happened, under 10 words). ${keepKeys(name)} Use only what is in the note. No advice."
    }

    fun translatePrompt(lang: String): String {
        val name = TranslationRegistry.languageName(lang)
        return "Translate every string value in this JSON into $name. ${keepKeys(name)} Keep the numbers as they are. " +
            "Reply with the JSON object only."
    }

    fun input(text: String): String = text.trim().take(MAX_INPUT)

    // ---- replies ----

    /** The model's reply as an [Item] in [lang], or null when it isn't usable (not JSON, empty, wrong script). */
    fun parse(reply: String?, lang: String): Item? {
        val raw = reply?.trim().orEmpty()
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        return item(o, lang)
    }

    private fun clean(s: String?, max: Int): String =
        s.orEmpty().trim().replace(Regex("^(?:[•\\-*·]+\\s*|\\d{1,2}[.)]\\s+)"), "").trim().take(max).trim()

    /** Keys as models actually send them: any case, with or without "_" ("Tone_Line", "toneLine", "tone line"). */
    private fun norm(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    private fun JSONObject.find(vararg names: String): Any? {
        val want = names.map(::norm)
        for (n in want) for (k in keys()) if (norm(k) == n) opt(k)?.takeIf { it != JSONObject.NULL }?.let { return it }
        return null
    }

    private fun JSONObject.text(vararg names: String): String = when (val v = find(*names)) {
        null -> ""
        is String -> v
        is JSONObject -> v.text("label", "mood", "value", "name", "text")
        is Number, is Boolean -> v.toString()
        else -> ""
    }

    /** A list from an array, or from one string (one point per line / bullet; a paragraph is one point). */
    private fun list(v: Any?, max: Int): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).map { i ->
            when (val x = v.opt(i)) { is String -> x; is JSONObject -> x.text("text", "point", "summary"); null -> ""; else -> x.toString() }
        }
        is String -> v.split(Regex("\\n+|(?<=\\S)\\s+[•·]\\s+"))
        else -> emptyList()
    }.map { clean(it, max) }.filter { it.isNotEmpty() }

    private fun item(top: JSONObject, lang: String): Item? {
        // {"insights": {...}} and the like: the one object inside.
        val o = if (top.find("summary", "summary_points", "key_points", "points", "bullets") == null)
            top.keys().asSequence().mapNotNull { top.optJSONObject(it) }.singleOrNull() ?: top else top
        val summary = list(o.find("summary", "summary_points", "key_points", "points", "bullets"), 200).take(5)
        val mood = clean(o.text("mood", "sentiment", "overall_mood", "overall_sentiment", "feeling"), 32)
        if (summary.isEmpty() || mood.isEmpty()) return null
        val tone = (o.find("tone", "tone_score", "score")?.toString()?.trim()?.toDoubleOrNull() ?: 50.0).toInt().coerceIn(0, 100)
        val scale = list(o.find("scale"), 16).takeIf { it.size == 3 }.orEmpty()
        val shifts = (o.find("shifts", "mood_shifts", "moments") as? JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val dir = s.text("dir", "direction").trim().lowercase().takeIf { it in setOf(UP, DOWN, FLAT) } ?: FLAT
                val label = clean(s.text("label", "name"), 16); val text = clean(s.text("text", "what", "moment"), 90)
                if (label.isEmpty() || text.isEmpty()) null else Shift(dir, label, text)
            }
        }?.take(3).orEmpty()
        val item = Item(lang, summary, mood, tone, clean(o.text("tone_line"), 60), scale, shifts)
        // In the right script (e.g. Chinese asked for, Chinese came back), where that can be told.
        val script = LiveTranslation.expectedScript(lang)
        if (script != null && LiveTranslation.Script.detect(summary.joinToString(" ") + " " + mood, 0.5) != script) return null
        return item
    }

    /** A weather word for the tone, for the mood tile. */
    fun emoji(tone: Int): String = when {
        tone < 25 -> "🌧️"; tone < 45 -> "☁️"; tone < 60 -> "⛅"; tone < 80 -> "🌤️"; else -> "☀️"
    }
}
