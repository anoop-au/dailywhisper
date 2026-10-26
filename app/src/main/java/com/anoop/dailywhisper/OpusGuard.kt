package com.anoop.dailywhisper

import java.io.File

/**
 * Keeps a fault inside ONNX Runtime's native code (which no Kotlin try/catch can stop: the process just dies) from
 * taking translation down with it. Pure Kotlin (no Android imports) so tools/translate-harness tests exactly this code.
 *
 * - CPU: ONNX Runtime before 1.24.3 picks Arm SME/SME2 matrix kernels on processors that report SME but can't run them
 *   (Snapdragon 8 Elite Gen 5, e.g. Galaxy Z Fold 8) and dies with SIGILL on the first translation
 *   (github.com/microsoft/onnxruntime/issues/27884, /26921). On such a processor, with that engine, Opus-MT is not
 *   started at all and ML Kit translates. The app now bundles 1.28.0 ([ORT_SME_SAFE]), so this check no longer blocks.
 * - CRASHED: a marker file is written before each Opus-MT run and removed after it. If it is still there when this app
 *   process didn't write it, the last run died mid-way; Opus-MT then stays off (ML Kit translates) until the app is
 *   updated or the user retries it in Settings → Translation → Offline translation models.
 */
class OpusGuard(private val dir: File, private val appVersion: Long, private val cpuFeatures: Set<String>) {
    enum class Block { CPU, CRASHED }

    private val inflight = File(dir, "inflight")
    private val crashed = File(dir, "crashed")

    /** Why Opus-MT must not run now, or null if it may. */
    fun blocked(): Block? {
        if (smeRisk(cpuFeatures)) return Block.CPU
        synchronized(LOCK) {
            if (inflight.exists() && inflight.path !in running) {
                // The last run never finished: its process died inside ONNX Runtime (or was killed mid-run).
                runCatching { dir.mkdirs(); crashed.writeText(appVersion.toString()) }
                inflight.delete()
            }
            if (!crashed.exists()) return null
            if (crashed.readText().trim() == appVersion.toString()) return Block.CRASHED
            crashed.delete() // the app was updated since: give Opus-MT another go
            return null
        }
    }

    /** Runs one Opus-MT translation with the marker in place (removed afterwards, also when [block] throws). */
    fun <T> run(block: () -> T): T {
        synchronized(LOCK) { dir.mkdirs(); inflight.writeText(appVersion.toString()); running += inflight.path }
        try {
            return block()
        } finally {
            synchronized(LOCK) { inflight.delete(); running -= inflight.path }
        }
    }

    /** Settings → "Try Opus-MT again". */
    fun reset() { synchronized(LOCK) { crashed.delete() } }

    companion object {
        private val LOCK = Any()
        /** Marker files a run in this process holds right now. */
        private val running = HashSet<String>()

        /**
         * True: app/build.gradle.kts uses onnxruntime-android 1.24.3 or later (1.28.0; SME detection fixed in 1.24.3).
         * The harness checks this matches the version in the build file. The crash marker below stays as a safety net.
         */
        const val ORT_SME_SAFE = true

        /** The CPU feature flags from /proc/cpuinfo ("Features : fp asimd ... sme sme2 ..."), all cores together. */
        fun cpuFeatures(cpuinfo: String): Set<String> = cpuinfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .flatMap { it.substringAfter(':', "").trim().split(Regex("\\s+")).asSequence() }
            .filter { it.isNotEmpty() }.toSet()

        /** This processor reports Arm SME (any variant) and the bundled ONNX Runtime may misuse it. */
        fun smeRisk(features: Set<String>, ortSmeSafe: Boolean = ORT_SME_SAFE): Boolean =
            !ortSmeSafe && features.any { it.startsWith("sme") }
    }
}
