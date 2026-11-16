package com.anoop.dailywhisper

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Private Weekly Review (Daily Whisper Plus, v0.1.27): one week's notes turned into a short reflection, made on this
 * phone from simple counts. No model, no library, nothing sent:
 *  - Key themes: the words that come up in the most notes (common words left out; Chinese/Japanese by character pairs).
 *  - Mood this week: each note's tone (a Long note's saved Insights; otherwise a small word list), averaged per day,
 *    and how it moved from the first half of the week to the second.
 *  - Unfinished thoughts: sentences that sound open ("need to", "should", "not sure", a question…), newest first.
 *  - 2 to 4 gentle prompts built from the above.
 * Online (only with Settings → Privacy → Weekly review online, off by default) a chat model may write the words
 * instead ([cloudPrompt], [parseCloud]); the mood by day always comes from the phone. Pure apart from org.json (tests).
 */
object WeeklyReview {
    const val ON_PHONE = "phone"
    const val ONLINE = "online"
    /** Most note text sent for an online review (characters, newest notes kept first). */
    const val MAX_INPUT = 16_000
    const val MAX_TOKENS = 1500

    data class Options(val minNotes: Int = 3, val themes: Int = 5, val unfinished: Int = 4, val prompts: Int = 3)

    /** One day of the week: its average tone 0-100 (null: nothing to go on) and how many notes. */
    data class Day(val date: LocalDate, val tone: Int?, val notes: Int)

    data class Review(
        val start: LocalDate, val end: LocalDate, val notes: Int, val words: Int,
        val themes: List<String>, val moodLine: String, val moods: List<String>, val days: List<Day>,
        val unfinished: List<String>, val prompts: List<String>, val source: String, val created: Long,
    ) {
        fun toJson(): String = JSONObject().put("v", 1).put("start", start.toString()).put("end", end.toString())
            .put("notes", notes).put("words", words).put("themes", JSONArray(themes)).put("mood_line", moodLine)
            .put("moods", JSONArray(moods))
            .put("days", JSONArray(days.map { d -> JSONObject().put("date", d.date.toString()).put("notes", d.notes).apply { d.tone?.let { put("tone", it) } } }))
            .put("unfinished", JSONArray(unfinished)).put("prompts", JSONArray(prompts)).put("source", source)
            .put("created", created).toString()
    }

