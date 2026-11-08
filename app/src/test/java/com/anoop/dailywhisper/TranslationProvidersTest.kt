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

/** The translation provider layer: routing by pair, OpenRouter's request/reply, the wider guard, and the v6 migration. */
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

    private fun route(s: String, t: String, online: Boolean = true, prefer: Boolean = true, ready: Set<TranslationProvider> = setOf(OR, OPUS, MLK)) =
        TranslationRegistry.route(s, t, online, prefer, { it in ready })

    // ---- routing ----

    @Test fun routeByPair() {
        assertEquals("English → Chinese: OpenRouter first, then the phone", listOf(OR, OPUS, MLK), route("en", "zh"))
        assertEquals("English → Malayalam: ML Kit can't", listOf(OR, OPUS), route("en", "ml"))
        assertEquals("phone first when OpenRouter isn't preferred", listOf(OPUS, MLK, OR), route("en", "zh", prefer = false))
        assertEquals("offline: only the phone", listOf(OPUS, MLK), route("en", "zh", online = false))
        assertEquals("OpenRouter not set up", listOf(OPUS, MLK), route("en", "zh", ready = setOf(OPUS, MLK)))
        assertEquals("Opus-MT models missing: ML Kit", listOf(MLK), route("en", "zh", ready = setOf(MLK)))
        assertEquals("Malayalam with nothing ready", emptyList<TranslationProvider>(), route("en", "ml", ready = setOf(MLK)))
        assertEquals("a pair only OpenRouter knows", listOf(OR), route("ml", "ko", ready = setOf(OR, OPUS, MLK)).filter { it == OR })
        assertTrue("same language: nothing", route("en", "en").isEmpty())
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
