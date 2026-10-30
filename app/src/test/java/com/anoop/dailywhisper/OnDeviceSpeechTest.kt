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
        assertEquals(listOf("en", "ml", "global", "en-recheck", "en-recheck-small", "punct-en"), ids)
        for (p in SpeechPackCatalog.PACKS) for (f in p.files) {
            assertTrue(f.url, Regex("https://huggingface.co/[\\w.-]+/[\\w.-]+/resolve/[0-9a-f]{40}/[\\w.]+").matches(f.url))
            assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(f.size > 0)
        }
        assertEquals("73 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("en")!!.bytes))
        assertEquals("138 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("ml")!!.bytes))
        assertEquals("366 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("global")!!.bytes))
        assertEquals("662 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("en-recheck")!!.bytes))
        assertEquals("8 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("punct-en")!!.bytes))
        assertEquals("137 MB", SpeechPackCatalog.mb(SpeechPackCatalog.pack("en-recheck-small")!!.bytes))
        assertTrue("every pack says what it's for", SpeechPackCatalog.PACKS.all { it.about.isNotBlank() })
        assertTrue("sentence packs carry the voice detector", SpeechPackCatalog.PACKS.filter { it.mode == SpeechPackCatalog.Mode.SENTENCES && it.kind != SpeechPackCatalog.Kind.PUNCTUATION }
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
        // A sentence Sharper English cut at a pause can end with a comma: one full stop, not ",."
        assertEquals("Behind Friday the thirteenth.", OnDeviceText.tidy("Behind Friday the thirteenth,", true, english = true))
        assertEquals("Discover the legend.", OnDeviceText.tidy("Discover the legend; ", true, english = true))
        assertEquals("", OnDeviceText.tidy(",", true, english = true))
        assertEquals("this halloween,", OnDeviceText.tidy("this halloween,", false, english = true)) // live words keep it
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
            "Processor cores", "2", "Longest sentence", "20 s", "Audio never leaves this phone", "Use first", "Quiet speech boost",
            "10×", "English search width", "4", "Recheck each sentence", "Recheck pause", "2 s", "English punctuation")) assertTrue("$s on $t", s in t)
        assertFalse("no key on this page", "API key" in t)
    }

    @Test fun packPageOrderDownloadAndDelete() {
        val a = page(SettingsActivity.PAGE_PACKS)
        val t = texts(a.window.decorView)
        // Phone language is English on Robolectric: English suggested first, Malayalam available, every language last.
        val order = listOf("Suggested for this phone", "English", "Available", "Malayalam", "Every language", "All languages (1,600+)")
        assertTrue(t.toString(), t.containsAll(order)); assertEquals(order, order.sortedBy { t.indexOf(it) })
        // One short line each: what it does · size.
        assertTrue(t.toString(), t.contains("Live, word by word · 73 MB") && t.contains("A sentence at a time · 138 MB") &&
            t.contains("Any other language, less accurate · 366 MB"))
        // Sharper English is one choice on the English row; punctuation isn't listed; one footer line.
        assertTrue(t.contains("Sharper English") && t.indexOf("English") < t.indexOf("Sharper English") && t.indexOf("Sharper English") < t.indexOf("Available"))
        for (gone in listOf("Better English (optional)", "Sharper English (small)", "English punctuation", "Use"))
            assertFalse("no \"$gone\" on the page", t.contains(gone))
        assertTrue(t.contains("Your voice never leaves this phone.")); assertFalse(t.any { it.contains("Tap Use") || it.contains("mobile data") })
        // Download Malayalam: queued on any network (Wi-Fi only is off by default).
        all(a.window.decorView).first { it is TextView && it.contentDescription == "Download Malayalam" }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(SpeechPacks.isQueued(ctx, "ml"))
        val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(texts(a.window.decorView).any { it.endsWith("Waiting to download · 138 MB") })
        // Wi-Fi only changes the job, as for translation models.
        ctx.settings().edit().putBoolean(Prefs.TR_WIFI_ONLY.key, true).commit(); SpeechPacks.sync(ctx)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single().networkType)
        // Once on the phone it moves to "On this phone", with its size and Delete.
        install("ml")
        val b = page(SettingsActivity.PAGE_PACKS)
        val bt = texts(b.window.decorView)
        assertTrue(bt.toString(), bt.contains("On this phone") && bt.indexOf("On this phone") < bt.indexOf("Malayalam"))
        assertTrue(bt.toString(), bt.contains("A sentence at a time · 138 MB · Tap to use"))
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

    // ---- picking a downloaded pack (Malayalam), engines, add-ons ----

    @Test fun downloadedMalayalamShowsUpInTheLanguageList() {
        val before = SpeechSlots.languageChoices(ctx.speechSlots())
        assertEquals(SpeechSlots.LANGUAGES, before)
        install("ml")
        val after = SpeechSlots.languageChoices(ctx.speechSlots())
        assertEquals("right under the two automatic choices", "ml" to "Malayalam (Sarvam AI, On this phone)", after[2])
        assertEquals("nothing lost or doubled", SpeechSlots.LANGUAGES.map { it.first }.toSet(), after.map { it.first }.toSet())
        assertEquals(SpeechSlots.LANGUAGES.size, after.size)
        assertEquals("Uses On this phone", SpeechSlots.languageHints(ctx.speechSlots())["ml"])
    }

    @Test fun malayalamUsesSarvamWhenItCanThenThePhone() {
        ctx.setUserRegion("in"); install("ml"); lang("ml")
        assertEquals("no Sarvam key: the pack dictates", "Uses On this phone", SpeechSlots.engineLine(ctx.speechSlots(), "ml"))
        val s0 = StreamingLiveDictation.setup(ctx, online = true)
        assertTrue(s0.protocols.isEmpty()); assertEquals("ml", s0.device?.pack?.id)
        fakeKey(SecretStore.SLOT2_API_KEY)
        assertEquals("Uses Sarvam AI, then On this phone", SpeechSlots.engineLine(ctx.speechSlots(), "ml"))
        val s1 = StreamingLiveDictation.setup(ctx, online = true)
        assertEquals(listOf(SpeechProvider.SARVAM), s1.protocols.map { it.provider }); assertEquals("ml", s1.device?.pack?.id)
        // "Use first": the pack even when Sarvam AI could.
        ctx.settings().edit().putBoolean(Prefs.OD_FIRST.key, true).commit()
        assertEquals("Uses On this phone, then Sarvam AI", SpeechSlots.engineLine(ctx.speechSlots(), "ml", true))
        val s2 = StreamingLiveDictation.setup(ctx, online = true)
        assertTrue(s2.protocols.isEmpty()); assertEquals("ml", s2.device?.pack?.id)
    }

    @Test fun languageRowSaysWhichEngineIsUsed() {
        ctx.setUserRegion("in"); install("ml"); lang("ml")
        val t = texts(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView)
        assertTrue(t.toString(), t.contains("Malayalam (Sarvam AI, On this phone)") && t.contains("Uses On this phone"))
    }

    @Test fun tappingAPackRowPicksTheLanguageAndTagsItInUse() {
        install("ml"); lang("en")
        val a = page(SettingsActivity.PAGE_PACKS)
        fun inUse() = all(a.window.decorView).filter { it is TextView && it.text == "In use" && it.isShown }
        assertTrue("nothing in use for English without the English pack", inUse().isEmpty())
        val row = all(a.window.decorView).first { it.contentDescription == "Use Malayalam for dictation" }
        row.performClick(); ShadowLooper.idleMainLooper()
        assertEquals("ml", ctx.settings()[Prefs.DG_LANGUAGE])
        assertEquals("tagged In use", 1, inUse().size)
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.contains("A sentence at a time · 138 MB"))
        assertFalse(t.any { it.contains("Dictation language") || it.contains("Tap to use") })
        assertNull("no longer tappable", row.contentDescription); assertFalse(row.isClickable)
    }

    @Test fun addOnsNeedEnglishAndAreNeverChosenAlone() {
        assertEquals(listOf("en", "global"), SpeechPackCatalog.packsFor("en").map { it.id })
        assertNull(SpeechPackCatalog.choose("en", setOf("en-recheck", "punct-en")))
        val en = SpeechPackCatalog.pack("en")!!
        assertEquals(listOf("en-recheck", "punct-en"), SpeechPackCatalog.addons(en, setOf("en", "en-recheck", "punct-en")).map { it.id })
        // Choosing Best on the English row downloads it with the English pack, and no punctuation pack.
        val a = page(SettingsActivity.PAGE_PACKS)
        pickSharper(a, 2)
        assertTrue(SpeechPacks.isQueued(ctx, "en") && SpeechPacks.isQueued(ctx, "en-recheck"))
        assertFalse("punctuation isn't needed with Sharper English", "punct-en" in SpeechPacks.wanted(ctx))
        assertEquals("en-recheck", SpeechPacks.sharper(ctx))
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.contains("Best") && t.contains("Waiting to download · 662 MB"))
    }

    /** Tap the English row's Sharper English choice and pick [item] (0 Off, 1 Small, 2 Best). */
    private fun pickSharper(a: SettingsActivity, item: Int) {
        all(a.window.decorView).first { it.contentDescription?.startsWith("Sharper English: ") == true }.performClick()
        ShadowLooper.idleMainLooper()
        org.robolectric.Shadows.shadowOf(ShadowDialog.getLatestDialog() as android.app.AlertDialog).clickOnItem(item)
        ShadowLooper.idleMainLooper()
    }
    private fun confirm() {
        (ShadowDialog.getLatestDialog() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
    }

    @Test fun sharperSwitchPlans() {
        val s = SpeechPackCatalog.RECHECK_SMALL; val b = SpeechPackCatalog.RECHECK
        assertNull(SpeechPackCatalog.sharper(setOf("en")))
        assertEquals("both (older installs): Best", b, SpeechPackCatalog.sharper(setOf("en", s, b)))
        assertEquals("unless Best is on its way out", s, SpeechPackCatalog.sharper(setOf(s, b), setOf(b)))
        var p = SpeechPackCatalog.sharperSwitch(b, setOf("en", s), emptySet())
        assertEquals(b, p.download); assertEquals(emptySet<String>(), p.deleteNow); assertEquals(setOf(s), p.deleteWhenReady)
        p = SpeechPackCatalog.sharperSwitch(s, setOf("en", s, b), emptySet())
        assertNull("already here", p.download); assertEquals(setOf(b), p.deleteNow)
        p = SpeechPackCatalog.sharperSwitch(s, setOf("en"), setOf(b))
        assertEquals(s, p.download); assertEquals("an unfinished download stops at once", setOf(b), p.deleteNow)
        p = SpeechPackCatalog.sharperSwitch(null, setOf("en", s, b), emptySet())
        assertNull(p.download); assertEquals(setOf(s, b), p.deleteNow)
        assertTrue(SpeechPackCatalog.wantsPunct(true, false, true, true)); assertFalse(SpeechPackCatalog.wantsPunct(true, true, true, true))
        assertTrue("recheck off: punctuation still helps", SpeechPackCatalog.wantsPunct(true, true, true, false))
        assertFalse(SpeechPackCatalog.wantsPunct(false, false, true, true)); assertFalse(SpeechPackCatalog.wantsPunct(true, false, false, true))
        assertEquals(listOf("en", "ml", "global"), SpeechPackCatalog.LISTED.map { it.id })
    }

    @Test fun switchingSharperSizesDeletesTheOtherAfterAsking() {
        lang("en"); install("en"); install("en-recheck-small"); ctx.uiState().edit().putBoolean(SpeechPacks.KEY_SHARPER_ASKED, true).commit()
        val a = page(SettingsActivity.PAGE_PACKS)
        assertTrue(texts(a.window.decorView).contains("Small"))
        pickSharper(a, 2)
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertEquals("Downloads Best (662 MB), then deletes Small (137 MB).", org.robolectric.Shadows.shadowOf(d).message.toString())
        confirm()
        assertTrue(SpeechPacks.isQueued(ctx, "en-recheck")); assertEquals("en-recheck", SpeechPacks.sharper(ctx))
        assertTrue("Small stays until Best is here", SpeechPacks.isReady(ctx, SpeechPackCatalog.pack("en-recheck-small")!!))
        assertEquals("and is still used meanwhile", "en-recheck-small", OnDeviceSpeech.choice(ctx, "en")!!.recheck?.name)
        install("en-recheck"); SpeechPacks.tidy(ctx)
        assertFalse("then it's deleted", SpeechPacks.dir(ctx, "en-recheck-small").exists())
        // Off asks, then deletes Best; the punctuation pack comes back for English.
        val b = page(SettingsActivity.PAGE_PACKS)
        pickSharper(b, 0)
        assertEquals("Turn off Sharper English?", org.robolectric.Shadows.shadowOf(ShadowDialog.getLatestDialog() as android.app.AlertDialog).title.toString())
        confirm()
        assertFalse(SpeechPacks.dir(ctx, "en-recheck").exists()); assertNull(SpeechPacks.sharper(ctx))
        assertTrue(SpeechPacks.isQueued(ctx, "punct-en"))
        assertTrue(texts(b.window.decorView).contains("Off"))
    }

    @Test fun bothSharperPacksFromBeforeStayAndBestIsChosen() {
        lang("en"); install("en"); install("en-recheck-small"); install("en-recheck"); install("punct-en")
        ctx.uiState().edit().putBoolean(SpeechPacks.KEY_SHARPER_ASKED, true).commit()
        val t = texts(page(SettingsActivity.PAGE_PACKS).window.decorView)
        assertTrue(t.toString(), t.contains("Best") && t.contains("Fewest mistakes · 662 MB"))
        assertTrue("neither is deleted on its own", SpeechPacks.dir(ctx, "en-recheck-small").exists() && SpeechPacks.dir(ctx, "en-recheck").exists())
        assertEquals("en-recheck", OnDeviceSpeech.choice(ctx, "en")!!.recheck?.name)
        assertFalse("punctuation removed: Sharper English is on", SpeechPacks.dir(ctx, "punct-en").exists())
    }

    @Test fun punctuationComesWithEnglishWhenSharperIsOff() {
        lang("en")
        val a = page(SettingsActivity.PAGE_PACKS)
        all(a.window.decorView).first { it is TextView && it.contentDescription == "Download English" }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(SpeechPacks.isQueued(ctx, "en") && SpeechPacks.isQueued(ctx, "punct-en"))
        // A phone that has English but no punctuation pack catches up when the page opens.
        SpeechPacks.delete(ctx, "punct-en"); assertTrue("delete re-asks while English is here", "punct-en" in SpeechPacks.wanted(ctx))
        ctx.settings().edit().putBoolean(Prefs.OD_PUNCT.key, false).commit()
        SpeechPacks.delete(ctx, "punct-en"); assertFalse("not with English punctuation off", "punct-en" in SpeechPacks.wanted(ctx))
    }

    @Test fun choiceWiresAddOnsAndSettings() {
        lang("en"); install("en")
        var ch = OnDeviceSpeech.choice(ctx, "en")!!
        assertNull(ch.recheck); assertNull(ch.punct); assertEquals(4, ch.beam); assertEquals(10, ch.boost)
        assertEquals("recheck pause is its own, longer setting", 2000, ch.recheckPauseMs); assertEquals(1300, ch.pauseMs)
        install("punct-en")
        ch = OnDeviceSpeech.choice(ctx, "en")!!
        assertNotNull(ch.punct); assertNull(ch.recheck)
        install("en-recheck")
        ch = OnDeviceSpeech.choice(ctx, "en")!!
        assertNotNull("Sharper English rechecks", ch.recheck); assertNull("and writes its own punctuation", ch.punct)
        ctx.settings().edit().putBoolean(Prefs.OD_RECHECK.key, false).putInt(Prefs.OD_BEAM.key, 1).putInt(Prefs.OD_BOOST.key, 1).commit()
        ch = OnDeviceSpeech.choice(ctx, "en")!!
        assertNull(ch.recheck); assertNotNull(ch.punct); assertEquals(1, ch.beam); assertEquals(1, ch.boost)
        assertNull("Malayalam gets no English add-ons", run { install("ml"); OnDeviceSpeech.choice(ctx, "ml")!!.punct })
    }

    @Test fun quietSpeechIsBoostedWithoutClipping() {
        val g = AutoGain(10f)
        fun chunk(level: Float) = FloatArray(1600) { i -> level * kotlin.math.sin(i / 5.0).toFloat() }
        var out = FloatArray(0)
        repeat(100) { out = g.apply(chunk(0.02f)) } // 10 s of quiet speech
        assertEquals("up to the limit (0.02 × 10)", 0.2f, out.maxOf { kotlin.math.abs(it) }, 0.01f)
        out = g.apply(chunk(0.9f))
        assertTrue("a loud word right after is never clipped", out.maxOf { kotlin.math.abs(it) } <= 0.99f)
        repeat(30) { out = g.apply(chunk(0.2f)) }
        assertTrue("normal speech is left nearly alone", out.maxOf { kotlin.math.abs(it) } < 0.6f)
        val off = chunk(0.02f).copyOf()
        assertEquals("1× is off", off.toList(), AutoGain(1f).apply(off.copyOf()).toList())
    }

    @Test fun engineNotes() {
        assertEquals("Using On this phone · Malayalam", SpeechSlots.engineNote(SpeechProvider.ON_DEVICE, "Malayalam"))
        assertEquals("Using Sarvam AI", SpeechSlots.engineNote(SpeechProvider.SARVAM))
    }

    @Test fun smallSharperEnglishIsUsedUnlessTheBigOneIsThere() {
        lang("en"); install("en"); install("en-recheck-small")
        assertEquals("en-recheck-small", OnDeviceSpeech.choice(ctx, "en")!!.recheck?.name)
        install("en-recheck")
        assertEquals("both on the phone: the most accurate", "en-recheck", OnDeviceSpeech.choice(ctx, "en")!!.recheck?.name)
        ctx.settings().edit().putInt(Prefs.OD_RECHECK_PAUSE_MS.key, 3000).commit()
        assertEquals(3000, OnDeviceSpeech.choice(ctx, "en")!!.recheckPauseMs)
    }

    @Test fun packPageAsksOnceForSharperEnglish() {
        val a0 = page(SettingsActivity.PAGE_PACKS)
        assertFalse("not before English is on the phone", texts(a0.window.decorView).contains("Get Sharper English?"))
        install("en")
        val a = page(SettingsActivity.PAGE_PACKS)
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.containsAll(listOf("Get Sharper English?", "No thanks", "Small · 137 MB", "Best · 662 MB")))
        assertTrue("right under English", t.indexOf("English") < t.indexOf("Get Sharper English?"))
        assertFalse("in place of the choice until answered", t.contains("Sharper English"))
        all(a.window.decorView).first { it is TextView && it.contentDescription == "Small · 137 MB" }.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(SpeechPacks.isQueued(ctx, "en-recheck-small"))
        val after = texts(a.window.decorView)
        assertFalse(after.contains("Get Sharper English?")); assertTrue(after.toString(), after.contains("Sharper English") && after.contains("Small"))
        // Asked once: even with the download cancelled it doesn't come back.
        SpeechPacks.delete(ctx, "en-recheck-small")
        assertFalse(texts(page(SettingsActivity.PAGE_PACKS).window.decorView).contains("Get Sharper English?"))
    }

    @Test fun noThanksHidesTheQuestion() {
        install("en")
        val a = page(SettingsActivity.PAGE_PACKS)
        all(a.window.decorView).first { it is TextView && it.contentDescription == "No thanks" }.performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(texts(a.window.decorView).contains("Get Sharper English?"))
        assertFalse(SpeechPacks.hasSharper(ctx))
        assertFalse(texts(page(SettingsActivity.PAGE_PACKS).window.decorView).contains("Get Sharper English?"))
    }

    @Test fun setupOffersSharperEnglishPlainly() {
        ctx.uiState().edit().remove(SetupActivity.KEY_DONE).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)).setup().get()
        val search = all(a.window.decorView).filterIsInstance<android.widget.EditText>().single()
        search.setText("English"); ShadowLooper.idleMainLooper()
        val l = a.list(); l.measure(0, 0); l.layout(0, 0, 1080, 20000)
        l.performItemClick(l.getChildAt(0) ?: l, 0, 0); ShadowLooper.idleMainLooper()
        a.click("Next"); a.click("Next")
        val regions = a.list(); regions.measure(0, 0); regions.layout(0, 0, 1080, 20000)
        val pos = Residency.REGIONS.indexOfFirst { it.code == "in" }
        regions.performItemClick(regions.getChildAt(pos) ?: regions, pos, 0); ShadowLooper.idleMainLooper()
        a.click("Next")
        assertEquals("en", ctx.settings()[Prefs.TR_SOURCE].substringBefore('-'))
        var guard = 0
        while (guard++ < 12 && !texts(a.window.decorView).contains("You can change these later in Settings.")) a.click(buttons(a.window.decorView).last().text.toString())
        val t = texts(a.window.decorView)
        assertTrue(t.toString(), t.contains("Better English on this phone (optional)"))
        val offer = t.first { it.startsWith("Fewer mistakes when you speak English") }
        assertTrue(offer, offer.contains("137 MB") && offer.contains("662 MB"))
        val all = t.joinToString("\n")
        for (w in listOf("Parakeet", "NVIDIA", "Sherpa", "ONNX", "VAD", "Hugging Face", "two-pass"))
            assertFalse("setup shows \"$w\": $all", all.contains(w))
        assertFalse("nothing downloads until tapped", SpeechPacks.hasSharper(ctx))
        a.click("Small · 137 MB")
        assertTrue("with the English pack it needs", SpeechPacks.isQueued(ctx, "en-recheck-small") && SpeechPacks.isQueued(ctx, "en"))
        assertFalse("not asked again on the pack page", SpeechPacks.askSharper(ctx))
    }
}
