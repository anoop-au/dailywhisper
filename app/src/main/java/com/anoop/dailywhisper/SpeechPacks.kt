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
 * The offline speech packs on this phone (SpeechPackCatalog), in noBackupFilesDir/speech/<pack>/. Downloads run in the
 * background with Android's JobScheduler ([SpeechPackDownloadJob]), like the Opus-MT models: any network, mobile data
 * included, unless "Download on Wi-Fi only" is on (off by default). They resume where they stopped, and each file's
 * size and SHA-256 are checked before it is used. Only the Hugging Face file URLs are contacted; nothing about the user,
 * their voice or their notes is sent. Packs the user asked for are remembered in "state" until they are on the phone.
 */
object SpeechPacks {
    private const val JOB_ID = 4108
    private const val KEY_WANTED = "speech_packs_wanted"

    class Progress(val pack: String?, val done: Long, val total: Long, val running: Boolean, val error: String?)

    @Volatile var progress = Progress(null, 0, 0, false, null)
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun publish(p: Progress) { progress = p; main.post { listeners.forEach { it() } } }

    private fun root(c: Context) = File(c.noBackupFilesDir, "speech")
    fun dir(c: Context, pack: String) = File(root(c), pack)
    fun file(c: Context, pack: String, local: String) = File(dir(c, pack), local)

    fun isReady(c: Context, pack: SpeechPackCatalog.Pack): Boolean =
        pack.files.all { file(c, pack.id, it.local).length() == it.size } // SHA-256-checked before it was renamed in

    /** Ids of the packs that are fully on the phone. */
    fun installed(c: Context): Set<String> = SpeechPackCatalog.PACKS.filter { isReady(c, it) }.map { it.id }.toSet()

    fun bytesOnDisk(c: Context, pack: String): Long = dir(c, pack).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun wanted(c: Context): Set<String> = c.uiState().getStringSet(KEY_WANTED, emptySet())!!.toSet()
        .filter { SpeechPackCatalog.pack(it) != null }.toSet()

    fun isQueued(c: Context, pack: String) = pack in wanted(c) && SpeechPackCatalog.pack(pack)?.let { !isReady(c, it) } == true

    /** Ask for a pack (pack page, setup): it downloads in the background. */
    fun download(c: Context, pack: String) {
        // An add-on (Sharper English, punctuation) needs its base pack: get both.
        val base = SpeechPackCatalog.pack(pack)?.addonFor?.let { SpeechPackCatalog.pack(it) }?.takeIf { !isReady(c, it) }
        c.uiState().edit().putStringSet(KEY_WANTED, wanted(c) + listOfNotNull(base?.id, pack)).apply()
        if (progress.error != null) publish(Progress(null, 0, 0, false, null))
        sync(c)
    }

    /** "Get Sharper English?" was answered (either size, or No thanks): it isn't asked again. */
    const val KEY_SHARPER_ASKED = "sharper_english_asked"

    /** A Sharper English pack is on the phone or on its way. */
    fun hasSharper(c: Context) = SpeechPackCatalog.RECHECKS.any { id -> id in wanted(c) || SpeechPackCatalog.pack(id)?.let { isReady(c, it) } == true }

    /** The one-time "Get Sharper English?" question under the English pack: English is on the phone, no Sharper English yet. */
    fun askSharper(c: Context) = SpeechPackCatalog.pack("en")?.let { isReady(c, it) } == true && !hasSharper(c) &&
        !c.uiState().getBoolean(KEY_SHARPER_ASKED, false)

    fun sharperAsked(c: Context) = c.uiState().edit().putBoolean(KEY_SHARPER_ASKED, true).apply()

    /** Delete a pack (or stop its download) and free the space. */
    fun delete(c: Context, pack: String) {
        c.uiState().edit().putStringSet(KEY_WANTED, wanted(c) - pack).apply()
        if (progress.pack == pack) stopRequested = true
        OnDeviceSpeech.release(pack)
        dir(c, pack).deleteRecursively()
        publish(Progress(null, 0, 0, false, null))
        sync(c)
    }

