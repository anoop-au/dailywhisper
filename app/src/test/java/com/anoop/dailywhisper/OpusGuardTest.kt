package com.anoop.dailywhisper

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Opus-MT crash guard as the app wires it (OpusPacks.guard: noBackupFilesDir + this app's version code): after a
 * run that died inside ONNX Runtime, Opus-MT stays off and Settings says ML Kit translates, until "Try again".
 */
@RunWith(RobolectricTestRunner::class)
class OpusGuardTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val guardDir get() = File(ctx.noBackupFilesDir, "opus-guard")

    @Before fun setUp() {
        guardDir.deleteRecursively()
        File(ctx.filesDir, "opus").deleteRecursively()
        ctx.getSharedPreferences("settings", 0).edit().clear()
            .putString(Prefs.TR_SOURCE.key, "en").putString(Prefs.TR_TARGET.key, "zh").commit()
        // en-zh "downloaded": sparse files of the catalog sizes (isReady checks sizes; SHA-256 was checked on download).
        val m = OpusMtCatalog.MODELS.getValue("en-zh")
        val dir = OpusPacks.dir(ctx, "en-zh").apply { mkdirs() }
        for (f in m.files) RandomAccessFile(File(dir, f.local), "rw").use { it.setLength(f.size) }
    }

    @Test fun readyPairIsUsedNormally() {
        assertEquals(listOf("en-zh"), OpusPacks.readyRoute(ctx, "en", "zh"))
        assertNull(OpusPacks.guard(ctx).blocked()) // Robolectric's CPU reports no SME
        assertTrue(OpusPacks.statusLine(ctx), OpusPacks.statusLine(ctx).contains("ready on this phone"))
    }

    @Test fun runThatDiedTurnsOpusOffUntilRetried() {
        // What a process killed mid-translation (SIGILL in ONNX Runtime) leaves behind.
        guardDir.mkdirs(); File(guardDir, "inflight").writeText("1")
        assertEquals(OpusGuard.Block.CRASHED, OpusPacks.guard(ctx).blocked())
        assertTrue(OpusPacks.statusLine(ctx), OpusPacks.statusLine(ctx).contains("ML Kit translates"))
        assertEquals("still off on the next tap", OpusGuard.Block.CRASHED, OpusPacks.guard(ctx).blocked())
        OpusPacks.guard(ctx).reset()
        assertNull(OpusPacks.guard(ctx).blocked())
        assertTrue(OpusPacks.statusLine(ctx).contains("ready on this phone"))
    }

    @Test fun finishedRunLeavesNoMarker() {
        val g = OpusPacks.guard(ctx)
        assertEquals("done", g.run { "done" })
        assertTrue(!File(guardDir, "inflight").exists())
        assertNull(OpusPacks.guard(ctx).blocked())
    }
}
