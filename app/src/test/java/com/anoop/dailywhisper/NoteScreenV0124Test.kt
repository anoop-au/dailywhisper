package com.anoop.dailywhisper

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.shadows.ShadowToast

/**
 * v0.1.24 (Anoop, 11 Nov 2026): one translation on screen, copy either side and the insights, the whole note translated
 * online with context after Stop, Chinese tidied, and plain reasons when the online services fail.
 */
@RunWith(RobolectricTestRunner::class)
class NoteScreenV0124Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ctx.deleteDatabase("notes.db")
        for (f in listOf("settings", "state", "secrets", NoteActivity.WHOLE_PREFS)) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).putBoolean(WritingMode.KEY_CHOSEN, true).commit()
        NetDiag.clear()
    }

    @After fun reset() {
        NoteStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        NoteTranslator.testRoute = null; InsightsMaker.testProviders = null; NetDiag.clear()
    }

    /** Stands in for an online translator (AssemblyAI / OpenRouter): answers [reply], counting the asks. */
    private class FakeTr(@Volatile var reply: String?) : TranslationProvider {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        override val id = "fake"; override val label = "Fake"; override val online = true
        override fun supports(source: String, target: String) = true
        override fun usable(c: Context, source: String, target: String) = true
        override fun translate(t: NoteTranslator, text: String, source: String, target: String, cancelled: () -> Boolean): TranslationProvider.Result {
            asks.incrementAndGet(); seen += text; return TranslationProvider.Result(reply)
        }
    }

    private class FakeChat(@Volatile var reply: String?) : ChatProvider {
        override val label = "Fake"
        override fun chatUsable(c: Context) = true
        override fun chat(c: Context, system: String, user: String, maxTokens: Int): String? = reply
    }

    private fun open(id: Long) = Robolectric.buildActivity(NoteActivity::class.java,
        android.content.Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id)).setup().get()

    private fun settle(until: () -> Boolean) {
        repeat(300) { ShadowLooper.idleMainLooper(); if (until()) return; Thread.sleep(10) }
    }

    private fun clip(): String = ctx.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()

    // ---- 1. one translation surface ----

    @Test fun withTranslationOnATapNeverOpensASecondTranslation() {
        val id = NoteStore.get(ctx).add("I went to the market today", NoteSchema.SHORT, "我今天去了市场")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id)
        a.findViewById<View>(R.id.edit).performClick(); ShadowLooper.idleMainLooper()
        assertEquals("no bottom translation bar", View.GONE, a.findViewById<View>(R.id.tr_panel).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.note_tr).visibility)
        // Off: the original alone, and a tap translates (one surface again).
        a.findViewById<View>(R.id.tr_pill).performClick()
        a.findViewById<View>(R.id.edit).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.tr_panel).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.note_tr).visibility)
        // Back on: the panel closes itself.
        a.findViewById<View>(R.id.tr_pill).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.tr_panel).visibility)
    }

    @Test fun tapToTranslateUsesTheOnlineTranslatorsFirst() {
        val fake = FakeTr("我今天去了市场。"); NoteTranslator.testRoute = listOf(fake)
        val id = NoteStore.get(ctx).add("I went to the market today")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(false)
        val a = open(id)
        a.findViewById<View>(R.id.edit).performClick()
        val tr = a.findViewById<TextView>(R.id.tr_text)
        settle { tr.text.isNotEmpty() }
        assertEquals("我今天去了市场。", tr.text.toString())
        assertTrue(a.findViewById<TextView>(R.id.tr_title).text.toString().contains("Fake"))
        assertEquals(1, fake.asks.get())
    }

    // ---- whole note, with context, after Stop / on open ----

    @Test fun stoppedNoteIsTranslatedWholeOnceAndSaved() {
        val fake = FakeTr("所以 嗯,亲爱的 。我前天晚上在你家的时候 想告诉你"); NoteTranslator.testRoute = listOf(fake)
        val store = NoteStore.get(ctx)
        val text = "So, um, darling. The day before yesterday evening when I was at your place, I wanted to tell you."
        val id = store.add(text, NoteSchema.LONG, "所以 嗯亲爱的。前天的前一天晚上 当")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id)
        val tr = a.findViewById<TextView>(R.id.note_tr)
        settle { store.get(id)!!.translation != "所以 嗯亲爱的。前天的前一天晚上 当" }
        assertEquals("the whole note in one go (context), tidied", "所以嗯，亲爱的。我前天晚上在你家的时候想告诉你", store.get(id)!!.translation)
        assertEquals(store.get(id)!!.translation, tr.text.toString())
        assertEquals(1f, tr.alpha)
        assertEquals(listOf(text), fake.seen.toList())
        // Opened again: already done for this text and language, nothing asked.
        val b = open(id); settle { false }
        assertEquals(1, fake.asks.get())
        assertEquals(store.get(id)!!.translation, b.findViewById<TextView>(R.id.note_tr).text.toString())
    }

    @Test fun longNotesGoInParagraphSizedPieces() {
        val fake = FakeTr("段。"); NoteTranslator.testRoute = listOf(fake)
        val sentence = "This is one fairly ordinary spoken sentence about the day. "
        val text = sentence.repeat(40).trim()
        val id = NoteStore.get(ctx).add(text, NoteSchema.LONG)
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        open(id); settle { fake.asks.get() >= 3 && NoteStore.get(ctx).get(id)!!.translation != null }
        assertTrue("pieces of a paragraph, not sentence fragments", fake.seen.all { it.length in 300..NoteTranslator.ONLINE_CHUNK })
        assertEquals(text.length.toDouble(), fake.seen.sumOf { it.length }.toDouble(), 40.0)
    }

    @Test fun nothingOnlineKeepsTheLiveTranslation() {
        NoteTranslator.testRoute = emptyList()
        val id = NoteStore.get(ctx).add("I went to the market", NoteSchema.LONG, "我去了市场")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id); settle { false }
        assertEquals("我去了市场", NoteStore.get(ctx).get(id)!!.translation)
        assertEquals("我去了市场", a.findViewById<TextView>(R.id.note_tr).text.toString())
    }

    // ---- 2. copy either side ----

    @Test fun copyEitherSide() {
        val id = NoteStore.get(ctx).add("I went to the market today", NoteSchema.SHORT, "我今天去了市场")
        ctx.setTranslateInto("zh"); ctx.setTranslationOn(true)
        val a = open(id)
        assertEquals("English", a.findViewById<TextView>(R.id.orig_label).text.toString())
        assertEquals("中文", a.findViewById<TextView>(R.id.tr_label).text.toString())
        a.findViewById<View>(R.id.copy_orig).performClick()
        assertEquals("I went to the market today", clip()); assertEquals("Copied", ShadowToast.getTextOfLatestToast())
        a.findViewById<View>(R.id.copy_tr).performClick()
        assertEquals("我今天去了市场", clip())
        // Long-press the original: Copy / Edit here (editing no longer takes the long-press by itself).
        a.findViewById<View>(R.id.edit).performLongClick()
        val menu = ShadowPopupMenu.getLatestPopupMenu()!!.menu
        assertEquals(listOf("Copy", "Edit here"), (0 until menu.size()).map { menu.getItem(it).title.toString() })
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("x", "x"))
        org.robolectric.Shadows.shadowOf(ShadowPopupMenu.getLatestPopupMenu()).onMenuItemClickListener.onMenuItemClick(menu.getItem(0))
        assertEquals("I went to the market today", clip())
        a.findViewById<View>(R.id.note_tr).performLongClick()
        val m2 = ShadowPopupMenu.getLatestPopupMenu()!!.menu
        assertEquals("Copy", m2.getItem(0).title.toString())
    }

    // ---- 3. copy the insights ----

    @Test fun copyTheInsightsFaceShowing() {
        val store = NoteStore.get(ctx)
        val long = (1..80).joinToString(" ") { "word$it" }
        val id = store.add(long, NoteSchema.LONG)
        val en = Insights.parse("""{"summary":["Market with Mum.","Launch worries."],"mood":"Hopeful","tone":72,
            "tone_line":"anxious, then settled","shifts":[{"dir":"up","label":"Relief","text":"It clicked"}]}""", "en")!!
        store.setInsights(id, Insights.Saved(Insights.hash(long), emptyMap()).with(en, long).toJson(), "en")
        ctx.setTranslationOn(false)
        val a = open(id)
        val card = NoteActivity::class.java.getDeclaredField("insights").apply { isAccessible = true }.get(a) as InsightsCard
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        val copy = all(a.findViewById(R.id.insights_slot)).first { it.contentDescription == "Copy" }
        assertEquals(View.VISIBLE, copy.visibility)
        copy.performClick()
        assertEquals("Summary\n• Market with Mum.\n• Launch worries.", clip())
        card.flipTo(true); ShadowLooper.idleMainLooper(InsightsCard.FLIP_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        copy.performClick()
        assertEquals("Sentiment: Hopeful (72/100)\nanxious, then settled\n↑ Relief: It clicked", clip())
    }

    // ---- 4. why summaries fail, in plain words ----

    @Test fun failureSaysWhyWithoutNamingTheService() {
        InsightsMaker.testProviders = listOf(FakeChat(null))
        NetDiag.failed("Fake", 403, "LLM Gateway is not available on the free tier")
        val long = (1..80).joinToString(" ") { "word$it" }
        val id = NoteStore.get(ctx).add(long, NoteSchema.LONG)
        ctx.setTranslationOn(false)
        val a = open(id)
        val card = NoteActivity::class.java.getDeclaredField("insights").apply { isAccessible = true }.get(a) as InsightsCard
        ShadowLooper.idleMainLooper(NoteActivity.INSIGHTS_DELAY_MS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        settle { card.state == InsightsCard.State.FAILED }
        fun texts(v: View): List<String> = when (v) { is TextView -> listOf(v.text.toString()); is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }; else -> emptyList() }
        val t = texts(a.findViewById(R.id.insights_slot))
        assertTrue(t.toString(), t.any { it.contains("refused the key (HTTP 403)") })
        assertTrue("no service names on the card", t.none { "Fake" in it || "AssemblyAI" in it })
    }

    @Test fun netDiagReasonsAndRedaction() {
        assertEquals("refused the key (HTTP 401)", NetDiag.plain(NetDiag.Failure(401, ""), 30))
        assertEquals("needs credit on the account (HTTP 402)", NetDiag.plain(NetDiag.Failure(402, ""), 30))
        assertEquals("no answer in 30 s", NetDiag.plain(NetDiag.Failure(NetDiag.TIMEOUT, ""), 30))
        assertEquals("couldn't connect", NetDiag.plain(NetDiag.Failure(NetDiag.UNREACHABLE, ""), 30))
        assertEquals("had a problem on its side (HTTP 503)", NetDiag.plain(NetDiag.Failure(503, ""), 30))
        val key = "abcdef0123456789secret"
        assertEquals("bad key … here", NetDiag.serverMessage("""{"error":"bad key $key here"}""", listOf(key)))
        assertEquals("nope …", NetDiag.serverMessage("""{"error":{"message":"nope $key"}}""", listOf("Bearer $key")))
        assertEquals("invalid request body", NetDiag.serverMessage("""{"code":400,"message":"invalid request body"}""", emptyList()))
        assertEquals("", NetDiag.serverMessage("<html>502</html>", emptyList()))
    }

    @Test fun onlineCheckSaysWhatWorksAndWhy() {
        val works = FakeChat("OK"); val broken = FakeChat(null)
        assertTrue(OnlineCheck.line(ctx, works).startsWith("works ("))
        NetDiag.failed("Fake", 401, "Authentication error, API token missing/invalid")
        assertEquals("failed: refused the key (HTTP 401) – \"Authentication error, API token missing/invalid\"", OnlineCheck.line(ctx, broken))
        // AssemblyAI not set up: says what's missing, asks nothing.
        assertEquals("no key (Settings → Online services → AssemblyAI)", OnlineCheck.line(ctx, AssemblyAiTranslation))
        ctx.settings().edit().putBoolean(AssemblyAiTranslation.useKey, false).commit()
        assertEquals("switched off (Settings → Online services → AssemblyAI)", OnlineCheck.line(ctx, AssemblyAiTranslation))
        InsightsMaker.testProviders = listOf(works)
        val lines = OnlineCheck.run(ctx)
        assertTrue(lines.toString(), lines.first().startsWith("Fake: works") && lines.last().contains("use Fake first"))
    }

    // ---- 7. Chinese ----

    @Test fun chineseIsTidiedWhoeverTranslates() {
        assertEquals("所以嗯，亲爱的。啊，它来了。", TranslateConfig.tidy("所以 嗯,亲爱的 。啊, 它来了.", "zh"))
        assertEquals("我在你家的时候，有东西想告诉你？", TranslateConfig.tidy("我在你家的时候 , 有东 西想告诉你?", "zh-Hans"))
        assertEquals("用iPhone 16拍的", TranslateConfig.tidy("用 iPhone 16 拍的", "zh"))
        assertEquals("unchanged , for other languages", TranslateConfig.tidy("unchanged , for other languages", "es"))
        assertEquals("3.5小时", TranslateConfig.tidy("3.5 小时", "zh"))
        val p = ChatTranslation.prompt("en", "zh")
        assertTrue(p, p.contains("简体中文") && p.contains("never put spaces between Chinese characters") && p.contains("fillers"))
        assertFalse(ChatTranslation.prompt("en", "es").contains("简体中文"))
        assertTrue(ChatTranslation.prompt("en", "es").contains("natural"))
        assertNull(ChatTranslation.TARGET_STYLE["es"])
    }
}
