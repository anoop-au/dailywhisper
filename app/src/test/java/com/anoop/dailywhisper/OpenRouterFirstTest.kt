package com.anoop.dailywhisper

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** v0.1.26 (Anoop, 13 Nov 2026: "i have openrouter"): OpenRouter first once its key is saved; a model picker. */
@RunWith(RobolectricTestRunner::class)
class OpenRouterFirstTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val OR = OpenRouterTranslation; private val AAI = AssemblyAiTranslation; private val SAR = SarvamTranslation
    private val OPUS = OpusMtTranslation; private val MLK = MlKitTranslation

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        InsightsMaker.testProviders = null; NetDiag.clear()
    }
    @After fun reset() { InsightsMaker.testProviders = null; NetDiag.clear() }

    private fun orKey() = ctx.getSharedPreferences("secrets", 0).edit().putString(OR.keyName, "placeholder").commit()
    private fun allOk() {
        orKey(); ctx.getSharedPreferences("secrets", 0).edit().putString(AAI.keyName, "placeholder").putString(SAR.keyName, "placeholder").commit()
        ctx.uiState().edit().putBoolean(OR.CONSENT, true).commit()
    }

    // ---- the order ----

    @Test fun effectiveOrderIsPure() {
        assertEquals(TranslationRegistry.OPENROUTER_FIRST_ORDER, TranslationRegistry.effectiveOrder(null, true))
        assertEquals(TranslationRegistry.OPENROUTER_FIRST_ORDER, TranslationRegistry.effectiveOrder(" ", true))
        assertNull("no key: today's default", TranslationRegistry.effectiveOrder(null, false))
        assertEquals("set by hand wins", "sarvam,assemblyai,openrouter", TranslationRegistry.effectiveOrder("sarvam,assemblyai,openrouter", true))
        assertEquals(listOf(OR, AAI, SAR, OPUS, MLK), TranslationRegistry.ordered(TranslationRegistry.OPENROUTER_FIRST_ORDER))
    }

    @Test fun noOpenRouterKeyKeepsTodaysOrder() {
        assertEquals(listOf(AAI, SAR, OR, OPUS, MLK), ctx.translationOrder())
    }

    @Test fun openRouterKeyPutsItFirstThenAssemblyAiThenSarvamThenThePhone() {
        orKey()
        assertEquals(listOf(OR, AAI, SAR, OPUS, MLK), ctx.translationOrder())
    }

    @Test fun anOrderSetByHandStaysEvenWithAKey() {
        orKey()
        // The order dialog: tap AssemblyAI to try it first (saved from the order shown, OpenRouter first).
        ctx.settings().edit().putString(Prefs.TR_ORDER.key, TranslationRegistry.moveFirst(ctx.translationOrderSaved(), AAI)).commit()
        assertEquals(listOf(AAI, OR, SAR, OPUS, MLK), ctx.translationOrder())
        ctx.settings().edit().remove(Prefs.TR_ORDER.key).commit() // "Default"
        assertEquals(OR, ctx.translationOrder().first())
    }

    @Test fun translationRouteTriesOpenRouterFirstAndSarvamOnlyForIndianLanguages() {
        allOk()
        val usable = { p: TranslationProvider -> p.online || p === MLK }
        assertEquals(listOf(OR, AAI, MLK), TranslationRegistry.route("en", "zh", true, ctx.translationOrder(), usable))
        assertEquals(listOf(OR, AAI, SAR, MLK), TranslationRegistry.route("en", "hi", true, ctx.translationOrder(), usable).filter { it !== OPUS })
        assertEquals("offline: the phone only", listOf(MLK), TranslationRegistry.route("en", "zh", false, ctx.translationOrder(), usable))
    }

    @Test fun insightsAskOpenRouterFirst() {
        allOk()
        val chat = InsightsMaker(ctx).providers()
        assertEquals("OpenRouter first; Sarvam can't write summaries", OR, chat.first())
        assertTrue(chat.none { it === SAR as Any })
    }

    // ---- the model ----

    @Test fun modelDefaultIsGeminiFlashLiteAndPickerChoicesWork() {
        assertEquals("google/gemini-2.5-flash-lite", OR.DEFAULT_MODEL)
        assertEquals(OR.DEFAULT_MODEL, OR.model(ctx))
        assertEquals(OR.DEFAULT_MODEL, Prefs.OR_MODEL_PICK.def)
        assertEquals(OR.DEFAULT_MODEL, OR.MODELS.first().first)
        assertTrue(OR.MODELS.any { it.first == "deepseek/deepseek-v4.1-flash" })
        assertTrue(OR.MODELS.all { it.first in OR.MODEL_HINTS })
        ctx.settings().edit().putString(OR.KEY_MODEL_PICK, "deepseek/deepseek-v4.1-flash").commit()
        assertEquals("deepseek/deepseek-v4.1-flash", OR.model(ctx))
        ctx.settings().edit().putString(OR.KEY_MODEL_PICK, OR.CUSTOM).putString(OR.KEY_MODEL, "  mistral/x  ").commit()
        assertEquals("mistral/x", OR.model(ctx))
        val b = JSONObject(OR.body("hi", "en", "zh", OR.model(ctx)))
        assertEquals("mistral/x", b.getString("model"))
        assertEquals("deny", b.getJSONObject("provider").getString("data_collection"))
    }

    @Test fun aModelTypedBeforeKeepsWorkingAfterTheUpgrade() {
        ctx.uiState().edit().putInt("settings_version", 9).commit()
        ctx.getSharedPreferences("settings", 0).edit().putString(OR.KEY_MODEL, "anthropic/some-model").commit()
        assertEquals(OR.CUSTOM, ctx.settings().getString(OR.KEY_MODEL_PICK, null))
        assertEquals("anthropic/some-model", OR.model(ctx))
    }

    @Test fun theDefaultTypedIdNeedsNoPicker() {
        ctx.uiState().edit().putInt("settings_version", 9).commit()
        ctx.getSharedPreferences("settings", 0).edit().putString(OR.KEY_MODEL, OR.DEFAULT_MODEL).commit()
        assertNull(ctx.settings().getString(OR.KEY_MODEL_PICK, null))
        assertEquals(OR.DEFAULT_MODEL, OR.model(ctx))
    }

    // ---- Test summary & translation: plain reasons ----

    @Test fun plainReasonsForOpenRouterFailures() {
        fun f(s: Int, m: String = "") = NetDiag.Failure(s, m)
        assertEquals("refused the key (HTTP 401)", NetDiag.plain(f(401), 30))
        assertEquals("refused the key (HTTP 403)", NetDiag.plain(f(403), 30))
        assertEquals("needs credit on the account (HTTP 402)", NetDiag.plain(f(402), 30))
        assertEquals("busy right now (HTTP 429)", NetDiag.plain(f(429), 30))
        assertEquals("no answer in 30 s", NetDiag.plain(f(NetDiag.TIMEOUT), 30))
        assertEquals("doesn't know that model (HTTP 400)", NetDiag.plain(f(400, "foo/bar is not a valid model ID"), 30))
        assertEquals("doesn't know that model (HTTP 404)", NetDiag.plain(f(404, "No endpoints found for foo/bar."), 30))
        assertTrue(NetDiag.plain(f(404, "No endpoints found matching your data policy"), 30).contains("not to train"))
        assertEquals("didn't accept the request (HTTP 400)", NetDiag.plain(f(400, "messages must not be empty"), 30))
        assertTrue(OnlineCheck.openRouterHint(f(401)).contains("Check the key"))
        assertTrue(OnlineCheck.openRouterHint(f(402)).contains("credit"))
        assertTrue(OnlineCheck.openRouterHint(f(400, "x is not a valid model ID")).contains("Pick another Model"))
        assertTrue(OnlineCheck.openRouterHint(f(429)).contains("minute"))
        assertTrue(OnlineCheck.openRouterHint(f(NetDiag.TIMEOUT)).contains("Wait for a summary"))
    }

    @Test fun testCheckListsOpenRouterFirstAndSaysWhatIsMissing() {
        orKey() // a key but no OK yet: no request is sent
        val chat = ctx.translationOrder().filterIsInstance<ChatProvider>()
        assertEquals(OR, chat.first())
        assertTrue(OnlineCheck.line(ctx, OR).contains("needs your OK"))
        ctx.settings().edit().putBoolean(OR.useKey, false).commit()
        assertTrue(OnlineCheck.line(ctx, OR).startsWith("switched off"))
    }

    @Test fun theModelPickerIsOnTheOpenRouterPage() {
        val page = Prefs.translationPages(TranslationRegistry.ALL).first { it.translator === OR }
        assertEquals(listOf(Prefs.USE_OPENROUTER, Prefs.OR_KEY, Prefs.OR_MODEL_PICK, Prefs.OR_MODEL), page.prefs)
    }
}