    /** Schedules (or cancels) the background download for the packs still missing. Call after "Wi-Fi only" changes too. */
    fun sync(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        val missing = wanted(c).mapNotNull { SpeechPackCatalog.pack(it) }.filter { !isReady(c, it) }
        if (missing.isEmpty()) { js.cancel(JOB_ID); return }
        val remaining = missing.sumOf { p ->
            p.files.sumOf { f -> f.size - (File(dir(c, p.id), f.local + ".part").takeIf { it.exists() }?.length() ?: 0L) }
        }
        val wifiOnly = c.settings()[Prefs.TR_WIFI_ONLY]
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, SpeechPackDownloadJob::class.java))
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setEstimatedNetworkBytes(remaining, 0)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (!progress.running) js.schedule(job)
    }

    private fun pending(c: Context) = c.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    /** One line about a pack for the pack page: ready, downloading, waiting, or its size. */
    fun statusLine(c: Context, pack: SpeechPackCatalog.Pack): String {
        val how = when {
            pack.addonFor != null -> ""
            pack.mode == SpeechPackCatalog.Mode.LIVE -> " · Live, word by word"
            else -> " · A sentence at a time"
        }
        if (isReady(c, pack)) return "On this phone · ${SpeechPackCatalog.mb(bytesOnDisk(c, pack.id))}$how"
        if (pack.id in wanted(c)) {
            val pr = progress
            if (pr.pack == pack.id && pr.running && pr.total > 0) return "Downloading… ${pr.done * 100 / pr.total}% of ${SpeechPackCatalog.mb(pr.total)}"
            if (pr.pack == pack.id && pr.error != null) return "${pr.error}. Will try again soon"
            if (c.settings()[Prefs.TR_WIFI_ONLY]) return "Waiting for Wi-Fi · ${SpeechPackCatalog.mb(pack.bytes)}"
            return "Waiting to download · ${SpeechPackCatalog.mb(pack.bytes)}"
        }
        return "${SpeechPackCatalog.mb(pack.bytes)}$how"
    }

    @Volatile private var stopRequested = false

    /** The job's work, on its own thread. */
    internal fun runDownloads(c: Context, keepGoing: () -> Boolean): OpusPacks.Outcome {
        stopRequested = false
        for (pack in wanted(c).mapNotNull { SpeechPackCatalog.pack(it) }.filter { !isReady(c, it) }) {
            val go = { keepGoing() && !stopRequested && pack.id in wanted(c) }
            val dir = dir(c, pack.id).apply { mkdirs() }
            val need = pack.bytes - pack.files.sumOf { f -> File(dir, f.local).takeIf { it.length() == f.size }?.length() ?: 0L }
            if (dir.usableSpace < need + 50_000_000) {
                publish(Progress(pack.id, 0, pack.bytes, false, "Not enough free space (needs ${SpeechPackCatalog.mb(need)})"))
                return OpusPacks.Outcome.RETRY
            }
            var before = 0L
            for (f in pack.files) {
                val d = ResumableDownload(f.url, File(dir, f.local + ".part"), File(dir, f.local), f.size, f.sha256)
                val base = before
                when (d.run(go, { done, _ -> publish(Progress(pack.id, base + done, pack.bytes, true, null)) })) {
                    ResumableDownload.Result.DONE -> before += f.size
                    ResumableDownload.Result.STOPPED -> { publish(Progress(pack.id, before, pack.bytes, false, null)); return OpusPacks.Outcome.STOPPED }
                    ResumableDownload.Result.FAILED -> { publish(Progress(pack.id, before, pack.bytes, false, d.error)); return OpusPacks.Outcome.RETRY }
                }
            }
            if (pack.id !in wanted(c)) dir.deleteRecursively() // deleted while it was finishing
        }
        publish(Progress(null, 0, 0, false, null))
        return OpusPacks.Outcome.DONE
    }
}

/** Background download of the offline speech packs (scheduled by SpeechPacks.sync). No notification. */
class SpeechPackDownloadJob : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread({
            // Nothing here may escape: an uncaught exception on this thread would close the whole app.
            val r = try { SpeechPacks.runDownloads(applicationContext) { !stopped } } catch (e: Throwable) {
                Log.w("DailyWhisper", "Speech pack download failed", e); OpusPacks.Outcome.RETRY
            }
            runCatching { if (!stopped) jobFinished(params, r == OpusPacks.Outcome.RETRY) }
            if (r == OpusPacks.Outcome.DONE) runCatching { SpeechPacks.sync(applicationContext) }
        }, "dw-speech-download").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