    fun load(json: String?): Review? = runCatching {
        val o = JSONObject(json ?: return null)
        val days = o.getJSONArray("days").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) } }.map {
            Day(LocalDate.parse(it.getString("date")), if (it.has("tone")) it.getInt("tone") else null, it.optInt("notes"))
        }
        Review(LocalDate.parse(o.getString("start")), LocalDate.parse(o.getString("end")), o.optInt("notes"), o.optInt("words"),
            strings(o.optJSONArray("themes")), o.optString("mood_line"), strings(o.optJSONArray("moods")), days,
            strings(o.optJSONArray("unfinished")), strings(o.optJSONArray("prompts")), o.optString("source", ON_PHONE), o.optLong("created"))
    }.getOrNull()

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    // ---- the week ----

    /** The seven days ending on the latest [reviewDay] (1 = Monday … 7 = Sunday) that is today or before. */
    fun week(today: LocalDate, reviewDay: Int): Pair<LocalDate, LocalDate> {
        val end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(reviewDay.coerceIn(1, 7))))
        return end.minusDays(6) to end
    }

    fun inWeek(notes: List<Note>, start: LocalDate, end: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Note> =
        notes.filter { n ->
            val d = java.time.Instant.ofEpochMilli(n.created).atZone(zone).toLocalDate()
            !d.isBefore(start) && !d.isAfter(end) && n.text.isNotBlank()
        }.sortedBy { it.created }

    // ---- key themes ----

    private val STOP = ("a about above after again against all also am an and any are aren't as at be because been before being " +
        "below between both but by can can't cannot could couldn't did didn't do does doesn't doing don't down during each " +
        "few for from further get got had hadn't has hasn't have haven't having he he'd he'll he's her here here's hers herself " +
        "him himself his how how's i i'd i'll i'm i've if in into is isn't it it's its itself just let's like me more most " +
        "mustn't my myself no nor not now of off on once only or other ought our ours ourselves out over own really same " +
        "shan't she she'd she'll she's should shouldn't so some such than that that's the their theirs them themselves then " +
        "there there's these they they'd they'll they're they've thing things think this those through to today too " +
        "tomorrow under until up very was wasn't we we'd we'll we're we've well were weren't what what's when when's where " +
        "where's which while who who's whom why why's will with won't would wouldn't yeah yes yesterday you you'd you'll " +
        "you're you've your yours yourself yourselves okay ok um uh actually maybe going gonna want wanted know need lot " +
        "bit still even much many one two three back way day time make made said say says see something anything nothing " +
        "everything someone people week").split(" ").toSet()

    /** Han runs as character pairs; 的/了/是… pairs are rarely themes, so pairs with them are left out. */
    private val HAN_STOP = "的了是我你他她它们在有和就不也都很这那个要会说去到着把被吗呢吧啊".toSet()

    fun terms(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lowercase().split(Regex("[^\\p{L}\\p{M}'’]+"))) {
            val w = raw.trim('\'', '’').replace('’', '\'')
            if (w.isEmpty()) continue
            if (w.any { LiveTranslation.Script.isHan(it) }) {
                val han = w.filter { LiveTranslation.Script.isHan(it) }
                for (i in 0 until han.length - 1) {
                    val pair = han.substring(i, i + 2)
                    if (pair.none { it in HAN_STOP }) out += pair
                }
            } else if (w.length >= 4 && w !in STOP && !w.endsWith("'s")) out += w
        }
        return out
    }

    /** Words in the most notes (ties: the most often overall), at most [max]; a word must come up in two notes. */
    fun themes(texts: List<String>, max: Int): List<String> {
        val inNotes = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        val firstSeen = LinkedHashMap<String, Int>()
        texts.forEachIndexed { i, t ->
            for (w in terms(t)) { inNotes.merge(w, 1, Int::plus); firstSeen.putIfAbsent(w, i) }
            for (raw in t.lowercase().split(Regex("[^\\p{L}\\p{M}']+"))) if (raw in inNotes) total.merge(raw, 1, Int::plus)
        }
        val need = if (texts.size >= 2) 2 else 1
        return inNotes.filter { it.value >= need }.keys
            .sortedWith(compareByDescending<String> { inNotes[it] }.thenByDescending { total[it] ?: 0 }.thenBy { firstSeen[it] })
            .let { merged(it) }.take(max).map { w -> if (w.first().isLetter() && !LiveTranslation.Script.isHan(w.first())) w.replaceFirstChar { it.uppercase() } else w }
    }

    /** "plan" and "plans" (or "planning") are one theme: keep the first (most frequent) of a shared stem. */
    private fun merged(words: List<String>): List<String> {
        val seen = HashSet<String>()
        return words.filter { w -> seen.add(stem(w)) }
    }

    private fun stem(w: String): String = w.removeSuffix("ing").removeSuffix("ed").removeSuffix("es").removeSuffix("s").take(6)

    // ---- mood ----

    private val UP = ("good great happy glad calm relieved relief grateful thankful excited love loved lovely hope hopeful " +
        "proud fun enjoyed enjoy better best peaceful rested energised energized laughed smile smiled wonderful nice " +
        "progress finally win won easy beautiful 开心 高兴 好 喜欢 放松 感谢 希望").split(" ").toSet()
    private val DOWN = ("sad tired tiring stressed stressful stress drained anxious anxiety worried worry angry upset frustrated frustrating lonely " +
        "exhausted overwhelmed afraid scared hurt sick bad worse worst hard difficult annoyed cried crying bored lost " +
        "stuck failed fail pain 累 难过 生气 担心 焦虑 压力 烦 不好").split(" ").toSet()

    /** A tone 0-100 for [text] from the word lists, or null when none of their words is there. */
    fun lexiconTone(text: String): Int? {
        val lower = text.lowercase()
        val words = lower.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        var up = words.count { it in UP }; var down = words.count { it in DOWN }
        // Han words have no spaces: look for them inside the text.
        up += UP.filter { w -> w.any { LiveTranslation.Script.isHan(it) } }.sumOf { Regex(Regex.escape(it)).findAll(lower).count() }
        down += DOWN.filter { w -> w.any { LiveTranslation.Script.isHan(it) } }.sumOf { Regex(Regex.escape(it)).findAll(lower).count() }
        if (up + down == 0) return null
        return (50 + 40.0 * (up - down) / (up + down)).toInt().coerceIn(10, 90)
    }

    /** A note's tone: its Insights (made online when it was summarised, any language), else [lexiconTone]. */
    fun tone(n: Note): Int? = Insights.load(n.insights)?.takeIf { it.fresh(n.text) }?.items?.values?.firstOrNull()?.tone
        ?: lexiconTone(n.text)

    fun moodWords(notes: List<Note>): List<String> = notes.mapNotNull { n ->
        Insights.load(n.insights)?.takeIf { it.fresh(n.text) }?.items?.values?.firstOrNull()?.mood?.trim()?.takeIf { it.isNotEmpty() }
    }.groupingBy { it.replaceFirstChar { c -> c.uppercase() } }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(3)

    fun days(notes: List<Note>, start: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Day> = (0..6).map { i ->
        val d = start.plusDays(i.toLong())
        val those = notes.filter { java.time.Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate() == d }
        val tones = those.mapNotNull { tone(it) }
        Day(d, if (tones.isEmpty()) null else tones.average().toInt(), those.size)
    }

    enum class Trend { LIFTED, DIPPED, STEADY, UPBEAT, LOW, UNKNOWN }

    fun trend(days: List<Day>): Trend {
        val known = days.filter { it.tone != null }
        if (known.isEmpty()) return Trend.UNKNOWN
        val avg = known.map { it.tone!! }.average()
        if (known.size >= 2) {
            val half = known.size / 2
            val first = known.take(half).map { it.tone!! }.average()
            val second = known.drop(half).map { it.tone!! }.average()
            if (second - first >= 12) return Trend.LIFTED
            if (first - second >= 12) return Trend.DIPPED
        }
        return when { avg >= 64 -> Trend.UPBEAT; avg <= 36 -> Trend.LOW; else -> Trend.STEADY }
    }

    fun moodLine(t: Trend): String = when (t) {
        Trend.LIFTED -> "Your mood lifted as the week went on."
        Trend.DIPPED -> "Things felt heavier later in the week."
        Trend.UPBEAT -> "A mostly bright week."
        Trend.LOW -> "A heavier week than most."
        Trend.STEADY -> "A fairly steady week."
        Trend.UNKNOWN -> "Not enough to read the mood this week."
    }

    // ---- unfinished thoughts ----

    private val OPEN = Regex(
        "\\b(need to|needs to|have to|has to|got to|gotta|should|must|remember to|don't forget|do not forget|not sure|" +
            "i wonder|wondering|figure out|haven't|still need|to-do|todo|someday|one day i|at some point|follow up|" +
            "get back to|look into|decide|maybe i)\\b|[?？]\\s*$|还没|要记得|得去|应该|不确定|想想",
        RegexOption.IGNORE_CASE)

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?。！？])\\s*|\\n+")).map { it.trim() }.filter { it.length >= 8 }

    /** Open-sounding sentences, newest note first, each once, at most [max] (long ones shortened). */
    fun unfinished(notes: List<Note>, max: Int): List<String> {
        val out = LinkedHashMap<String, String>()
        for (n in notes.sortedByDescending { it.created }) for (s in sentences(n.text)) {
            if (!OPEN.containsMatchIn(s)) continue
            val key = s.lowercase().filter { it.isLetterOrDigit() }
            if (key.isNotEmpty() && key !in out) out[key] = if (s.length > 140) s.take(137).trimEnd() + "…" else s
            if (out.size >= max) return out.values.toList()
        }
        return out.values.toList()
    }

    // ---- gentle prompts ----

    fun prompts(themes: List<String>, trend: Trend, unfinished: List<String>, n: Int): List<String> {
        val all = mutableListOf<String>()
        themes.firstOrNull()?.let { all += "“$it” came up most this week. How do you feel about it now?" }
        if (unfinished.isNotEmpty()) all += "Of the thoughts you left open, which one matters most for next week?"
        all += when (trend) {
            Trend.LIFTED -> "Your mood lifted as the week went on. What helped?"
            Trend.DIPPED -> "The week got heavier towards the end. What would make next week a little gentler?"
            Trend.LOW -> "This week asked a lot of you. What is one kind thing you could do for yourself?"
            Trend.UPBEAT -> "What made this a good week, and how could you keep a little of it?"
            Trend.STEADY, Trend.UNKNOWN -> "What gave you steady ground this week?"
        }
        themes.getOrNull(1)?.let { all += "What is one small step you could take on “$it”?" }
        all += "What is one moment from this week you'd like to remember?"
        all += "What would you like to leave behind with this week?"
        return all.distinct().take(n.coerceIn(2, 4))
    }

    // ---- the review ----

    /** The on-phone review of [weekNotes] (already the week's), or null when there are fewer than [Options.minNotes]. */
    fun build(weekNotes: List<Note>, start: LocalDate, end: LocalDate, o: Options, now: Long,
              zone: ZoneId = ZoneId.systemDefault()): Review? {
        if (weekNotes.size < maxOf(1, o.minNotes)) return null
        val themes = themes(weekNotes.map { it.text }, o.themes)
        val days = days(weekNotes, start, zone)
        val trend = trend(days)
        val open = unfinished(weekNotes, o.unfinished)
        return Review(start, end, weekNotes.size, weekNotes.sumOf { Insights.words(it.text) }, themes, moodLine(trend),
            moodWords(weekNotes), days, open, prompts(themes, trend, open, o.prompts), ON_PHONE, now)
    }

    // ---- online (opt-in) ----

    fun cloudPrompt(o: Options): String =
        "You read one person's private notes from one week and write them a short, warm weekly reflection in the language " +
            "the notes are mostly in. Reply with one JSON object only (no code fences, no other text), shaped like " +
            "{\"themes\": [\"...\"], \"mood\": \"one sentence on how the mood moved\", \"unfinished\": [\"...\"], " +
            "\"prompts\": [\"...\"]}. \"themes\": up to ${o.themes} key themes, two or three words each; \"unfinished\": up " +
            "to ${o.unfinished} thoughts they left open, in their words, under 20 words each; \"prompts\": exactly " +
            "${o.prompts.coerceIn(2, 4)} gentle, open questions for the week ahead. Keep the English key names. Use only " +
            "what is in the notes. No advice, no diagnosis."

    /** The week's notes for the model, oldest first, each with its day; the newest are kept when it's too long. */
    fun cloudInput(weekNotes: List<Note>, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = weekNotes.sortedBy { it.created }.map { n ->
            val d = java.time.Instant.ofEpochMilli(n.created).atZone(zone).toLocalDate()
            "[${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.dayOfMonth}] ${n.text.trim()}"
        }
        val kept = ArrayDeque<String>(); var size = 0
        for (p in parts.asReversed()) { if (size + p.length > MAX_INPUT && kept.isNotEmpty()) break; kept.addFirst(p.take(MAX_INPUT)); size += p.length + 2 }
        return kept.joinToString("\n\n")
    }

    /** The model's words over [base] (which keeps its numbers and mood by day), or null when the reply isn't usable. */
    fun parseCloud(reply: String?, base: Review, o: Options, now: Long): Review? {
        val raw = reply?.trim().orEmpty()
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val j = runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrNull() ?: return null
        val themes = strings(j.optJSONArray("themes")).take(o.themes)
        val prompts = strings(j.optJSONArray("prompts")).take(o.prompts.coerceIn(2, 4))
        if (themes.isEmpty() || prompts.size < 2) return null
        return base.copy(themes = themes, moodLine = j.optString("mood").trim().ifEmpty { base.moodLine },
            unfinished = strings(j.optJSONArray("unfinished")).take(o.unfinished), prompts = prompts, source = ONLINE, created = now)
    }
}
