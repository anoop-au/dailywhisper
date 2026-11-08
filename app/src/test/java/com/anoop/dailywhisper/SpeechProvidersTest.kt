package com.anoop.dailywhisper

import android.content.Context
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The provider layer: the registry and its order, routing and fallback, AssemblyAI's wire format, and the v6 migration. */
@RunWith(RobolectricTestRunner::class)
class SpeechProvidersTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
    }

    private fun fakeKey(name: String) = ctx.getSharedPreferences("secrets", 0).edit().putString(name, "placeholder").commit()
    private fun lang(code: String) = ctx.settings().edit().putString(Prefs.DG_LANGUAGE.key, code).commit()
    private val DG = SpeechProvider.DEEPGRAM
    private val SV = SpeechProvider.SARVAM
    private val AAI = SpeechProvider.ASSEMBLYAI
    private val OD = SpeechProvider.ON_DEVICE

    /** Slots in [order] (all on, keyed and allowed unless listed), the phone last with [packs]. */
    private fun slots(order: List<SpeechProvider> = listOf(DG, SV, AAI), off: Set<SpeechProvider> = emptySet(),
                      noKey: Set<SpeechProvider> = emptySet(), packs: Set<String> = emptySet()) =
        (order + OD).mapIndexed { i, p ->
            SpeechSlots.Slot(i + 1, p, p !in off, p !in noKey, true, if (p == OD) packs else emptySet())
        }
    private fun route(s: List<SpeechSlots.Slot>, lang: String, online: Boolean = true) = SpeechSlots.plan(s, lang, online).slots.map { it.provider }

    // ---- registry ----

    @Test fun registryOrderAndMoveFirst() {
        assertEquals(listOf(DG, SV, AAI, OD), SpeechRegistry.ALL)
        assertEquals(listOf(DG, SV, AAI, OD), SpeechRegistry.ordered(null))
        assertEquals("unknown ids skipped, missing ones appended, phone always last",
            listOf(AAI, DG, SV, OD), SpeechRegistry.ordered("assemblyai, nope,deepgram,ondevice"))
        assertEquals("assemblyai,deepgram,sarvam", SpeechRegistry.moveFirst(null, AAI))
        assertEquals("sarvam,assemblyai,deepgram", SpeechRegistry.moveFirst("assemblyai,deepgram", SV))
        assertEquals("ids are unique", SpeechRegistry.ALL.size, SpeechRegistry.ALL.map { it.id }.toSet().size)
        assertTrue("every online provider has its own key", SpeechRegistry.online.all { it.keyName != null } &&
            SpeechRegistry.online.map { it.keyName }.toSet().size == SpeechRegistry.online.size)
    }

    @Test fun everyListedLanguageHasAProviderAndLabelsAreProviderFree() {
        for ((code, name) in SpeechSlots.LANGUAGES) {
            assertTrue(code, SpeechRegistry.online.any { it.supports(code) })
            assertFalse(name, listOf("Deepgram", "Sarvam", "AssemblyAI").any { name.contains(it) })
        }
        assertEquals(SpeechSlots.LANGUAGES.size, SpeechSlots.LANGUAGES.map { it.first }.toSet().size)
        assertTrue(SpeechSlots.LANGUAGES.any { it.first == "ml" })
    }

    // ---- routing and fallback ----

    @Test fun firstEnabledKeyedProviderInTheUsersOrderThenThePhone() {
        assertEquals(listOf(DG, SV, AAI), route(slots(), "en"))
        assertEquals("user put AssemblyAI first", listOf(AAI, DG, SV), route(slots(listOf(AAI, DG, SV)), "en"))
        assertEquals("no Deepgram key: skipped", listOf(SV, AAI), route(slots(noKey = setOf(DG)), "en"))
        assertEquals("switched off: skipped", listOf(DG, AAI), route(slots(off = setOf(SV)), "en"))
        assertEquals("pack on the phone: tried last", listOf(DG, SV, AAI, OD), route(slots(packs = setOf("en")), "en"))
    }

    @Test fun languageDecidesWhoCanDictate() {
        assertEquals("Malayalam: Sarvam AI only, then the phone", listOf(SV, OD), route(slots(packs = setOf("ml")), "ml"))
        assertEquals("Mandarin: Deepgram and AssemblyAI", listOf(DG, AAI), route(slots(), "zh-CN"))
        assertEquals("Mandarin without Deepgram: AssemblyAI", listOf(AAI), route(slots(noKey = setOf(DG)), "zh-CN"))
        assertEquals("Cantonese → AssemblyAI yue", listOf(DG, AAI), route(slots(), "zh-HK"))
    }

    @Test fun phoneIsOnlyTheFallback() {
        assertEquals("no internet: only the phone", listOf(OD), route(slots(packs = setOf("ml")), "ml", online = false))
        assertTrue("no internet, no pack: nothing", route(slots(), "ml", online = false).isEmpty())
        assertEquals("no online provider can (Sarvam off): the phone", listOf(OD), route(slots(off = setOf(SV), packs = setOf("ml")), "ml"))
        assertEquals("even when the user's order lists it first", OD, route(slots(listOf(AAI, DG, SV), packs = setOf("en")), "en").last())
    }

    @Test fun autoIndianFallsBackToTheFirstMultiProvider() {
        val p = SpeechSlots.plan(slots(off = setOf(SV)), SpeechSlots.AUTO_SARVAM)
        assertTrue(p.fellBack); assertEquals("multi", p.language); assertEquals(listOf(DG, AAI), p.slots.map { it.provider })
        val q = SpeechSlots.plan(slots(off = setOf(SV), noKey = setOf(DG)), SpeechSlots.AUTO_SARVAM)
        assertEquals("Deepgram unusable too: AssemblyAI with any language", listOf(AAI), q.slots.map { it.provider })
        assertEquals("Sarvam AI is off, using AssemblyAI", SpeechSlots.autoFallbackNote(q.slots.first().provider))
        val r = SpeechSlots.plan(slots(), SpeechSlots.AUTO_SARVAM)
        assertFalse("Sarvam AI usable: no fallback", r.fellBack); assertEquals(listOf(SV), r.slots.map { it.provider })
    }

    @Test fun whyNoneNamesTheProvidersPage() {
        val msg = SpeechSlots.whyNone(slots(noKey = setOf(DG, SV, AAI)), "zh-CN")
        assertEquals("Add your Deepgram API key in Settings → Providers → Deepgram to dictate Chinese (Mandarin, Simplified)", msg)
    }

    // ---- AssemblyAI ----

    private fun q(url: String) = URLDecoder.decode(url, "UTF-8")

    @Test fun assemblyAiUrl() {
        val zh = AssemblyAiProvider.url("universal-3-6-pro", "zh-CN", 1300, "us")!!
        assertTrue(zh, zh.startsWith("wss://streaming.us.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&speech_model=universal-3-6-pro"))
        assertTrue(zh, zh.contains("format_turns=true") && zh.contains("max_turn_silence=1300") && q(zh).contains("language_codes=[\"zh\"]"))
        assertTrue(q(AssemblyAiProvider.url("universal-3-6-pro", "zh-HK", 1300, "eu")!!).let { it.contains("streaming.eu.") && it.contains("[\"yue\"]") })
        assertTrue(q(AssemblyAiProvider.url("universal-3-6-pro", "en-AU", 1300, "us")!!).contains("[\"en\"]"))
        assertFalse("multi: no steering", AssemblyAiProvider.url("universal-3-6-pro", "multi", 1300, "us")!!.contains("language_codes"))
        assertTrue("pause clamped", AssemblyAiProvider.url("universal-3-6-pro", "en", 99999, "us")!!.contains("max_turn_silence=5000"))
        assertNull("no Malayalam", AssemblyAiProvider.url("universal-3-6-pro", "ml", 1300, "us"))
        assertNull("English-only model, Chinese", AssemblyAiProvider.url("universal-streaming-english", "zh-CN", 1300, "us"))
        assertNull("3.5 Pro has no Korean", AssemblyAiProvider.url("universal-3-5-pro", "ko", 1300, "us"))
        assertFalse("Universal Streaming takes no language_codes",
            AssemblyAiProvider.url("universal-streaming-multilingual", "fr", 1300, "us")!!.contains("language_codes"))
        assertFalse(AAI.supports("ml")); assertFalse(AAI.supports(SpeechSlots.AUTO_SARVAM)); assertTrue(AAI.supports("multi"))
    }

    @Test fun assemblyAiProtocolAndMessages() {
        val p = AAI.protocol("k-123", "en", 1300, SttOptions())!!
        assertEquals("raw key, no Bearer", mapOf("Authorization" to "k-123"), p.headers)
        assertEquals("""{"type":"Terminate"}""", p.endMessage)
        assertTrue(p.waitForReady)
        assertTrue(p.isReady("""{"type":"Begin","id":"x","expires_at":1}"""))
        assertFalse(p.isReady("""{"type":"Turn","transcript":"hi"}"""))
        assertEquals(TranscriptParser.Update("hello wor", false), p.parser.parse("""{"type":"Turn","transcript":"hello wor","end_of_turn":false}"""))
        assertFalse("end of turn, not formatted yet: still a partial",
            p.parser.parse("""{"type":"Turn","transcript":"hello world","end_of_turn":true,"turn_is_formatted":false}""")!!.isFinal)
        assertEquals(TranscriptParser.Update("Hello world.", true),
            p.parser.parse("""{"type":"Turn","transcript":"Hello world.","end_of_turn":true,"turn_is_formatted":true}"""))
        assertNull(p.parser.parse("""{"type":"Termination","audio_duration_seconds":3}"""))
        assertNull(p.parser.parse("not json"))
        assertEquals(0 to "Invalid API key", p.fatalError("""{"type":"Error","error":"Invalid API key"}"""))
        assertNull(p.fatalError("""{"type":"Begin"}"""))
        assertTrue("EU option", AAI.protocol("k", "en", 1300, SttOptions(mapOf(AssemblyAiProvider.KEY_REGION to "eu")))!!.url.contains(".eu."))
        assertNull(AAI.protocol("k", "ml", 1300, SttOptions()))
    }

    @Test fun assemblyAiResidency() {
        assertEquals("the US", AAI.place(SttOptions()))
        assertFalse(AAI.inRegion("au", SttOptions()))
        assertTrue(AAI.inRegion("us", SttOptions()))
        assertTrue(AAI.inRegion("eu", SttOptions(mapOf(AssemblyAiProvider.KEY_REGION to "eu"))))
        ctx.setUserRegion("au"); fakeKey(AssemblyAiProvider.keyName); lang("zh-CN")
        assertFalse("outside the US: needs an OK first", ctx.providerAllowed(AAI))
        assertTrue(runCatching { StreamingLiveDictation.protocols(ctx) }.exceptionOrNull()!!.message!!.contains("allow AssemblyAI"))
        ctx.setProviderConsent(AAI, "the US")
        assertEquals(listOf(AAI), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    // ---- end to end through the real settings ----

    @Test fun protocolsFollowTheUsersOrderAndFallBack() {
        ctx.setUserRegion("us"); fakeKey(SecretStore.STT_API_KEY); fakeKey(AssemblyAiProvider.keyName); lang("zh-CN")
        assertEquals(listOf(DG, AAI), StreamingLiveDictation.protocols(ctx).map { it.provider })
        ctx.settings().edit().putString(Prefs.STT_ORDER.key, "assemblyai,deepgram,sarvam").commit()
        val ps = StreamingLiveDictation.protocols(ctx)
        assertEquals(listOf(AAI, DG), ps.map { it.provider })
        assertTrue(ps[0].url.contains("streaming.us.assemblyai.com") && !ps[0].url.contains("placeholder"))
        assertTrue(ps[1].url.contains("language=zh-CN"))
        ctx.settings().edit().putBoolean(Prefs.USE_ASSEMBLYAI.key, false).commit()
        assertEquals(listOf(DG), StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    // ---- migration ----

    @Test fun migrationMapsSlotSwitches() {
        val c = SettingsMigration.toProviders(mapOf("slot1_on" to false, "slot2_on" to true, "slot3_on" to false, "od_first" to true, "pause_ms" to 900))
        assertEquals(mapOf("use_deepgram" to false, "use_sarvam" to true, "use_ondevice" to false), c.put)
        assertEquals(setOf("slot1_on", "slot2_on", "slot3_on", "od_first"), c.remove)
        val keep = SettingsMigration.toProviders(mapOf("slot1_on" to true, "use_deepgram" to false))
        assertTrue("a newer switch wins", keep.put.isEmpty()); assertEquals(setOf("slot1_on"), keep.remove)
        assertTrue(SettingsMigration.toProviders(emptyMap<String, Any>()).let { it.put.isEmpty() && it.remove.isEmpty() })
    }

    @Test fun migrationRunsOnceOnUpgrade() {
        ctx.uiState().edit().putInt("settings_version", 5).commit()
        ctx.getSharedPreferences("settings", 0).edit().putBoolean("slot1_on", false).putBoolean("slot3_on", false)
            .putBoolean("od_first", true).putString(Prefs.DG_LANGUAGE.key, "ml").commit()
        val p = ctx.settings()
        assertFalse(p[Prefs.USE_DEEPGRAM]); assertTrue(p[Prefs.USE_SARVAM]); assertFalse(p[Prefs.USE_ONDEVICE])
        assertFalse(p.contains("slot1_on") || p.contains("slot3_on") || p.contains("od_first"))
        assertEquals("ml", p[Prefs.DG_LANGUAGE])
        assertEquals(listOf(false, true, true, false), ctx.speechSlots().map { it.enabled })
    }

    @Test fun upgradeKeepsSarvamOnlyLanguages() {
        for (code in listOf("ml", SpeechSlots.AUTO_SARVAM, "multi", "zh-CN")) {
            clean()
            ctx.uiState().edit().putInt("settings_version", 5).commit()
            ctx.getSharedPreferences("settings", 0).edit().putString(Prefs.DG_LANGUAGE.key, code).commit()
            assertEquals(code, ctx.settings()[Prefs.DG_LANGUAGE])
        }
    }
}
