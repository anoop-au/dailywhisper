package com.anoop.dailywhisper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v0.1.21: one Translation switch + "Translate into" (the v8 settings upgrade, the language a note is in), and Long
 * notes' Insights (the language they're shown in, the model's reply, what's saved with the note, the DB upgrade, the card).
 */
@RunWith(RobolectricTestRunner::class)
class InsightsAndTranslationTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
    }

    @After fun reset() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        InsightsMaker.testProviders = null
    }

    private fun doneSetup() = ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()

    // ---- the v8 settings upgrade (pure) ----

    private fun merge(saved: Map<String, Any>, upgrade: Boolean = true) = SettingsMigration.toOneTranslation(saved, upgrade)

    @Test fun mergeTakesLiveFirstAndSwitchFollowsLive() {
        assertEquals("live on: its language, on", mapOf("translate_into" to "zh", "translation_on" to true),
            merge(mapOf("live_tr_into" to "zh", "tr_target" to "es")).put)
        assertEquals("live Off: the notes language, off", mapOf("translate_into" to "de", "translation_on" to false),
            merge(mapOf("live_tr_into" to "off", "tr_target" to "de")).put)
        assertEquals("live never set (its default was Off): off", mapOf("translate_into" to "ml", "translation_on" to false),
            merge(mapOf("tr_target" to "ml")).put)
        assertEquals("nothing saved on an upgrade: off, default language", mapOf("translation_on" to false), merge(emptyMap()).put)
        assertTrue("a fresh install keeps the defaults (on)", merge(emptyMap(), upgrade = false).put.isEmpty())
        assertEquals("a new value already saved wins", emptyMap<String, Any>(),
            merge(mapOf("live_tr_into" to "zh", "translate_into" to "hi", "translation_on" to false)).put)
        assertTrue("old keys are kept (downgrade)", merge(mapOf("live_tr_into" to "zh", "tr_target" to "es")).remove.isEmpty())
        assertEquals(mapOf("tr_target" to "zh", "live_tr_into" to "zh"), SettingsMigration.oldKeys(true, "zh"))
        assertEquals(mapOf("tr_target" to "zh", "live_tr_into" to "off"), SettingsMigration.oldKeys(false, "zh"))
    }

    // ---- the upgrade as the app runs it ----

    private fun upgradeFrom(version: Int, vararg saved: Pair<String, Any>): android.content.SharedPreferences {
        ctx.uiState().edit().putInt("settings_version", version).putBoolean(SetupActivity.KEY_DONE, true).commit()
        val e = ctx.getSharedPreferences("settings", 0).edit()
        for ((k, v) in saved) when (v) { is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v) }
        e.commit()
        return ctx.settings()
    }

    @Test fun upgradeFromV020ChineseLive() {
        val p = upgradeFrom(7, "live_tr_into" to "zh", "tr_target" to "es", "tr_source" to "en", "dg_language" to "en")
        assertTrue(ctx.translationOn()); assertEquals("zh", ctx.translateInto()); assertEquals("zh", ctx.liveTranslationInto())
        assertEquals("old keys stay for a downgrade", "zh", p.getString("live_tr_into", null))
        assertEquals("es", p.getString("tr_target", null)); assertEquals("en", p.getString("tr_source", null))
    }

    @Test fun upgradeKeepsMalayalamAndAutoDictation() {
        // The earlier bug: a version bump must never reset "I dictate in" to English.
        upgradeFrom(7, "dg_language" to "ml", "tr_target" to "en")
        assertEquals("ml", ctx.settings()[Prefs.DG_LANGUAGE])
        assertFalse("live was Off: Translation off", ctx.translationOn()); assertEquals("en", ctx.translateInto())
        assertEquals(LiveTranslation.OFF, ctx.liveTranslationInto())
        setUp()
        upgradeFrom(7, "dg_language" to "auto-sarvam", "live_tr_into" to "ml")
        assertEquals("auto-sarvam", ctx.settings()[Prefs.DG_LANGUAGE])
        assertTrue(ctx.translationOn()); assertEquals("ml", ctx.translateInto())
    }

    @Test fun olderInstallRunsEveryStepInOrder() {
        // v0.1.19: the old live switch on, into Malayalam: v6 makes "Live translation into", v8 makes it the one language.
        upgradeFrom(5, "live_tr" to true, "live_tr_target" to "ml", "tr_target" to "zh")
        assertTrue(ctx.translationOn()); assertEquals("ml", ctx.translateInto())
    }

    @Test fun freshInstallIsOn() {
        assertTrue(ctx.translationOn())
        assertFalse("nothing written for it", ctx.settings().contains(Prefs.TRANSLATION.key))
        assertEquals("v0.1.25: fresh installs translate into Chinese", SettingsMigration.FRESH_TRANSLATE_INTO, ctx.translateInto())
        assertTrue(ctx.settings()[Prefs.INSIGHTS]); assertEquals(50, ctx.settings()[Prefs.INSIGHTS_MIN_WORDS])
    }

    // ---- the language a note is in ----

    @Test fun fromLanguage() {
        val L = TranslationLanguages
        assertEquals("Malayalam script", "ml", L.from("ഞാൻ ഇന്ന് ചന്തയിൽ പോയി", "en", "en"))
        assertEquals("Chinese script", "zh", L.from("我今天去了市场", "en", "en"))
        assertEquals("Latin: the dictation language", "es", L.from("Fui al mercado hoy", "es", "en"))
        assertEquals("Latin, dictating en-AU", "en", L.from("I went", "en-AU", "ml"))
        assertEquals("Mixed: the saved one", "de", L.from("Ich ging", "multi", "de"))
        assertEquals("Auto (Indian): the saved one", "hi", L.from("main gaya", "auto-sarvam", "hi"))
        assertEquals("nothing known: English", "en", L.from("hello", "multi", null))
        assertEquals("a dictation code no translator knows: the saved one", "fr", L.from("bonjour", "xx", "fr"))
        assertEquals("no dictation choice yet: the saved one", "ja", L.home(null, "ja"))
        assertEquals("中文", L.nativeName("zh")); assertEquals("മലയാളം", L.nativeName("ml"))
        // The app: "I dictate in" counts only once chosen.
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "de").commit()
        assertEquals("de", ctx.homeLanguage())
        ctx.settings().edit().putString(Prefs.DG_LANGUAGE.key, "ml").commit()
        assertEquals("ml", ctx.homeLanguage()); assertEquals("zh", ctx.noteLanguage("我去了"))
    }

    @Test fun insightsLanguage() {
        val L = TranslationLanguages
        assertEquals("on: the translation language", "zh", L.insightsLanguage(true, "zh", "en"))
        assertEquals("off: the note's", "en", L.insightsLanguage(false, "zh", "en"))
        assertEquals("on, same language: the note's", "en", L.insightsLanguage(true, "en", "en"))
        assertEquals("a Chinese note, into English", "en", L.insightsLanguage(true, "en", "zh"))
    }

    // ---- the model's reply ----

    private val reply = """Here you go:
        ```json
        {"summary":["Market with Mum: mangoes back.","Launch anxiety all week.","It clicked: a simple switch.","Tomorrow: settings, mockup, Arjun."],
         "mood":"Hopeful","tone":"72","tone_line":"anxious, then settled","scale":["Low","Steady","Upbeat"],
         "shifts":[{"dir":"up","label":"Warm","text":"Market with Mum"},{"dir":"DOWN","label":"Anxious","text":"Worried about bugs"},
                   {"dir":"sideways","label":"Relief","text":"It clicked"},{"dir":"up","label":"Extra","text":"one too many"}]}
        ```"""

    @Test fun parsesAndChecksTheReply() {
        val i = Insights.parse(reply, "en")!!
        assertEquals(4, i.summary.size); assertEquals("Hopeful", i.mood); assertEquals(72, i.tone)
        assertEquals("at most 3 shifts", 3, i.shifts.size)
        assertEquals(listOf(Insights.UP, Insights.DOWN, Insights.FLAT), i.shifts.map { it.dir })
        assertEquals(100, Insights.parse("""{"summary":["a"],"mood":"Joy","tone":250}""", "en")!!.tone)
        assertNull("asked for Chinese, English came back", Insights.parse(reply, "zh"))
        assertNotNull(Insights.parse("""{"summary":["和妈妈逛集市","整周焦虑"],"mood":"充满希望","tone":70}""", "zh"))
        assertNull("no summary", Insights.parse("""{"summary":[],"mood":"Calm"}""", "en"))
        assertNull("no mood", Insights.parse("""{"summary":["x"]}""", "en"))
        assertNull(Insights.parse("I can't help with that", "en")); assertNull(Insights.parse(null, "en"))
        assertTrue(Insights.makePrompt("zh").contains("Chinese (Simplified)") && Insights.makePrompt("zh").contains("JSON"))
        assertEquals(Insights.MAX_INPUT, Insights.input("a".repeat(20_000)).length)
    }

    // ---- real model replies (v0.1.23): the shapes Gemini / GPT send back, not just the one asked for ----

    @Test fun summaryAsAStringIsAccepted() {
        val lines = Insights.parse("""{"summary":"• Putting things off at 97%.\n• Planning 100% tomorrow.\n- Bored of chat apps.","mood":"Wry","tone":45}""", "en")!!
        assertEquals(listOf("Putting things off at 97%.", "Planning 100% tomorrow.", "Bored of chat apps."), lines.summary)
        val numbered = Insights.parse("""{"summary":"1. One thing.\n2) Another thing.","mood":"Calm"}""", "en")!!
        assertEquals(listOf("One thing.", "Another thing."), numbered.summary)
        val para = Insights.parse("""{"summary":"A wry note about putting things off until tomorrow.","mood":"Wry"}""", "en")!!
        assertEquals("a paragraph is one point", 1, para.summary.size)
    }

    @Test fun capitalisedAndAliasedKeysAreAccepted() {
        val i = Insights.parse("""{"Summary":["Putting things off."],"Mood":"Wry","Tone":40,"Tone_Line":"flat, then hopeful",
            "Scale":["Low","Steady","Upbeat"],"Shifts":[{"Dir":"UP","Label":"Hope","Text":"Planning tomorrow"}]}""", "en")!!
        assertEquals("Wry", i.mood); assertEquals(40, i.tone); assertEquals("flat, then hopeful", i.toneLine)
        assertEquals(Insights.UP, i.shifts.single().dir); assertEquals(3, i.scale.size)
        assertEquals("SUMMARY / sentiment", "Wry", Insights.parse("""{"SUMMARY":["x"],"sentiment":"Wry"}""", "en")!!.mood)
        assertEquals("toneLine / overall_mood", "Calm", Insights.parse("""{"summary":["x"],"overall_mood":"Calm","toneLine":"steady"}""", "en")!!.mood)
        assertEquals("wrapped in one object", "Wry", Insights.parse("""{"insights":{"summary":["x"],"mood":"Wry"}}""", "en")!!.mood)
        assertEquals("sentiment as an object", "Wry", Insights.parse("""{"summary":["x"],"sentiment":{"label":"Wry","score":40}}""", "en")!!.mood)
    }

    @Test fun hindiValuesWithEnglishKeys() {
        val hi = """```json
            {
              "summary": ["ज़िम्मेदारियों को 97% दक्षता से टालना।", "कल 100% काम करने की योजना।"],
              "mood": "व्यंग्यात्मक",
              "tone": 45,
              "tone_line": "ऊबा हुआ, फिर आशावान",
              "scale": ["उदास", "स्थिर", "उत्साहित"],
              "shifts": [{"dir": "up", "label": "उम्मीद", "text": "कल की योजना"}]
            }
            ```"""
        val i = Insights.parse(hi, "hi")!!
        assertEquals(2, i.summary.size); assertEquals("व्यंग्यात्मक", i.mood); assertEquals("hi", i.lang); assertEquals(1, i.shifts.size)
        // Still strict where it matters: Chinese asked for, English back: rejected; Chinese back: fine.
        assertNull(Insights.parse(reply, "zh"))
        assertNotNull(Insights.parse("""{"Summary":"和妈妈逛集市","Mood":"充满希望"}""", "zh"))
        assertNull("no summary at all", Insights.parse("""{"mood":"Calm","tone":50}""", "en"))
        assertNull("blank summary string", Insights.parse("""{"summary":"  ","mood":"Calm"}""", "en"))
        assertNull("no mood", Insights.parse("""{"summary":"Something."}""", "en"))
        assertNull("cut off (no closing brace)", Insights.parse("""{"summary":["a","b"],"mood":"Ca""", "en"))
    }

    @Test fun promptsKeepTheEnglishKeysAndShowAnExample() {
        val p = Insights.makePrompt("hi")
        assertTrue(p, p.contains("Hindi"))
        assertTrue("an example object", p.contains("{\"summary\": ["))
        for (k in listOf("summary", "mood", "tone", "tone_line", "scale", "shifts", "dir")) assertTrue(k, p.contains("\"$k\""))
        assertTrue("keys stay English", p.contains("English key names exactly"))
        assertTrue(Insights.translatePrompt("hi").contains("English key names exactly"))
        assertTrue("room for a whole reply in Hindi / Malayalam", Insights.MAX_TOKENS >= 2000)
    }

    @Test fun chatContentAsPartsIsRead() {
        assertEquals("{\"a\":1}", ChatTranslation.content("""{"choices":[{"message":{"content":[{"type":"text","text":"{\"a\":1}"}]}}]}"""))
        assertEquals("ab", ChatTranslation.content("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""))
        assertEquals("plain", ChatTranslation.content("""{"choices":[{"message":{"content":"plain"}}]}"""))
        assertNull(ChatTranslation.content("""{"choices":[{"message":{"content":null}}]}"""))
        assertNull(ChatTranslation.content("""{"choices":[]}""")); assertNull(ChatTranslation.content("not json"))
    }

    @Test fun savedPerLanguageAndStaleAfterAnEdit() {
        val text = "Spent the morning at the market with Mum."
        val en = Insights.parse(reply, "en")!!
        val zh = Insights.parse("""{"summary":["和妈妈逛集市"],"mood":"充满希望","tone":70}""", "zh")!!
        assertEquals(Insights.Need.MAKE, Insights.need(null, text, "en"))
        val s1 = Insights.Saved(Insights.hash(text), emptyMap()).with(en, text)
        assertEquals(Insights.Need.SHOW, Insights.need(s1, text, "en"))
        assertEquals("the other language: translated, not remade", Insights.Need.TRANSLATE, Insights.need(s1, text, "zh"))
        val s2 = Insights.load(s1.with(zh, text).toJson())!!
        assertEquals(setOf("en", "zh"), s2.items.keys); assertEquals("充满希望", s2["zh"]!!.mood)
        assertEquals(en, s2["en"])
        assertEquals("edited: made again", Insights.Need.MAKE, Insights.need(s2, "$text Then home.", "en"))
        assertEquals("a new text starts over", setOf("zh"), s2.with(zh, "$text Then home.").items.keys)
        assertNull(Insights.load("not json")); assertNull(Insights.load(null))
    }

    @Test fun onlyLongNotesWithEnoughWords() {
        val words = (1..60).joinToString(" ") { "word$it" }
        assertTrue(Insights.eligible(NoteSchema.LONG, words, 50))
        assertFalse("Short notes never", Insights.eligible(NoteSchema.SHORT, words, 50))
        assertFalse("too few words", Insights.eligible(NoteSchema.LONG, "just a few words here", 50))
        assertFalse("empty", Insights.eligible(NoteSchema.LONG, "   ", 0))
        assertFalse("never tiny, whatever the setting", Insights.eligible(NoteSchema.LONG, "one two", 0))
        assertEquals("Chinese: about two characters a word", 5, Insights.words("我今天去了市场，很忙"))
    }

    @Test fun noProviderMeansNothingIsMade() {
        assertFalse(InsightsMaker(ctx).canMake())
        assertTrue(InsightsMaker(ctx).providers().isEmpty())
        // Set up AssemblyAI (key + OK): it can, first in the order. Sarvam (translation only) never answers prompts.
        ctx.getSharedPreferences("secrets", 0).edit().putString(AssemblyAiProvider.keyName, "placeholder").commit()
        ctx.setProviderConsent(SpeechProvider.ASSEMBLYAI, "the US")
        assertEquals(listOf<ChatProvider>(AssemblyAiTranslation), InsightsMaker(ctx).providers())
        ctx.settings().edit().putBoolean(Prefs.INSIGHTS.key, false).commit()
        assertFalse("switched off", InsightsMaker(ctx).canMake())
        val body = JSONObject(ChatTranslation.chatBody("m", "sys", "the note", 900))
        assertEquals(900, body.getInt("max_tokens")); assertEquals("the note", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("{\"a\":1}", ChatTranslation.content("""{"choices":[{"message":{"content":" {\"a\":1} "}}]}"""))
    }

    // ---- storage ----

    @Test fun existingV4NotesUpgradeWithNoInsights() {
        val file = ctx.getDatabasePath("notes.db"); file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        NoteSchema.CREATE_V4.forEach(db::execSQL)
        NoteSchema.ftsObjects("simple").forEach { runCatching { db.execSQL(it) } }
        db.execSQL("INSERT INTO notes (text, created, updated, status, kind, duration_ms, translation) VALUES ('a long talk', 1, 2, 'active', 'long', 65000, '一个长的')")
        db.execSQL("PRAGMA user_version = 4"); db.close()
        val store = NoteStore.get(ctx)
        val n = store.active(NoteSchema.LONG).single()
        assertEquals("a long talk", n.text); assertEquals("一个长的", n.translation); assertNull(n.insights); assertNull(n.insightsLang)
        store.setInsights(n.id, """{"v":1}""", "zh")
        assertEquals("""{"v":1}""", store.get(n.id)!!.insights); assertEquals("zh", store.get(n.id)!!.insightsLang)
        store.setInsights(n.id, null, null)
        assertNull(store.get(n.id)!!.insights)
        assertEquals(n.id, store.search("long talk", includeArchived = false).single().id)
    }

    // ---- the card on the note screen ----

    private val long = "Spent the morning at the Saturday market with Mum. The mango stall finally had Alphonsos again, and she " +
        "bargained like it was 1995. I've been anxious about the app launch all week. Too many small bugs, and I kept " +
        "second-guessing the translation feature. But walking home, it clicked: keep the switch simple. Plan for tomorrow: " +
        "fix the settings, ship the mockup, and call Arjun."

    private fun texts(v: View): List<String> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> if (v.visibility == View.VISIBLE || v.parent?.let { (it as View).visibility == View.VISIBLE } == true) listOf(v.text.toString()) else emptyList()
        else -> emptyList()
    }

    private fun open(id: Long) = Robolectric.buildActivity(NoteActivity::class.java,
        android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()

    @Test fun cardShowsSavedInsightsInTheTranslationLanguageWithOriginalChip() {
        doneSetup()
        val store = NoteStore.get(ctx)
        val id = store.add(long, NoteSchema.LONG)
        val en = Insights.parse(reply, "en")!!
        val zh = Insights.parse("""{"summary":["和妈妈逛集市","整周焦虑"],"mood":"充满希望","tone":70,"shifts":[{"dir":"up","label":"温暖","text":"和妈妈逛集市"}]}""", "zh")!!
        val saved = Insights.Saved(Insights.hash(long), emptyMap()).with(en, long).with(zh, long)
        store.setInsights(id, saved.toJson(), "zh")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id)
        val slot = a.findViewById<ViewGroup>(R.id.insights_slot)
        assertEquals(View.VISIBLE, slot.getChildAt(0).visibility)
        val t = texts(slot)
        assertTrue(t.toString(), t.containsAll(listOf("INSIGHTS", "Summary", "Sentiment", "和妈妈逛集市", "Show in English")))
        // The "Show in English" chip: the English ones (saved, so no request).
        allViews(slot).first { it is TextView && it.text == "Show in English" }.performClick()
        val o = texts(slot)
        assertTrue(o.toString(), o.contains("Launch anxiety all week.") && o.contains("Show in 中文"))
        // Translation off: the note's own language, no chip.
        a.findViewById<View>(R.id.tr_pill).performClick()
        val off = texts(slot)
        assertTrue(off.contains("Market with Mum: mangoes back.") && "Show in English" !in off && "Show in 中文" !in off)
    }

    @Test fun cardFlipsBetweenSummaryAndSentiment() {
        doneSetup()
        val store = NoteStore.get(ctx)
        val id = store.add(long, NoteSchema.LONG)
        store.setInsights(id, Insights.Saved(Insights.hash(long), emptyMap()).with(Insights.parse(reply, "en")!!, long).toJson(), "en")
        ctx.setTranslationOn(false)
        val a = open(id)
        val slot = a.findViewById<ViewGroup>(R.id.insights_slot)
        val sentimentTab = allViews(slot).first { it is TextView && it.text == "Sentiment" }
        sentimentTab.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(InsightsCard.FLIP_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        val mood = allViews(slot).first { it is TextView && it.text == "Hopeful" }
        assertTrue("the Sentiment side shows", visibleUp(mood, slot))
        assertFalse("the Summary side is hidden", visibleUp(allViews(slot).first { it is TextView && it.text == "Launch anxiety all week." }, slot))
    }

    @Test fun noInsightsForShortOrTinyNotesOrWithoutProviders() {
        doneSetup()
        val store = NoteStore.get(ctx)
        val short = store.add(long, NoteSchema.SHORT)
        assertEquals(View.GONE, open(short).findViewById<ViewGroup>(R.id.insights_slot).getChildAt(0).visibility)
        val tiny = store.add("A few words only", NoteSchema.LONG)
        assertEquals(View.GONE, open(tiny).findViewById<ViewGroup>(R.id.insights_slot).getChildAt(0).visibility)
        // Long and long enough, but nothing set up: hidden, no error, no provider names.
        val a = open(store.add(long, NoteSchema.LONG))
        assertEquals(View.GONE, a.findViewById<ViewGroup>(R.id.insights_slot).getChildAt(0).visibility)
    }

    // ---- loading, failing, trying again (v0.1.22) ----

    /** Stands in for AssemblyAI/OpenRouter: answers [reply] (null = no answer / timed out), counting the asks. */
    private class Fake(@Volatile var reply: String?) : ChatProvider {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        override val label = "Fake"
        override fun chatUsable(c: Context) = true
        override fun chat(c: Context, system: String, user: String, maxTokens: Int): String? { asks.incrementAndGet(); return reply }
    }

    private fun card(a: NoteActivity) = NoteActivity::class.java.getDeclaredField("insights").apply { isAccessible = true }.get(a) as InsightsCard

    /** Runs the 1.5 s wait, then lets the background request answer. */
    private fun settle(a: NoteActivity, until: (InsightsCard.State) -> Boolean) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(NoteActivity.INSIGHTS_DELAY_MS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        repeat(200) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (until(card(a).state)) return
            Thread.sleep(10)
        }
    }

    private fun longNote(): Long { doneSetup(); ctx.setTranslationOn(false); return NoteStore.get(ctx).add(long, NoteSchema.LONG) }

    @Test fun writingASummaryThenTheCard() {
        val fake = Fake(reply); InsightsMaker.testProviders = listOf(fake)
        val id = longNote()
        val a = open(id)
        assertEquals("on its way: a loading card, not empty space", InsightsCard.State.LOADING, card(a).state)
        val slot = a.findViewById<ViewGroup>(R.id.insights_slot)
        assertTrue(texts(slot).contains(InsightsCard.LOADING))
        assertTrue("no provider names", texts(slot).none { "Fake" in it })
        settle(a) { it == InsightsCard.State.SHOWN }
        assertEquals(InsightsCard.State.SHOWN, card(a).state)
        assertTrue(texts(slot).contains("Launch anxiety all week."))
        assertEquals(1, fake.asks.get())
        assertNotNull("saved with the note", Insights.load(NoteStore.get(ctx).get(id)!!.insights)?.get("en"))
        // Reopened: shown straight from the note, nothing asked.
        val again = open(id)
        assertEquals(InsightsCard.State.SHOWN, card(again).state); settle(again) { false }; assertEquals(1, fake.asks.get())
    }

    @Test fun openingTheNoteDoesNotCancelItsOwnRequest() {
        // onResume runs right after onCreate: it used to drop the request onCreate had just scheduled.
        val fake = Fake(reply); InsightsMaker.testProviders = listOf(fake)
        val a = open(longNote())
        settle(a) { it == InsightsCard.State.SHOWN }
        assertEquals(1, fake.asks.get()); assertEquals(InsightsCard.State.SHOWN, card(a).state)
    }

    @Test fun failureIsQuietAndTapTriesAgain() {
        val fake = Fake(null); InsightsMaker.testProviders = listOf(fake)
        val id = longNote()
        val a = open(id)
        settle(a) { it == InsightsCard.State.FAILED }
        assertEquals("no answer (timeout / refused)", InsightsCard.State.FAILED, card(a).state)
        val slot = a.findViewById<ViewGroup>(R.id.insights_slot)
        assertTrue(texts(slot).contains(InsightsCard.FAILED))
        settle(a) { false }
        assertEquals("no retry loop on its own", 1, fake.asks.get())
        // A reply that doesn't check out counts as a failure too.
        fake.reply = "I can't help with that"
        allViews(slot).first { it.contentDescription == InsightsCard.FAILED }.performClick()
        assertEquals(InsightsCard.State.LOADING, card(a).state)
        settle(a) { it == InsightsCard.State.FAILED }
        assertEquals(InsightsCard.State.FAILED, card(a).state); assertEquals(2, fake.asks.get())
        // Tap again with a good answer: the failed try is forgotten and it works.
        fake.reply = reply
        allViews(slot).first { it.contentDescription == InsightsCard.FAILED }.performClick()
        settle(a) { it == InsightsCard.State.SHOWN }
        assertEquals(InsightsCard.State.SHOWN, card(a).state); assertEquals(3, fake.asks.get())
    }

    @Test fun failedTriesAreTriedAgainWhenTheNoteIsOpenedAgain() {
        val fake = Fake(null); InsightsMaker.testProviders = listOf(fake)
        val id = longNote()
        val first = Robolectric.buildActivity(NoteActivity::class.java,
            android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup()
        settle(first.get()) { it == InsightsCard.State.FAILED }
        assertEquals(InsightsCard.State.FAILED, card(first.get()).state)
        // Back to the same screen (another app, the lock screen): tried again.
        fake.reply = reply
        first.pause().stop().restart().start().resume()
        assertEquals(InsightsCard.State.LOADING, card(first.get()).state)
        settle(first.get()) { it == InsightsCard.State.SHOWN }
        assertEquals(InsightsCard.State.SHOWN, card(first.get()).state); assertEquals(2, fake.asks.get())
        // Opened fresh after a failure: tried again too.
        val other = NoteStore.get(ctx).add(long + " Again.", NoteSchema.LONG)
        fake.reply = null
        val b = open(other); settle(b) { it == InsightsCard.State.FAILED }
        fake.reply = reply
        val c = open(other)
        assertEquals(InsightsCard.State.LOADING, card(c).state)
        settle(c) { it == InsightsCard.State.SHOWN }; assertEquals(InsightsCard.State.SHOWN, card(c).state)
    }

    @Test fun stillHiddenWhenOffShortOrNothingCanMakeThem() {
        val fake = Fake(reply); InsightsMaker.testProviders = listOf(fake)
        doneSetup(); ctx.setTranslationOn(false)
        val store = NoteStore.get(ctx)
        assertEquals("Short note", InsightsCard.State.HIDDEN, card(open(store.add(long, NoteSchema.SHORT))).state)
        assertEquals("too few words", InsightsCard.State.HIDDEN, card(open(store.add("A few words only", NoteSchema.LONG))).state)
        ctx.settings().edit().putBoolean(Prefs.INSIGHTS.key, false).commit()
        val off = open(store.add(long, NoteSchema.LONG)); settle(off) { false }
        assertEquals("Insights off", InsightsCard.State.HIDDEN, card(off).state)
        ctx.settings().edit().putBoolean(Prefs.INSIGHTS.key, true).commit()
        InsightsMaker.testProviders = null // no key / no OK / offline
        val none = open(store.add(long, NoteSchema.LONG)); settle(none) { false }
        assertEquals("nothing can make them", InsightsCard.State.HIDDEN, card(none).state)
        assertEquals(0, fake.asks.get())
    }

    @Test fun insightsWaitLongerThanTranslation() {
        assertEquals(30, ctx.settings()[Prefs.INSIGHTS_TIMEOUT])
        assertEquals(30_000, TranslationHttp.chatTimeoutMs(ctx))
        assertEquals("live translation keeps its 10 s", 10_000, TranslationHttp.timeoutMs(ctx))
        ctx.settings().edit().putInt(Prefs.INSIGHTS_TIMEOUT.key, 60).commit()
        assertEquals(60_000, TranslationHttp.chatTimeoutMs(ctx)); assertEquals(10_000, TranslationHttp.timeoutMs(ctx))
        assertTrue(Prefs.INSIGHTS_TIMEOUT in Prefs.advanced.first { it.first == "Insights" }.second)
    }

    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun visibleUp(v: View, top: View): Boolean {
        var x: View? = v
        while (x != null && x !== top) { if (x.visibility != View.VISIBLE) return false; x = x.parent as? View }
        return true
    }
}
