package com.anoop.dailywhisper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Opus-MT models the chosen languages need (OpusMt.route): kept in filesDir/opus/<pair>/, fetched in the
 * background by [OpusDownloadJob] (Android's JobScheduler: waits for Wi-Fi when "Wi-Fi only" is on, resumes where it
 * stopped, no notification). Only the Hugging Face file URLs are contacted; nothing about the user or their notes is
 * sent. Until a pair is ready, translation uses ML Kit.
 */
object OpusPacks {
    private const val JOB_ID = 4107
    private const val KEY_OFF = "opus_off"  // the user deleted the models in Settings: don't fetch them again by itself

    /** Live download state for the UI (setup, Settings). Main-thread listeners. */
    class Progress(val pair: String?, val done: Long, val total: Long, val running: Boolean, val error: String?)

    @Volatile var progress = Progress(null, 0, 0, false, null)
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun publish(p: Progress) { progress = p; main.post { listeners.forEach { it() } } }

    private fun root(c: Context) = File(c.filesDir, "opus")
    fun dir(c: Context, pair: String) = File(root(c), pair)

    fun isReady(c: Context, pair: String): Boolean {
        val m = OpusMtCatalog.MODELS[pair] ?: return false
        return m.files.all { File(dir(c, pair), it.local).length() == it.size } // checked (SHA-256) before it was renamed in
    }

    /** The pairs the current languages need (empty: same language, or Opus-MT doesn't cover it). */
    fun wanted(c: Context): List<String> {
        val p = c.settings()
        return OpusMt.route(p[Prefs.TR_SOURCE], p[Prefs.TR_TARGET]) ?: emptyList()
    }

    /** The route to use for this translation, if every model on it is on the phone. */
    fun readyRoute(c: Context, source: String, target: String): List<String>? =
        OpusMt.route(source, target)?.takeIf { r -> r.all { isReady(c, it) } }

    fun bytesOnDisk(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun totalBytes(pairs: List<String>): Long = pairs.sumOf { OpusMtCatalog.MODELS[it]?.bytes ?: 0 }

    fun isOff(c: Context) = c.uiState().getBoolean(KEY_OFF, false)

    /** Opus-MT's crash guard (OpusGuard): this phone's processor, and whether the last run died mid-way. */
    fun guard(c: Context): OpusGuard {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0L)
        return OpusGuard(File(c.noBackupFilesDir, "opus-guard"), version, cpuFeatures)
    }

    private val cpuFeatures: Set<String> by lazy {
        runCatching { OpusGuard.cpuFeatures(File("/proc/cpuinfo").readText()) }.getOrDefault(emptySet())
    }

