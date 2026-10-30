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
        tidy(c, publish = false)
        sync(c)
    }

    /** Sharper English packs a switch deletes once the newly chosen one is on the phone. */
    private const val KEY_SHARPER_LEAVING = "sharper_english_leaving"

    private fun leaving(c: Context): Set<String> = c.uiState().getStringSet(KEY_SHARPER_LEAVING, emptySet())!!.toSet()

    private fun present(c: Context) = installed(c) + wanted(c)

    /** The pack page's Sharper English choice: [SpeechPackCatalog.RECHECK_SMALL], [SpeechPackCatalog.RECHECK] or null (Off). */
    fun sharper(c: Context): String? = SpeechPackCatalog.sharper(present(c), leaving(c))

    /**
     * Choose Sharper English (null = Off): downloads it if needed (with the English pack), and deletes the other size,
     * at once if it's only on its way or the chosen one is already here, else once the chosen one is ready. The page
     * asks first before anything on the phone is deleted.
     */
    fun chooseSharper(c: Context, target: String?) {
        val plan = SpeechPackCatalog.sharperSwitch(target, installed(c), wanted(c) - leaving(c))
        c.uiState().edit().putStringSet(KEY_SHARPER_LEAVING, plan.deleteWhenReady).apply()
        plan.deleteNow.forEach { drop(c, it) }
        if (plan.download != null) download(c, plan.download) else { tidy(c, publish = false); sync(c) }
        publish(progress)
    }

    /**
     * Keeps the packs the page doesn't list in step: finishes a Sharper English switch once the new one is ready, and
     * gets or removes the punctuation pack ([SpeechPackCatalog.wantsPunct]). Run after downloads, deletes and choices,
     * and when the pack page opens (so phones from before the choice catch up). Never deletes a Sharper English pack
     * the user didn't switch away from.
     */
    fun tidy(c: Context, publish: Boolean = true) {
        val leaving = leaving(c)
        val chosen = sharper(c)
        if (leaving.isNotEmpty()) {
            val ready = installed(c)
            if (chosen != null && chosen in ready) leaving.forEach { drop(c, it) }
            if (chosen == null || chosen in ready) c.uiState().edit().remove(KEY_SHARPER_LEAVING).apply()
        }
        val ready = installed(c)
        val p = c.settings()
        val en = "en" in ready || "en" in wanted(c)
        val punct = SpeechPackCatalog.PUNCT
        val punctHere = punct in ready || punct in wanted(c)
        val recheckOn = p[Prefs.OD_RECHECK]
        val sharperReady = SpeechPackCatalog.RECHECKS.any { it in ready }
        if (punctHere && SpeechPackCatalog.dropsPunct(sharperReady, recheckOn)) drop(c, punct)
        else if (punct in wanted(c) && punct !in ready && !SpeechPackCatalog.wantsPunct(en, sharper(c) != null, p[Prefs.OD_PUNCT], recheckOn)) drop(c, punct)
        else if (!punctHere && SpeechPackCatalog.wantsPunct(en, sharper(c) != null, p[Prefs.OD_PUNCT], recheckOn))
            c.uiState().edit().putStringSet(KEY_WANTED, wanted(c) + punct).apply()
        if (publish) { publish(progress); sync(c) }
    }

    /** Remove one pack (or its download) without the page refresh; [delete] and [tidy] refresh once. */
    private fun drop(c: Context, pack: String) {
        c.uiState().edit().putStringSet(KEY_WANTED, wanted(c) - pack).apply()
        if (progress.pack == pack) stopRequested = true
        OnDeviceSpeech.release(pack)
        dir(c, pack).deleteRecursively()
    }

    /** "Get Sharper English?" was answered (either size, or No thanks): it isn't asked again. */
    const val KEY_SHARPER_ASKED = "sharper_english_asked"

    /** A Sharper English pack is on the phone or on its way. */
    fun hasSharper(c: Context) = sharper(c) != null

    /** The one-time "Get Sharper English?" question under the English pack: English is on the phone, no Sharper English yet. */
    fun askSharper(c: Context) = SpeechPackCatalog.pack("en")?.let { isReady(c, it) } == true && !hasSharper(c) &&
        !c.uiState().getBoolean(KEY_SHARPER_ASKED, false)

    fun sharperAsked(c: Context) = c.uiState().edit().putBoolean(KEY_SHARPER_ASKED, true).apply()

    /** Delete a pack (or stop its download) and free the space. */
    fun delete(c: Context, pack: String) {
        drop(c, pack)
        tidy(c, publish = false)
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

    /** One short line about a pack for the pack page: what it does and its size, or how its download is going. */
    fun statusLine(c: Context, pack: SpeechPackCatalog.Pack): String {
        if (isReady(c, pack)) return "${pack.about} · ${SpeechPackCatalog.mb(bytesOnDisk(c, pack.id))}"
        if (pack.id in wanted(c)) {
            val pr = progress
            if (pr.pack == pack.id && pr.running && pr.total > 0) return "Downloading… ${pr.done * 100 / pr.total}% of ${SpeechPackCatalog.mb(pr.total)}"
            if (pr.pack == pack.id && pr.error != null) return "${pr.error}. Will try again soon"
            if (c.settings()[Prefs.TR_WIFI_ONLY]) return "Waiting for Wi-Fi · ${SpeechPackCatalog.mb(pack.bytes)}"
            return "Waiting to download · ${SpeechPackCatalog.mb(pack.bytes)}"
        }
        return "${pack.about} · ${SpeechPackCatalog.mb(pack.bytes)}"
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
            if (r == OpusPacks.Outcome.DONE) runCatching { SpeechPacks.tidy(applicationContext) } // also syncs
        }, "dw-speech-download").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
