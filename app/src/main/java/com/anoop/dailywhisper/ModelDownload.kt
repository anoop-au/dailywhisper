package com.anoop.dailywhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * The Hy-MT model download: state everyone can watch (setup, Settings, the note screen) and the rules for
 * starting, pausing and resuming. The bytes are fetched by [ModelDownloadService] so it keeps going in the
 * background. Files: filesDir/models/ (app-private, never backed up: allowBackup is off).
 */
object ModelDownloads {
    enum class Status { NOT_STARTED, DOWNLOADING, WAITING_WIFI, PAUSED, VERIFYING, READY, FAILED }

    data class State(val status: Status, val bytes: Long, val total: Long, val message: String? = null) {
        val percent: Int get() = if (total <= 0) 0 else ((bytes * 100) / total).toInt()
    }

    private const val KEY_WANT = "hymt_download_wanted"
    private const val KEY_ERROR = "hymt_download_error"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State) -> Unit>()

    @Volatile var running = false
        internal set
    @Volatile private var live: State? = null

    fun dir(c: Context) = File(c.filesDir, "models")
    fun modelFile(c: Context) = File(dir(c), TranslateConfig.MODEL_FILE)
    fun partFile(c: Context) = File(dir(c), TranslateConfig.MODEL_FILE + ".part")
    fun verifiedFile(c: Context) = File(dir(c), TranslateConfig.MODEL_FILE + ".verified")

    fun isReady(c: Context): Boolean = modelFile(c).length() == TranslateConfig.MODEL_SIZE

    fun state(c: Context): State {
        if (isReady(c)) return State(Status.READY, TranslateConfig.MODEL_SIZE, TranslateConfig.MODEL_SIZE)
        live?.takeIf { running }?.let { return it }
        val have = maxOf(partFile(c).length(), verifiedFile(c).length())
        val want = c.uiState().getBoolean(KEY_WANT, false)
        val err = c.uiState().getString(KEY_ERROR, null)
        val status = when {
            !want && have == 0L -> Status.NOT_STARTED
            !want -> Status.PAUSED
            err == WAIT_WIFI -> Status.WAITING_WIFI
            err != null -> Status.FAILED
            else -> Status.PAUSED // wanted but not running (app was closed); resumeIfWanted picks it up
        }
        return State(status, have, TranslateConfig.MODEL_SIZE, err?.takeIf { it != WAIT_WIFI })
    }

    /** Main thread. Called with the current state right away. */
    fun observe(c: Context, l: (State) -> Unit) { listeners += l; l(state(c)) }
    fun unobserve(l: (State) -> Unit) { listeners -= l }

    internal fun publish(c: Context, s: State) {
        live = s
        val app = c.applicationContext
        main.post { listeners.toList().forEach { it(if (s.status == Status.READY) state(app) else s) } }
    }

    internal const val WAIT_WIFI = "wifi"
    internal fun setError(c: Context, msg: String?) = c.uiState().edit().putString(KEY_ERROR, msg).apply()

    fun wifiOnlyBlocks(c: Context): Boolean =
        c.settings()[Prefs.TR_WIFI_ONLY] && c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /** Start (or resume) the one-time download. */
    fun start(c: Context) {
        if (isReady(c)) return
        c.uiState().edit().putBoolean(KEY_WANT, true).putString(KEY_ERROR, null).apply()
        if (wifiOnlyBlocks(c)) {
            setError(c, WAIT_WIFI); publish(c, state(c)); return
        }
        if (running) return
        c.startForegroundService(Intent(c, ModelDownloadService::class.java))
    }

    fun pause(c: Context) {
        c.uiState().edit().putBoolean(KEY_WANT, false).putString(KEY_ERROR, null).apply()
        c.stopService(Intent(c, ModelDownloadService::class.java))
        ModelDownloadService.stopRequested = true
        publish(c, state(c).copy(status = Status.PAUSED))
    }

    /** Removes the model and any partial download (frees ~440 MB). Translation falls back to built-in. */
    fun delete(c: Context) {
        pause(c)
        HyMtEngine.release()
        dir(c).listFiles()?.forEach { it.delete() }
        publish(c, State(Status.NOT_STARTED, 0, TranslateConfig.MODEL_SIZE))
    }

    /** On app open: carries on a download that was wanted but stopped (app closed, Wi-Fi back, error). */
    fun resumeIfWanted(c: Context) {
        if (running || isReady(c) || !c.uiState().getBoolean(KEY_WANT, false)) return
        start(c)
    }
}