    /**
     * Brings the phone in line with the chosen languages: removes models no longer needed, and schedules the
     * download of missing ones (unless the user turned them off). Call after languages or "Wi-Fi only" change.
     */
    fun sync(c: Context) {
        val want = wanted(c)
        root(c).listFiles()?.forEach { if (it.name !in want) it.deleteRecursively() }
        val missing = want.filter { !isReady(c, it) }
        val js = c.getSystemService(JobScheduler::class.java)
        // Opus-MT can't run on this processor with this engine: don't spend ~120 MB on models it can't use.
        if (missing.isEmpty() || isOff(c) || guard(c).blocked() == OpusGuard.Block.CPU) { js.cancel(JOB_ID); return }
        val remaining = missing.sumOf { pair ->
            OpusMtCatalog.MODELS.getValue(pair).files.sumOf { f ->
                f.size - (File(dir(c, pair), f.local + ".part").takeIf { it.exists() }?.length() ?: 0L)
            }
        }
        val wifiOnly = c.settings()[Prefs.TR_WIFI_ONLY]
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, OpusDownloadJob::class.java))
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setEstimatedNetworkBytes(remaining, 0)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (!progress.running) js.schedule(job) // a running download picks up the new list when it next starts
    }

    /** Settings → Download again (also clears the "off" choice). */
    fun downloadAgain(c: Context) {
        c.uiState().edit().putBoolean(KEY_OFF, false).apply()
        publish(Progress(null, 0, 0, false, null))
        sync(c)
    }

    /** Settings → Delete: frees the space; translation goes back to ML Kit until "Download again". */
    fun delete(c: Context) {
        c.uiState().edit().putBoolean(KEY_OFF, true).apply()
        c.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        stopRequested = true
        root(c).deleteRecursively()
        publish(Progress(null, 0, 0, false, null))
    }

    /** Setup: the user just chose languages, so fetch whatever they need. */
    fun start(c: Context) { c.uiState().edit().putBoolean(KEY_OFF, false).apply(); sync(c) }

    private fun pending(c: Context) = c.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    /** One short line for setup and Settings. */
    fun statusLine(c: Context): String {
        val p = c.settings()
        val src = p[Prefs.TR_SOURCE]; val tgt = p[Prefs.TR_TARGET]
        val names = "${TranslateConfig.languageName(src)} → ${TranslateConfig.languageName(tgt)}"
        if (src == tgt) return "Nothing to translate: both languages are the same"
        val want = wanted(c)
        if (want.isEmpty()) return "Opus-MT doesn't cover $names, so ML Kit translates it"
        when (guard(c).blocked()) {
            OpusGuard.Block.CPU -> return "Opus-MT is off on this phone: its processor needs a newer version of the " +
                "translation engine. ML Kit translates $names instead"
            OpusGuard.Block.CRASHED -> return "Opus-MT stopped unexpectedly last time, so ML Kit translates $names for now"
            null -> {}
        }
        val size = mb(totalBytes(want))
        if (want.all { isReady(c, it) }) return "$names ready on this phone (Opus-MT, $size)"
        if (isOff(c)) return "$names not downloaded ($size). ML Kit translates until you download it"
        val pr = progress
        if (pr.running && pr.total > 0) return "Getting $names ready… ${pr.done * 100 / pr.total}% of ${mb(pr.total)}. ML Kit covers until then"
        pr.error?.let { return "$it. Will try again soon; ML Kit covers until then" }
        if (pending(c)) return if (p[Prefs.TR_WIFI_ONLY]) "Waiting for Wi-Fi to fetch $names ($size). ML Kit covers until then"
            else "Waiting to fetch $names ($size). ML Kit covers until then"
        return "$names not downloaded yet ($size). ML Kit translates until then"
    }

    fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

    @Volatile private var stopRequested = false

    enum class Outcome { DONE, STOPPED, RETRY }

    /** The job's work, on its own thread. */
    internal fun runDownloads(c: Context, keepGoing: () -> Boolean): Outcome {
        stopRequested = false
        val go = { keepGoing() && !stopRequested && !isOff(c) }
        val missing = wanted(c).filter { !isReady(c, it) }
        for (pair in missing) {
            val m = OpusMtCatalog.MODELS.getValue(pair)
            val dir = dir(c, pair).apply { mkdirs() }
            val need = m.bytes - m.files.sumOf { f -> File(dir, f.local).takeIf { it.length() == f.size }?.length() ?: 0L }
            if (dir.usableSpace < need + 50_000_000) {
                publish(Progress(pair, 0, m.bytes, false, "Not enough free space (needs ${mb(need)})"))
                return Outcome.RETRY
            }
            var before = 0L
            for (f in m.files) {
                val d = ResumableDownload(m.url(f), File(dir, f.local + ".part"), File(dir, f.local), f.size, f.sha256)
                val base = before
                val r = d.run(go, { done, _ -> publish(Progress(pair, base + done, m.bytes, true, null)) })
                when (r) {
                    ResumableDownload.Result.DONE -> before += f.size
                    ResumableDownload.Result.STOPPED -> { publish(Progress(pair, before, m.bytes, false, null)); return Outcome.STOPPED }
                    ResumableDownload.Result.FAILED -> { publish(Progress(pair, before, m.bytes, false, d.error)); return Outcome.RETRY }
                }
            }
        }
        publish(Progress(null, 0, 0, false, null))
        return Outcome.DONE
    }
}

/** Background download of the Opus-MT models (scheduled by OpusPacks.sync). */
class OpusDownloadJob : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread({
            // Nothing here may escape: an uncaught exception on this thread would close the whole app.
            val r = try { OpusPacks.runDownloads(applicationContext) { !stopped } } catch (e: Throwable) {
                Log.w("DailyWhisper", "Opus-MT download failed", e); OpusPacks.Outcome.RETRY
            }
            runCatching { if (!stopped) jobFinished(params, r == OpusPacks.Outcome.RETRY) }
            if (r == OpusPacks.Outcome.DONE) runCatching { OpusPacks.sync(applicationContext) } // languages may have changed
        }, "dw-opus-download").start()
        return true
    }

    /** Wi-Fi lost, or Android's time limit: stop between reads; the partial file stays and we pick up later. */
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
