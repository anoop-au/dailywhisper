package com.anoop.dailywhisper

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration

/** The first-run flow driven through the real activities on Robolectric (Android framework on the JVM). */
@RunWith(RobolectricTestRunner::class)
class FirstRunFlowTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().commit()
        File(ctx.filesDir, "opus").deleteRecursively()
    }

    private fun texts(v: View): List<String> = when (v) {
        is Button -> emptyList()
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun buttons(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun lists(v: View): List<ListView> = when (v) {
        is ListView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { lists(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun SetupActivity.root(): View = window.decorView
    private fun SetupActivity.screen() = texts(root())
    private fun assertNoTechNames(screen: List<String>) {
        val all = screen.joinToString("\n")
        for (w in listOf("Deepgram", "Sarvam", "deepgram.com", "Opus", "ML Kit", " MB")) assertFalse("setup shows \"$w\": $all", all.contains(w))
    }
    private fun SetupActivity.click(label: String) {
        val b = buttons(root()).firstOrNull { it.text.toString() == label } ?: error("No \"$label\" button on ${screen()}")
        assertTrue("\"$label\" enabled", b.isEnabled)
        b.performClick(); ShadowLooper.idleMainLooper()
    }
    private fun SetupActivity.tap(label: String) {
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        all(root()).first { it is TextView && it !is Button && it.text.toString() == label && it.hasOnClickListeners() }.performClick()
        ShadowLooper.idleMainLooper()
    }
    private fun SetupActivity.pick(name: String) {
        val list = lists(root()).single()
        list.measure(0, 0); list.layout(0, 0, 1080, 20000)
        val adapter = list.adapter
        val pos = (0 until adapter.count).first { adapter.getItem(it).toString() == name }
        list.performItemClick(list.getChildAt(pos - list.firstVisiblePosition) ?: list, pos, adapter.getItemId(pos))
        ShadowLooper.idleMainLooper()
    }

    @Test fun newInstallOpensSetupOnce() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(SetupActivity::class.java.name, shadowOf(main).nextStartedActivity?.component?.className)
        ctx.getSharedPreferences("state", 0).edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        val afterSetup = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("mode chooser after setup", ModeChooserActivity::class.java.name,
            shadowOf(afterSetup).nextStartedActivity?.component?.className)
        ctx.getSharedPreferences("state", 0).edit().putBoolean(WritingMode.KEY_CHOSEN, true).commit()
        val ready = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("setup and chooser only once", shadowOf(ready).nextStartedActivity)
    }

    @Test fun happyPathAustralia() {
        val ctl = Robolectric.buildActivity(SetupActivity::class.java).setup()
        val a = ctl.get()
        // 1. Welcome: logo + one line, then on by itself.
        assertTrue(a.screen().toString(), a.screen().containsAll(listOf("Daily Whisper", "Say it softly. Keep it close.")))
        ShadowLooper.idleMainLooper(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("still welcome at 2 s: " + a.screen(), a.screen().contains("Daily Whisper"))
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS) // 2.5 s (default) after it appeared
        // 2. Source language, with the subtle note. English is pre-picked from the phone's language.
        assertTrue(a.screen().toString(), a.screen().contains("Which language do you usually write or speak your notes in?"))
        assertTrue(a.screen().contains("Keyboard language doesn't matter. You can whisper in the language that comes first."))
        a.click("Next")
        // 3. Target language.
        assertTrue(a.screen().toString(), a.screen().contains("Which language would you like your notes translated into?"))
        // The list starts short (phone's language and the current pick); Hindi is under "All languages".
        a.tap("All languages"); a.pick("Hindi"); a.click("Next")
        // 4. Region: explicit, nothing pre-selected, Next waits for a pick.
        assertTrue(a.screen().contains("Which region are you in?"))
        assertFalse("Next disabled until a region is picked", buttons(a.root()).first { it.text == "Next" }.isEnabled)
        a.pick("Australia"); a.click("Next")
        // Saved, and the Opus-MT pair is queued in the background, on any network (Wi-Fi only is off by default).
        val s = ctx.settings()
        assertEquals("en", s[Prefs.TR_SOURCE]); assertEquals("hi", s[Prefs.TR_TARGET])
        assertEquals("au", ctx.userRegion()); assertEquals("au", s[Prefs.DG_REGION])
        assertEquals(listOf("en-hi"), OpusPacks.wanted(ctx))
        val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        // 5. Residency: Deepgram has Australia, so just a short statement.
        assertTrue(a.screen().toString(), a.screen().contains("Your voice stays close."))
        assertTrue(a.screen().contains("Live dictation keeps your voice in your region. Nothing is kept after it’s typed."))
        assertTrue(a.screen().contains("Full details are in the Privacy Policy."))
        assertTrue("quiet progress line", a.screen().any { it.startsWith("Getting English → Hindi ready") })
        assertNoTechNames(a.screen())
        assertTrue(ctx.liveDictationAllowed())
        a.click("Next")
        // 6. Change later, then the notes list.
        assertTrue(a.screen().contains("You can change these later in Settings."))
        assertFalse(ctx.uiState().getBoolean(SetupActivity.KEY_DONE, false))
        a.click("Open my notes")
        assertTrue(a.isFinishing)
        assertTrue(ctx.uiState().getBoolean(SetupActivity.KEY_DONE, false))
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.mic).visibility)
    }

    @Test fun rerunFromSettingsEndsWithDoneNotOpenMyNotes() {
        val i = android.content.Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)
        val a = Robolectric.buildActivity(SetupActivity::class.java, i).setup().get()
        assertTrue("rerun skips the welcome", a.screen().contains("Which language do you usually write or speak your notes in?"))
        a.click("Next"); a.click("Next")
        a.pick("Australia"); a.click("Next"); a.click("Next")
        assertTrue(a.screen().contains("You can change these later in Settings."))
        assertTrue(buttons(a.root()).any { it.text == "Done" })
        assertFalse(buttons(a.root()).any { it.text == "Open my notes" })
        a.click("Done")
        assertTrue(a.isFinishing)
    }

    @Test fun outsideDeepgramRegionAsksAndDeclineHidesMic() {
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        // A tap moves on from the welcome straight away (no waiting).
        fun tappable(v: View): View? = if (v.hasOnClickListeners()) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { tappable(g.getChildAt(it)) } }
        tappable(a.root())!!.performClick(); ShadowLooper.idleMainLooper()
        assertTrue(a.screen().contains("Which language do you usually write or speak your notes in?"))
        a.click("Next"); a.click("Next")
        a.pick("New Zealand"); a.click("Next")
        assertEquals("au", ctx.settings()[Prefs.DG_REGION])
        assertTrue(a.screen().toString(), a.screen().contains("Is it OK to send audio to Australia?"))
        assertTrue(a.screen().any { it.startsWith("Live dictation can't keep your voice in New Zealand. It would go to Australia") })
        assertTrue(a.screen().contains("Full details are in the Privacy Policy."))
        assertNoTechNames(a.screen())
        a.click("Not now")
        assertFalse(ctx.liveDictationAllowed())
        a.click("Open my notes")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("mic hidden without consent", View.GONE, main.findViewById<View>(R.id.mic).visibility)
        // Saying yes later (Settings uses the same stored consent) brings it back.
        ctx.setDgConsent("au")
        val main2 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, main2.findViewById<View>(R.id.mic).visibility)
        // Pointing dictation somewhere else needs a new yes.
        ctx.settings().edit().putString(Prefs.DG_REGION.key, "us").commit()
        assertFalse(ctx.liveDictationAllowed())
    }

    @Test fun allowStoresConsent() {
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        a.click("Next"); a.click("Next"); a.pick("Rest of Europe (UK, Switzerland, Norway…)"); a.click("Next")
        assertTrue(a.screen().contains("Is it OK to send audio to the EU?"))
        a.click("Allow")
        assertEquals("eu", ctx.dgConsent()); assertTrue(ctx.liveDictationAllowed())
    }

    @Test fun sameLanguageOrNoModelMeansMlKitQuietly() {
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "ko").commit()
        OpusPacks.sync(ctx)
        assertTrue(OpusPacks.wanted(ctx).isEmpty())
        assertTrue(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        assertTrue(OpusPacks.statusLine(ctx), OpusPacks.statusLine(ctx).contains("ML Kit translates"))
    }

    @Test fun readyModelIsUsedAndLanguageChangeRederives() {
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "ja").putString(Prefs.TR_TARGET.key, "hi").commit()
        assertEquals(listOf("ja-en", "en-hi"), OpusPacks.wanted(ctx))
        assertNull(OpusPacks.readyRoute(ctx, "ja", "hi"))
        // Pretend both downloads finished (the real ones are SHA-256-checked before they're renamed in).
        for (pair in listOf("ja-en", "en-hi")) for (f in OpusMtCatalog.MODELS.getValue(pair).files)
            RandomAccessFile(File(OpusPacks.dir(ctx, pair).apply { mkdirs() }, f.local), "rw").use { it.setLength(f.size) }
        assertEquals(listOf("ja-en", "en-hi"), OpusPacks.readyRoute(ctx, "ja", "hi"))
        assertTrue(OpusPacks.statusLine(ctx), OpusPacks.statusLine(ctx).startsWith("Japanese → Hindi ready on this phone"))
        assertEquals("Japanese → Hindi is ready on this phone.", OpusPacks.setupStatusLine(ctx))
        // Settings: target becomes German -> ja-en kept, en-hi removed, en-de queued.
        ctx.settings().edit().putString(Prefs.TR_TARGET.key, "de").commit()
        OpusPacks.sync(ctx)
        assertTrue(OpusPacks.dir(ctx, "ja-en").exists()); assertFalse(OpusPacks.dir(ctx, "en-hi").exists())
        assertEquals(1, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.size)
        // Delete in Settings: space freed, nothing re-fetched until "Download again".
        OpusPacks.delete(ctx)
        assertFalse(File(ctx.filesDir, "opus").exists())
        OpusPacks.sync(ctx)
        assertTrue(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        OpusPacks.downloadAgain(ctx)
        assertEquals(1, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.size)
    }

    @Test fun wifiSettingChangesTheJobConstraint() {
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "de")
            .putBoolean(Prefs.TR_WIFI_ONLY.key, true).commit()
        OpusPacks.sync(ctx)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single().networkType)
        ctx.settings().edit().putBoolean(Prefs.TR_WIFI_ONLY.key, false).commit()
        OpusPacks.sync(ctx)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single().networkType)
    }

    @Test fun wifiOnlyIsOffByDefaultAlsoForExistingInstalls() {
        // An install from before this change that never touched the switch has no stored value: it gets the new default.
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "de").commit()
        assertFalse(ctx.settings().contains(Prefs.TR_WIFI_ONLY.key))
        assertFalse(ctx.settings()[Prefs.TR_WIFI_ONLY])
        OpusPacks.sync(ctx)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single().networkType)
        // Opening Settings doesn't write it either.
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertFalse(ctx.settings().contains(Prefs.TR_WIFI_ONLY.key))
    }

    @Test fun settingsRegionChangeAsksConsent() {
        ctx.setUserRegion("au"); ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "hi").commit()
        val st = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun row(title: String) = all(st.window.decorView).first { r -> r.hasOnClickListeners() && r is ViewGroup && texts(r).firstOrNull() == title }
        fun deepgramPage() = Robolectric.buildActivity(SettingsActivity::class.java,
            Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "deepgram")).setup().get()
        assertTrue(texts(deepgramPage().window.decorView).contains("Audio stays in Australia"))
        assertTrue(texts(row("Offline translation models")).toString(), texts(row("Offline translation models")).any { it.contains("English → Hindi") })
        row("Region").performClick(); ShadowLooper.idleMainLooper()
        val pick = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        pick.listView.performItemClick(null, Residency.REGIONS.indexOfFirst { it.code == "nz" }, 0); ShadowLooper.idleMainLooper()
        assertEquals("nz", ctx.userRegion()); assertEquals("au", ctx.settings()[Prefs.DG_REGION])
        val consent = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertTrue(consent.isShowing)
        consent.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper()
        assertFalse(ctx.liveDictationAllowed())
        assertTrue(texts(deepgramPage().window.decorView).any { it.startsWith("Mic off: needs your OK to send audio to Australia") })
    }

    @Test fun rerunFromSettingsSkipsWelcomeAndKeepsRegion() {
        ctx.setUserRegion("in"); ctx.uiState().edit().putBoolean(SetupActivity.KEY_DONE, true).commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java,
            Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RERUN, true)).setup().get()
        assertTrue(a.screen().contains("Which language do you usually write or speak your notes in?"))
        a.click("Next"); a.click("Next")
        assertTrue("saved region pre-selected", buttons(a.root()).first { it.text == "Next" }.isEnabled)
        a.click("Next")
        assertTrue(a.screen().contains("Your voice stays close."))
        assertTrue(a.screen().contains("Live dictation keeps your voice in your region. Nothing is kept after it’s typed."))
        assertNoTechNames(a.screen())
        assertNotNull(a)
    }
}
