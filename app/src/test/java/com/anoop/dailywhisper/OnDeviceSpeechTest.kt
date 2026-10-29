package com.anoop.dailywhisper

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.RandomAccessFile

/**
 * Speech slot 3, "On this phone": the slot order, the no-internet switch, the speech pack page, the setup offer and the
 * text clean-up, on Robolectric. The native recogniser itself can't run on the JVM; it is checked on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class OnDeviceSpeechTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        for (f in listOf("settings", "state", "secrets")) ctx.getSharedPreferences(f, 0).edit().clear().commit()
        ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        File(ctx.noBackupFilesDir, "speech").deleteRecursively()
        File(ctx.noBackupFilesDir, "speech-guard").deleteRecursively()
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun fakeKey(name: String) = ctx.getSharedPreferences("secrets", 0).edit().putString(name, "placeholder").commit()
    private fun lang(code: String) = ctx.settings().edit().putString(Prefs.DG_LANGUAGE.key, code).commit()
    /** Pretend a pack finished downloading (real files are SHA-256-checked before they're renamed in). */
    private fun install(id: String) {
        val p = SpeechPackCatalog.pack(id)!!
        for (f in p.files) RandomAccessFile(File(SpeechPacks.dir(ctx, id).apply { mkdirs() }, f.local), "rw").use { it.setLength(f.size) }
    }
    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun page(id: String) = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, id)).setup().get()

    // ---- catalog ----

    @Test fun catalogIsPinnedAndSized() {
        val ids = SpeechPackCatalog.PACKS.map { it.id }
        assertEquals(listOf("en", "ml", "global"), ids)
        for (p in SpeechPackCatalog.PACKS) for (f in p.files) {
            assertTrue(f.url, Regex("https://huggingface.co/[\\w.-]+/[\\w.-]+/resolve/[0-9a-f]{40}/[\\w.]+").matches(f.url))
            assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(f.size > 0)
        }
        assertEquals("73 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("en")!!.bytes))
        assertEquals("138 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("ml")!!.bytes))
        assertEquals("366 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("global")!!.bytes))
        assertTrue("sentence packs carry the voice detector", SpeechPackCatalog.PACKS.filter { it.mode == SpeechPackCatalog.Mode.SENTENCES }
            .all { p -> p.files.any { it.local == "silero_vad.onnx" } })
    }

    @Test fun packsForLanguages() {
        fun ids(code: String) = SpeechPackCatalog.packsFor(code).map { it.id }
        assertEquals(listOf("en", "global"), ids("en-AU"))
        assertEquals(listOf("ml", "global"), ids("ml"))
        assertEquals(listOf("global"), ids("fr"))
        assertEquals(listOf("global", "ml", "en"), ids(SpeechSlots.AUTO_SARVAM))
        assertEquals("ml", SpeechPackCatalog.choose(SpeechSlots.AUTO_SARVAM, setOf("en", "ml"))?.id)
        assertEquals("global", SpeechPackCatalog.choose("ml", setOf("global"))?.id)
        assertNull(SpeechPackCatalog.choose("fr", setOf("en", "ml")))
        assertEquals("ml", SpeechPackCatalog.suggestedFor("ml").id)
        assertEquals("en", SpeechPackCatalog.suggestedFor("en_AU").id)
        assertEquals("global", SpeechPackCatalog.suggestedFor("fr").id)
    }

    @Test fun textIsTidied() {
        assertEquals("After early nightfall the lamps would light up.", OnDeviceText.tidy("AFTER EARLY NIGHTFALL THE LAMPS WOULD LIGHT UP", true, english = true))
        assertEquals("i think I'm here", OnDeviceText.tidy("I THINK I'M HERE", false, english = true).replaceFirst("I think", "i think"))
        assertEquals("Is it?", OnDeviceText.tidy("is it?", true))
        assertEquals("ഈ നഗരം.", OnDeviceText.tidy(" ഈ  നഗരം ", true))
        assertEquals("", OnDeviceText.tidy("  ", true))
        assertEquals("i libri", OnDeviceText.tidy("i libri", false)) // Italian "i" stays when it isn't the English pack
    }

    // ---- slots and the internet ----

    @Test fun defaultsAndSettings() {
        val p = ctx.settings()
        assertTrue(p[Prefs.SLOT3_ON]); assertTrue(p[Prefs.OFFLINE_SWITCH])
        assertEquals(2, p[Prefs.OD_THREADS]); assertEquals(20, p[Prefs.OD_MAX_SENTENCE])
        val slot = ctx.speechSlots()[2]
        assertEquals(SpeechProvider.ON_DEVICE, slot.provider); assertEquals(3, slot.number)
        assertTrue("no key needed", slot.hasKey); assertTrue("no consent needed", slot.residencyOk)
        assertTrue(slot.installed.isEmpty())
    }

    @Test fun onlineOrderIsDeepgramSarvamThenPhone() {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("en"); install("en")
        val s = StreamingLiveDictation.setup(ctx, online = true)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM, SpeechProvider.ON_DEVICE), s.plan.slots.map { it.provider })
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM), s.protocols.map { it.provider })
        assertEquals("en", s.device?.pack?.id); assertTrue(s.switchOnDrop); assertFalse(s.plan.offline)
        assertEquals("protocols() unchanged for the online slots", listOf(SpeechProvider.DEEPGRAM, SpeechProvider.SARVAM),
            StreamingLiveDictation.protocols(ctx).map { it.provider })
    }

    @Test fun noInternetGoesStraightToThePhone() {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); fakeKey(SecretStore.SLOT2_API_KEY); lang("ml"); install("ml")
        val s = StreamingLiveDictation.setup(ctx, online = false)
        assertTrue(s.plan.offline)
        assertTrue("no online provider is tried", s.protocols.isEmpty())
        assertEquals("ml", s.device?.pack?.id)
        assertEquals(SpeechPacks.dir(ctx, "ml"), s.device?.dir)
        assertEquals(1300, s.device?.pauseMs)
    }

    @Test fun noInternetAndNoPackSaysWhatToDownload() {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); lang("ml")
        val msg = runCatching { StreamingLiveDictation.setup(ctx, online = false) }.exceptionOrNull()?.message
        assertEquals("No internet. Download Malayalam in Settings → On this phone to dictate offline", msg)
        ctx.settings().edit().putBoolean(Prefs.SLOT3_ON.key, false).commit()
        assertEquals("No internet. Turn on On this phone in Settings to dictate offline",
            runCatching { StreamingLiveDictation.setup(ctx, online = false) }.exceptionOrNull()?.message)
    }

    @Test fun phoneOnlyWorksWithNoKeysAndShowsTheMic() {
        ctx.setUserRegion("nz"); ctx.setDgConsent(""); lang("ml") // nothing online allowed or set up
        assertEquals(View.GONE, Robolectric.buildActivity(MainActivity::class.java).setup().get().findViewById<View>(R.id.mic).visibility)
        install("ml")
        val s = StreamingLiveDictation.setup(ctx, online = true)
        assertTrue(s.protocols.isEmpty()); assertEquals("ml", s.device?.pack?.id)
        assertEquals(View.VISIBLE, Robolectric.buildActivity(MainActivity::class.java).setup().get().findViewById<View>(R.id.mic).visibility)
    }

    @Test fun autoSarvamWithoutSarvamUsesDeepgramThenThePhone() {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); lang(SpeechSlots.AUTO_SARVAM); install("ml")
        val s = StreamingLiveDictation.setup(ctx, online = true)
        assertTrue(s.plan.fellBack)
        assertEquals(listOf(SpeechProvider.DEEPGRAM, SpeechProvider.ON_DEVICE), s.plan.slots.map { it.provider })
        assertTrue(s.protocols.single().url.contains("language=multi"))
        assertEquals("ml", s.device?.pack?.id)
    }

    @Test fun switchSettingIsPassedOn() {
        ctx.setUserRegion("in"); fakeKey(SecretStore.STT_API_KEY); lang("en"); install("en")
        ctx.settings().edit().putBoolean(Prefs.OFFLINE_SWITCH.key, false).commit()
        assertFalse(StreamingLiveDictation.setup(ctx, online = true).switchOnDrop)
    }

    @Test fun crashGuardTurnsThePhoneOffUntilRetried() {
        install("en"); lang("en")
        val g = OnDeviceSpeech.guard(ctx)
        assertNull(g.blocked())
        // A run that never finished (the process died inside the native code) leaves its marker behind.
        File(ctx.noBackupFilesDir, "speech-guard").apply { mkdirs() }.resolve("inflight").writeText("0")
        assertEquals(OpusGuard.Block.CRASHED, OnDeviceSpeech.guard(ctx).blocked())
        assertFalse(ctx.speechSlots()[2].enabled)
        val pg = page("ondevice")
        val t = texts(pg.window.decorView)
        assertTrue(t.toString(), t.any { it.startsWith("On this phone stopped unexpectedly last time") })
        all(pg.window.decorView).first { it is TextView && it.text.startsWith("On this phone stopped unexpectedly") }.performClick()
        ShadowLooper.idleMainLooper()
        assertNull(OnDeviceSpeech.guard(ctx).blocked()); assertTrue(ctx.speechSlots()[2].enabled)
    }

    // ---- Settings ----

    @Test fun mainScreenListsTheThirdProvider() {
        val t = texts(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView)
        assertTrue(t.toString(), t.containsAll(listOf("3", "On this phone", "Needs a speech pack · No internet needed")))
        assertTrue(t.indexOf("Sarvam AI") < t.indexOf("On this phone"))
        install("en")
        assertTrue(texts(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView).contains("On · English"))
    }

    @Test fun onDevicePageHasItsSettings() {
        val t = texts(page("ondevice").window.decorView)
        for (s in listOf("On this phone", "Use On this phone", "Speech packs", "None", "Switch when the internet drops",
            "Processor cores", "2", "Longest sentence", "20 s", "Audio never leaves this phone")) assertTrue("$s on $t", s in t)
        assertFalse("no key on this page", "API key" in t)
    }

    @Test fun packPageOrderDownloadAndDelete() {
        val a = page(SettingsActivity.PAGE_PACKS)
        val t = texts(a.window.decorView)
        // Phone language is English on Robolectric: English suggested first, Malayalam available, every language last.
        val order = listOf("Suggested for this phone", "English", "Available", "Malayalam", "Every language", "All languages (1,600+)")
        assertTrue(t.toString(), t.containsAll(order)); assertEquals(order, order.sortedBy { t.indexOf(it) })
        assertTrue(t.contains("73 MB · Live, word by word")); assertTrue(t.contains("366 MB · A sentence at a time"))
        assertTrue(t.any { it.contains("on Wi-Fi or mobile data") })
        // Download Malayalam: queued on any network (Wi-Fi only is off by default).
        all(a.window.decorView).first { it is TextView && it.contentDescription == "Download Malayalam" }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(SpeechPacks.isQueued(ctx, "ml"))
        val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(texts(a.window.decorView).contains("Waiting to download · 138 MB"))
        // Wi-Fi only changes the job, as for translation models.
        ctx.settings().edit().putBoolean(Prefs.TR_WIFI_ONLY.key, true).commit(); SpeechPacks.sync(ctx)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single().networkType)
        // Once on the phone it moves to "On this phone", with its size and Delete.
        install("ml")
        val b = page(SettingsActivity.PAGE_PACKS)
        val bt = texts(b.window.decorView)
        assertTrue(bt.toString(), bt.contains("On this phone") && bt.indexOf("On this phone") < bt.indexOf("Malayalam"))
        assertTrue(bt.contains("On this phone · 138 MB · A sentence at a time"))
        all(b.window.decorView).first { it is TextView && it.contentDescription == "Delete Malayalam" }.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(SpeechPacks.dir(ctx, "ml").exists()); assertFalse("ml" in SpeechPacks.wanted(ctx))
        assertTrue(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }

    // ---- first-run setup ----

    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun SetupActivity.click(label: String) {
        buttons(window.decorView).first { it.text.toString() == label }.performClick(); ShadowLooper.idleMainLooper()
    }
    private fun SetupActivity.list() = all(window.decorView).filterIsInstance<android.widget.ListView>().single()

    @Test fun setupLanguageListStartsShortThenAll() {
        ctx.uiState().edit().remove(SetupActivity.KEY_DONE).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)).setup().get()
        val l = a.list()
        assertEquals("English (the phone's language) only", listOf("English"), (0 until l.adapter.count).map { l.adapter.getItem(it).toString() })
        assertTrue(texts(a.window.decorView).contains("All languages"))
        val search = all(a.window.decorView).filterIsInstance<android.widget.EditText>().single()
        assertEquals("Search languages", search.hint.toString())
        all(a.window.decorView).first { it is TextView && it !is Button && it.text == "All languages" }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(a.list().adapter.count > 50)
        assertEquals(View.GONE, all(a.window.decorView).first { it is TextView && it !is Button && it.text == "All languages" }.visibility)
    }

    @Test fun setupOffersTheOfflinePackPlainly() {
        ctx.uiState().edit().remove(SetupActivity.KEY_DONE).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)).setup().get()
        // Pick Malayalam as the notes language.
        val search = all(a.window.decorView).filterIsInstance<android.widget.EditText>().single()
        search.setText("Malayalam"); ShadowLooper.idleMainLooper()
        val l = a.list(); l.measure(0, 0); l.layout(0, 0, 1080, 20000)
        l.performItemClick(l.getChildAt(0) ?: l, 0, 0); ShadowLooper.idleMainLooper()
        a.click("Next"); a.click("Next")
        val regions = a.list(); regions.measure(0, 0); regions.layout(0, 0, 1080, 20000)
        val pos = Residency.REGIONS.indexOfFirst { it.code == "in" }
        regions.performItemClick(regions.getChildAt(pos) ?: regions, pos, 0); ShadowLooper.idleMainLooper()
        a.click("Next")
        assertEquals("ml", ctx.settings()[Prefs.TR_SOURCE])
        assertEquals("the notes language becomes the dictation language", "ml", ctx.settings()[Prefs.DG_LANGUAGE])
        while (!texts(a.window.decorView).contains("You can change these later in Settings.")) a.click(buttons(a.window.decorView).last().text.toString())
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.contains("Dictate without internet (optional)"))
        val offer = t.first { it.startsWith("Get Malayalam on this phone") }
        assertTrue(offer, offer.contains("138 MB"))
        val all = t.joinToString("\n")
        for (w in listOf("Sherpa", "ONNX", "IndicConformer", "Zipformer", "Omnilingual", "Deepgram", "Sarvam", "Hugging Face", "VAD"))
            assertFalse("setup shows \"$w\": $all", all.contains(w))
        assertFalse("nothing downloads until tapped", "ml" in SpeechPacks.wanted(ctx))
        a.click("Download")
        assertTrue(SpeechPacks.isQueued(ctx, "ml"))
        assertNotNull(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.firstOrNull { it.service.className.endsWith("SpeechPackDownloadJob") })
        assertTrue(texts(a.window.decorView).contains("Getting it ready in the background. You can see it in Settings."))
    }

    @Test fun setupKeepsAChosenDictationLanguage() {
        lang("en-AU")
        val a = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)).setup().get()
        a.click("Next"); a.click("Next")
        val regions = a.list(); regions.measure(0, 0); regions.layout(0, 0, 1080, 20000)
        regions.performItemClick(regions.getChildAt(0) ?: regions, 0, 0); ShadowLooper.idleMainLooper()
        a.click("Next")
        assertEquals("en-AU", ctx.settings()[Prefs.DG_LANGUAGE])
    }
}
