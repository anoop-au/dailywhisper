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
    const val MAX_TOKENS = 900

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

    fun makePrompt(lang: String): String {
        val name = TranslationRegistry.languageName(lang)
        return "You read one person's spoken note and describe it briefly for them. Write every text value in $name. " +
            "Reply with one JSON object only (no code fences, no other text), with these keys: " +
            "\"summary\": 3 or 4 short bullet points (each under 16 words) on what the note is about; " +
            "\"mood\": one or two words for its overall tone (like Hopeful, Calm, Frustrated); " +
            "\"tone\": a number from 0 (very low) through 50 (steady) to 100 (very upbeat); " +
            "\"tone_line\": a few words on how the tone moves (like anxious, then settled); " +
            "\"scale\": three words for low, steady and upbeat; " +
            "\"shifts\": 2 or 3 moments the mood changes, each {\"dir\": \"up\" or \"down\" or \"flat\", \"label\": one word, " +
            "\"text\": what happened, under 10 words}. Use only what is in the note. No advice."
    }

    fun translatePrompt(lang: String): String {
        val name = TranslationRegistry.languageName(lang)
        return "Translate every text value in this JSON into $name. Keep the keys, the numbers and the \"dir\" values " +
            "exactly as they are. Reply with the JSON object only."
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

    private fun clean(s: String?, max: Int): String = s.orEmpty().trim().trim('•', '-', '*', ' ').take(max).trim()

    private fun item(o: JSONObject, lang: String): Item? {
        val summary = o.optJSONArray("summary")?.let { a -> (0 until a.length()).map { clean(a.optString(it), 200) } }
            ?.filter { it.isNotEmpty() }?.take(5).orEmpty()
        val mood = clean(o.optString("mood"), 32)
        if (summary.isEmpty() || mood.isEmpty()) return null
        val tone = (o.opt("tone")?.toString()?.trim()?.toDoubleOrNull() ?: 50.0).toInt().coerceIn(0, 100)
        val scale = o.optJSONArray("scale")?.let { a -> (0 until a.length()).map { clean(a.optString(it), 16) } }
            ?.takeIf { it.size == 3 && it.all(String::isNotEmpty) }.orEmpty()
        val shifts = o.optJSONArray("shifts")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val dir = s.optString("dir").lowercase().takeIf { it in setOf(UP, DOWN, FLAT) } ?: FLAT
                val label = clean(s.optString("label"), 16); val text = clean(s.optString("text"), 90)
                if (label.isEmpty() || text.isEmpty()) null else Shift(dir, label, text)
            }
        }?.take(3).orEmpty()
        val item = Item(lang, summary, mood, tone, clean(o.optString("tone_line"), 60), scale, shifts)
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