/** Foreground "data sync" service: shows a quiet progress notification while the model downloads. */
class ModelDownloadService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(ModelDownloads.state(this)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker == null) {
            stopRequested = false
            ModelDownloads.running = true
            worker = Thread({ run() }, "dw-model-download").apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun run() {
        val c = applicationContext
        val total = TranslateConfig.MODEL_SIZE
        val dl = ResumableDownload(TranslateConfig.MODEL_URL, ModelDownloads.partFile(c), ModelDownloads.verifiedFile(c),
            total, TranslateConfig.MODEL_SHA256)
        var result: ResumableDownload.Result
        var attempts = 0
        var lastNotify = 0L
        do {
            result = dl.run(
                keepGoing = {
                    if (ModelDownloads.wifiOnlyBlocks(c)) { ModelDownloads.setError(c, ModelDownloads.WAIT_WIFI); false } else !stopRequested
                },
                progress = { have, all ->
                    val s = ModelDownloads.State(ModelDownloads.Status.DOWNLOADING, have, all)
                    ModelDownloads.publish(c, s)
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1500) { lastNotify = now; notify(s) }
                },
                verifying = { ModelDownloads.publish(c, ModelDownloads.State(ModelDownloads.Status.VERIFYING, total, total)) },
            )
            if (result == ResumableDownload.Result.FAILED && !stopRequested && attempts < 4) {
                // Brief network blips: wait a little and pick up from the partial file.
                ModelDownloads.publish(c, ModelDownloads.State(ModelDownloads.Status.DOWNLOADING, ModelDownloads.partFile(c).length(), total, dl.error))
                Thread.sleep(3000L shl attempts)
            }
        } while (result == ResumableDownload.Result.FAILED && !stopRequested && attempts++ < 4)

        if (result == ResumableDownload.Result.DONE) {
            val ok = runCatching {
                val v = ModelDownloads.verifiedFile(c)
                GgufPatch.patch(v)
                v.renameTo(ModelDownloads.modelFile(c))
            }.getOrDefault(false)
            ModelDownloads.setError(c, if (ok) null else "Couldn't prepare the model file. Try again")
        } else if (result == ResumableDownload.Result.FAILED) {
            ModelDownloads.setError(c, dl.error)
        }
        ModelDownloads.running = false
        ModelDownloads.publish(c, ModelDownloads.state(c))
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (ModelDownloads.isReady(c)) getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, doneNotification())
        stopSelf()
    }

    /** Android 15+: data-sync services get a daily time budget. Pause cleanly; opening the app resumes. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopRequested = true }

    override fun onDestroy() { stopRequested = true; super.onDestroy() }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Translation model download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(s: ModelDownloads.State): Notification {
        channel()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Getting the better translator")
            .setContentText(if (s.status == ModelDownloads.Status.VERIFYING) "Checking the file…" else "${s.percent}% of 440 MB")
            .setProgress(100, s.percent, false)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun notify(s: ModelDownloads.State) = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))

    private fun doneNotification(): Notification {
        channel()
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Better translator ready").setContentText("Tap a note to translate it, fully offline")
            .setAutoCancel(true).setContentIntent(openApp()).build()
    }

    companion object {
        @Volatile var stopRequested = false
        private const val CHANNEL = "model_download"
        private const val NOTIF_ID = 41
        private const val NOTIF_DONE_ID = 42
    }
}
