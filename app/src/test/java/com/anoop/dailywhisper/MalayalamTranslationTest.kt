package com.anoop.dailywhisper

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile

/**
 * Malayalam: offered as a translation language, translated only by Opus-MT (ML Kit has none). Until its models are on
 * the phone, or when Opus-MT can't run here, the app says so plainly instead of falling back to ML Kit.
 */
@RunWith(RobolectricTestRunner::class)
class MalayalamTranslationTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        ctx.getSharedPreferences("state", 0).edit().clear().commit()
        File(ctx.filesDir, "opus").deleteRecursively()
        File(ctx.noBackupFilesDir, "opus-guard").deleteRecursively()
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun languages(source: String, target: String) =
        ctx.settings().edit().putString(Prefs.TR_SOURCE.key, source).putString(Prefs.TR_TARGET.key, target).commit()

    private fun download(pair: String) {
        val dir = OpusPacks.dir(ctx, pair).apply { mkdirs() }
        for (f in OpusMtCatalog.MODELS.getValue(pair).files) RandomAccessFile(File(dir, f.local), "rw").use { it.setLength(f.size) }
    }

    /** Runs a translation and waits for its error (or result) on the main thread. */
    private fun translateError(source: String, target: String): String? {
        var error: String? = null; var done = false
        NoteTranslator(ctx).translate("ഞാൻ വൈകിയാണ് വീട്ടിൽ വന്നത്.", source, target, object : NoteTranslator.Callbacks {
            override fun onStatus(message: String) {}
            override fun onDone(translation: String, engine: String) { done = true }
            override fun onError(message: String) { error = message; done = true }
        })
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
        assertTrue("translation never reported back", done)
        return error
    }

    @Test fun malayalamIsInBothLanguagePickers() {
        assertTrue(Prefs.TR_SOURCE.options.contains("ml" to "Malayalam"))
        assertTrue(Prefs.TR_TARGET.options.contains("ml" to "Malayalam"))
        assertTrue(TranslateConfig.options().contains("ml" to "Malayalam")) // first-run setup's list
        assertTrue(NoteTranslator(ctx).supports("en", "ml") && NoteTranslator(ctx).supports("ml", "zh"))
        assertFalse(TranslateConfig.mlKit("ml"))
    }

    @Test fun notDownloadedSaysSoAndDownloadsOnMobileData() {
        languages("ml", "en")
        assertFalse(ctx.settings()[Prefs.TR_WIFI_ONLY])
        OpusPacks.sync(ctx)
        val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertEquals("downloads on mobile data too", JobInfo.NETWORK_TYPE_ANY, job.networkType)
        val why = OpusPacks.unavailable(ctx, "ml", "en")!!
        assertTrue(why, why.startsWith("Malayalam works once it's downloaded"))
        assertTrue(OpusPacks.statusLine(ctx).startsWith("Malayalam works once it's downloaded"))
        assertFalse("no ML Kit promise for Malayalam", OpusPacks.statusLine(ctx).contains("ML Kit"))
        val setup = OpusPacks.setupStatusLine(ctx)
        assertFalse(setup, Regex("Opus|ML Kit|MB").containsMatchIn(setup))
        assertEquals(why, translateError("ml", "en"))
    }

    @Test fun pivotNeedsBothModels() {
        languages("ml", "zh")
        assertEquals(listOf("ml-en", "en-zh"), OpusPacks.wanted(ctx))
        download("ml-en")
        assertTrue(OpusPacks.unavailable(ctx, "ml", "zh")!!.startsWith("Malayalam works once it's downloaded"))
        download("en-zh")
        assertNull(OpusPacks.unavailable(ctx, "ml", "zh"))
        assertEquals(listOf("ml-en", "en-zh"), OpusPacks.readyRoute(ctx, "ml", "zh"))
        assertTrue(OpusPacks.statusLine(ctx), OpusPacks.statusLine(ctx).startsWith("Malayalam → Chinese (Simplified) ready on this phone"))
        assertEquals("Malayalam → Chinese (Simplified) is ready on this phone.", OpusPacks.setupStatusLine(ctx))
    }

    @Test fun noOpusRouteIsPlainNotMlKit() {
        languages("ml", "ko")
        assertEquals("Malayalam can't be translated to or from Korean yet.", OpusPacks.unavailable(ctx, "ml", "ko"))
        assertEquals("Malayalam can't be translated to or from Korean yet.", translateError("ml", "ko"))
        OpusPacks.sync(ctx)
        assertTrue(ctx.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }

    @Test fun opusBlockedOnThisPhoneSaysNotAvailable() {
        languages("en", "ml")
        download("en-ml")
        // What a run that died inside ONNX Runtime leaves behind (OpusGuard): Opus-MT stays off until "Try again".
        File(ctx.noBackupFilesDir, "opus-guard").apply { mkdirs() }.resolve("inflight").writeText("1")
        val why = OpusPacks.unavailable(ctx, "en", "ml")!!
        assertTrue(why, why.startsWith("Malayalam translation isn't available on this phone"))
        assertEquals(why, translateError("en", "ml"))
        assertEquals("Malayalam translation isn't available on this phone right now.", OpusPacks.setupStatusLine(ctx))
        OpusPacks.guard(ctx).reset()
        assertNull(OpusPacks.unavailable(ctx, "en", "ml"))
    }

    @Test fun mlKitLanguagesUnchanged() {
        assertNull(OpusPacks.unavailable(ctx, "en", "ko"))
        languages("en", "ko")
        assertTrue(OpusPacks.statusLine(ctx).contains("ML Kit translates"))
    }
}
