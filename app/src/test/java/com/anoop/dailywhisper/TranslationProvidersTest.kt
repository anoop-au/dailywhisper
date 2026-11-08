package com.anoop.dailywhisper

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The translation provider layer: routing by order and pair (Sarvam only for Indian languages), the providers' requests/replies, the guard, and the v6/v7 migrations. */
@RunWith(RobolectricTestRunner::class)
class TranslationProvidersTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val OR = OpenRouterTranslation
    private val OPUS = OpusMtTranslation
    private val MLK = MlKitTranslation

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private val AAI = AssemblyAiTranslation
    private val SAR = SarvamTranslation
    private val ALL_READY: Set<TranslationProvider> = setOf(AAI, SAR, OR, OPUS, MLK)

    private fun route(s: String, t: String, online: Boolean = true, order: String? = null, ready: Set<TranslationProvider> = ALL_READY) =
        TranslationRegistry.route(s, t, online, TranslationRegistry.ordered(order), { it in ready })

    // ---- routing ----

    @Test fun defaultOrderIsAssemblyAiSarvamOpenRouterPhone() {
        assertEquals(listOf(AAI, SAR, OR, OPUS, MLK), TranslationRegistry.ordered(null))
        assertEquals("English → Hindi: all of them", listOf(AAI, SAR, OR, OPUS, MLK), route("en", "hi"))
        assertEquals("English → Malayalam: ML Kit can't", listOf(AAI, SAR, OR, OPUS), route("en", "ml"))
        assertEquals("Malayalam → English: Sarvam second", listOf(AAI, SAR, OR, OPUS), route("ml", "en"))
        assertEquals("offline: only the phone", listOf(OPUS, MLK), route("en", "zh", online = false))
        assertEquals("AssemblyAI not set up: Sarvam first", listOf(SAR, OR, OPUS), route("en", "ml", ready = setOf(SAR, OR, OPUS)))
        assertEquals("nothing online: the phone", listOf(OPUS, MLK), route("en", "zh", ready = setOf(OPUS, MLK)))
        assertEquals("Opus-MT models missing: ML Kit", listOf(MLK), route("en", "zh", ready = setOf(MLK)))
        assertEquals("Malayalam with nothing ready", emptyList<TranslationProvider>(), route("en", "ml", ready = setOf(MLK)))
        assertTrue("same language: nothing", route("en", "en").isEmpty())
    }

    @Test fun sarvamOnlyForIndianLanguages() {
        assertEquals("English → Chinese: no Sarvam", listOf(AAI, OR, OPUS, MLK), route("en", "zh"))
        assertFalse(SAR.supports("en", "zh")); assertFalse(SAR.supports("en", "es")); assertFalse(SAR.supports("de", "fr"))
        assertFalse("an Indian language with one Sarvam doesn't know", SAR.supports("hi", "zh"))
        assertTrue(SAR.supports("en", "ml")); assertTrue(SAR.supports("ml", "en")); assertTrue(SAR.supports("hi", "ta"))
        assertTrue("Sarvam-Translate's extra ones", SAR.supports("en", "ur") && SAR.supports("kok", "en"))
        assertFalse("English ↔ English", SAR.supports("en", "en"))
        assertEquals("Punjabi: only online ones know it", listOf(AAI, SAR, OR), route("en", "pa"))
        assertEquals("od-IN", SAR.code("or")); assertEquals("en-IN", SAR.code("en")); assertEquals("ml-IN", SAR.code("ml"))
        assertNull(SAR.code("zh"))
        assertTrue(TranslationRegistry.knows("pa") && TranslationRegistry.knows("zh") && !TranslationRegistry.knows("xx"))
        assertTrue("offered in Live translation into", TranslationRegistry.choices().contains("pa" to "Punjabi"))
    }

    @Test fun reorderKeepsThePhoneLast() {
        val saved = TranslationRegistry.moveFirst(null, OR)
        assertEquals("openrouter,assemblyai,sarvam", saved)
        assertEquals(listOf(OR, AAI, SAR, OPUS, MLK), TranslationRegistry.ordered(saved))
        assertEquals(listOf(OR, AAI, SAR, OPUS), route("en", "ml", order = saved))
        assertEquals("unknown ids skipped, missing appended", listOf(SAR, AAI, OR, OPUS, MLK), TranslationRegistry.ordered("bogus,sarvam,opus"))
        assertEquals("a failing one falls through: the next in order is tried", listOf(SAR, OR),
            route("en", "hi", order = "sarvam,openrouter,assemblyai", ready = setOf(SAR, OR)))
    }

    @Test fun assemblyAiAndSarvamReuseTheirSpeechKeyAndOk() {
        assertEquals(AssemblyAiProvider.keyName, AAI.keyName); assertEquals(SarvamProvider.keyName, SAR.keyName)
        assertFalse(AAI.usable(ctx, "en", "zh")); assertFalse(SAR.usable(ctx, "en", "ml"))
        ctx.getSharedPreferences("secrets", 0).edit().putString(AAI.keyName, "placeholder").putString(SAR.keyName, "placeholder").commit()
        assertFalse("key, but the US needs an OK from Australia", AAI.usable(ctx, "en", "zh"))
        assertFalse("key, but India needs an OK", SAR.usable(ctx, "en", "ml"))
        ctx.setProviderConsent(SpeechProvider.ASSEMBLYAI, "the US"); ctx.setSarvamConsent(true)
        assertTrue(AAI.usable(ctx, "en", "zh")); assertTrue(SAR.usable(ctx, "en", "ml"))
        ctx.settings().edit().putBoolean(AAI.useKey, false).putBoolean(SAR.useKey, false).commit()
        assertFalse("switched off", AAI.usable(ctx, "en", "zh") || SAR.usable(ctx, "en", "ml"))
    }

    @Test fun assemblyAiRequest() {
        assertEquals("https://llm-gateway.assemblyai.com/v1/chat/completions", AAI.url("us"))
        assertEquals("https://llm-gateway.eu.assemblyai.com/v1/chat/completions", AAI.url("eu"))
        val b = JSONObject(ChatTranslation.body("I went to the market", "en", "ml", AAI.DEFAULT_MODEL))
        assertEquals("gemini-2.5-flash-lite", b.getString("model"))
        assertFalse("no OpenRouter-only fields", b.has("provider"))
        assertTrue(b.getJSONArray("messages").getJSONObject(0).getString("content").contains("Malayalam"))
        assertTrue(ChatTranslation.prompt("pa", "en").contains("Punjabi"))
        assertEquals("ഞാൻ ചന്തയിൽ പോയി", ChatTranslation.parse("""{"choices":[{"message":{"content":"ഞാൻ ചന്തയിൽ പോയി"}}]}"""))
        assertTrue(AAI.describe(401)!!.contains("AssemblyAI refused the key")); assertNull(AAI.describe(500))
    }

    @Test fun sarvamRequest() {
        assertEquals("mayura for English ↔ Malayalam", SarvamTranslation.MAYURA, SAR.model("auto", "en", "ml"))
        assertEquals("sarvam-translate for Urdu", SarvamTranslation.SARVAM_TRANSLATE, SAR.model("auto", "ur", "en"))
        assertEquals("mayura picked but it can't: sarvam-translate", SarvamTranslation.SARVAM_TRANSLATE, SAR.model(SarvamTranslation.MAYURA, "kok", "en"))
        assertEquals(SarvamTranslation.SARVAM_TRANSLATE, SAR.model(SarvamTranslation.SARVAM_TRANSLATE, "en", "hi"))
        val b = JSONObject(SAR.body("I went to the market", "en", "or", SarvamTranslation.MAYURA, "modern-colloquial")!!)
        assertEquals("I went to the market", b.getString("input"))
        assertEquals("en-IN", b.getString("source_language_code")); assertEquals("od-IN", b.getString("target_language_code"))
        assertEquals("mayura:v1", b.getString("model")); assertEquals("modern-colloquial", b.getString("mode"))
        assertEquals("sarvam-translate is formal only", "formal",
            JSONObject(SAR.body("x", "en", "ur", SarvamTranslation.SARVAM_TRANSLATE, "modern-colloquial")!!).getString("mode"))
        assertNull("too long for mayura: the next one translates", SAR.body("a".repeat(1001), "en", "hi", SarvamTranslation.MAYURA, "formal"))
        assertEquals("मैं बाज़ार गया", SAR.parse("""{"request_id":"r","translated_text":" मैं बाज़ार गया ","source_language_code":"en-IN"}"""))
        assertNull(SAR.parse("""{"error":{"message":"bad","code":"invalid_api_key_error"}}""")); assertNull(SAR.parse("<html>"))
        assertTrue(SAR.describe(403)!!.contains("Sarvam AI refused the key"))
    }

    @Test fun migrationV7() {
        val c = SettingsMigration.toTranslationOrder(mapOf("or_first" to false, "or_timeout_s" to 15))
        assertEquals(mapOf("tr_timeout_s" to 15), c.put)
        assertEquals(setOf("or_first", "or_timeout_s"), c.remove)
        assertTrue(SettingsMigration.toTranslationOrder(emptyMap<String, Any>()).put.isEmpty())
        ctx.uiState().edit().putInt("settings_version", 6).commit()
        ctx.getSharedPreferences("settings", 0).edit().putBoolean("or_first", false).putInt("or_timeout_s", 20)
            .putString("dg_language", "ml").commit()
        val p = ctx.settings()
        assertEquals(20, p[Prefs.TR_TIMEOUT]); assertFalse(p.contains("or_first") || p.contains("or_timeout_s"))
        assertEquals("the dictation language survives", "ml", p[Prefs.DG_LANGUAGE])
        assertEquals(listOf(AAI, SAR, OR, OPUS, MLK), ctx.translationOrder())
    }

    @Test fun openRouterIsOffUntilKeyAndConsent() {
        assertFalse(OR.usable(ctx, "en", "zh"))
        ctx.getSharedPreferences("secrets", 0).edit().putString(OR.keyName, "placeholder").commit()
        assertFalse("key but no OK", OR.usable(ctx, "en", "zh"))
        ctx.uiState().edit().putBoolean(OR.CONSENT, true).commit()
        assertTrue(OR.usable(ctx, "en", "zh"))
        ctx.settings().edit().putBoolean(OR.useKey, false).commit()
        assertFalse("switched off", OR.usable(ctx, "en", "zh"))
        assertTrue("the phone's ML Kit needs nothing", MLK.usable(ctx, "en", "zh"))
        assertFalse("no Opus-MT models on a fresh phone", OPUS.usable(ctx, "en", "ml"))
    }

    // ---- OpenRouter wire format ----

    @Test fun openRouterRequest() {
        val b = JSONObject(OR.body("I went to the market", "en", "zh", ""))
        assertEquals("google/gemini-2.5-flash-lite", b.getString("model"))
        assertEquals(0, b.getInt("temperature"))
        assertEquals("deny", b.getJSONObject("provider").getString("data_collection"))
        val m = b.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertTrue(m.getJSONObject(0).getString("content").let { it.contains("English") && it.contains("Chinese (Simplified)") && it.contains("only") })
        assertEquals("I went to the market", m.getJSONObject(1).getString("content"))
        assertEquals("openai/gpt-4.1-nano", JSONObject(OR.body("x", "en", "ml", "openai/gpt-4.1-nano")).getString("model"))
        assertFalse("no key in the body", OR.body("x", "en", "ml", "m").contains("Bearer"))
    }

    @Test fun openRouterReply() {
        assertEquals("我去了市场", OR.parse("""{"choices":[{"message":{"role":"assistant","content":" 我去了市场 "}}]}"""))
        assertEquals("quoted", OR.parse("""{"choices":[{"message":{"content":"\"quoted\""}}]}"""))
        assertNull(OR.parse("""{"choices":[]}"""))
        assertNull(OR.parse("""{"error":{"code":401,"message":"No auth"}}"""))
        assertNull(OR.parse("<html>"))
        assertTrue(OR.describe(401)!!.contains("refused the key")); assertTrue(OR.describe(402)!!.contains("credits"))
        assertNull(OR.describe(500))
    }

    // ---- guard, wider languages ----

    @Test fun guardForOtherLanguages() {
        assertTrue(LiveTranslation.sane("I went to the market today", "Fui al mercado hoy", "es"))
        assertFalse("Spanish must be Latin", LiveTranslation.sane("I went to the market today", "我今天去了市场", "es"))
        assertTrue(LiveTranslation.sane("I went to the market today", "मैं आज बाज़ार गया", "hi"))
        assertFalse("Hindi must not come back in English", LiveTranslation.sane("I went to the market today", "I went to market today ok", "hi"))
        assertTrue(LiveTranslation.sane("I went to the market today", "今日市場に行きました", "ja"))
        assertFalse(LiveTranslation.sane("I went to the market today", "mercado mercado mercado mercado", "es"))
    }

    // ---- migration ----

    private fun migrate(saved: Map<String, Any>, usable: Boolean = true) = SettingsMigration.toLiveTranslation(saved) { usable }

    @Test fun migrationToLiveTranslationInto() {
        assertTrue("turned off by hand: Off", migrate(mapOf("live_tr" to false, "live_tr_target" to "zh")).put.isEmpty())
        assertEquals(setOf("live_tr", "live_tr_target"), migrate(mapOf("live_tr" to false, "live_tr_target" to "zh")).remove)
        assertEquals("on, Chinese", mapOf("live_tr_into" to "zh"), migrate(mapOf("live_tr" to true, "live_tr_target" to "zh")).put)
        assertEquals("on, same as notes", mapOf("live_tr_into" to "ml"), migrate(mapOf("live_tr" to true, "tr_target" to "ml")).put)
        assertEquals("on, the other one", mapOf("live_tr_into" to "ml"), migrate(mapOf("live_tr" to true, "live_tr_target" to "auto")).put)
        assertEquals("the other one, writing Malayalam", mapOf("live_tr_into" to "en"),
            migrate(mapOf("live_tr" to true, "live_tr_target" to "auto", "tr_source" to "ml")).put)
        val carried = migrate(mapOf("tr_target" to "zh"))
        assertEquals("untouched + usable: on", mapOf("live_tr_into" to "zh"), carried.put)
        assertEquals(mapOf(SettingsMigration.LIVE_TR_CARRIED to true), carried.state)
        assertTrue("untouched, not usable: Off", migrate(mapOf("tr_target" to "zh"), usable = false).put.isEmpty())
        assertTrue("untouched, notes into Spanish (never live before): Off", migrate(mapOf("tr_target" to "es")).put.isEmpty())
        assertTrue("a saved new value wins", migrate(mapOf("live_tr_into" to "off", "live_tr" to true)).put.isEmpty())
    }

    @Test fun upgradeCarriesAnExplicitChoice() {
        ctx.uiState().edit().putInt("settings_version", 5).commit()
        ctx.getSharedPreferences("settings", 0).edit().putBoolean("live_tr", true).putString("live_tr_target", "ml").commit()
        val p = ctx.settings()
        assertEquals("ml", p[Prefs.LIVE_TR_INTO])
        assertFalse(p.contains("live_tr") || p.contains("live_tr_target"))
        assertFalse("picked by hand: may download", ctx.uiState().getBoolean(SettingsMigration.LIVE_TR_CARRIED, false))
    }
}
