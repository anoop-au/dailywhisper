package com.anoop.dailywhisper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** Starts (or resumes) the Hy-MT download, asking once for notification permission so progress shows in the shade. */
fun Activity.startModelDownload() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !uiState().getBoolean("asked_notifications", false)) {
        uiState().edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0) // optional: the download runs either way
    }
    ModelDownloads.start(this)
}

private fun mb(b: Long) = "${b / 1_000_000} MB"

/** One calm line about the model download, for Setup and Settings. */
fun modelStatusText(s: ModelDownloads.State): String = when (s.status) {
    ModelDownloads.Status.NOT_STARTED -> "Not downloaded (440 MB)"
    ModelDownloads.Status.DOWNLOADING -> "Downloading… ${s.percent}%  ·  ${mb(s.bytes)} of ${mb(s.total)}" +
        (s.message?.let { "\n$it" } ?: "")
    ModelDownloads.Status.WAITING_WIFI -> "Waiting for Wi-Fi (${s.percent}% so far)"
    ModelDownloads.Status.PAUSED -> if (s.bytes > 0) "Paused at ${s.percent}%" else "Not downloaded (440 MB)"
    ModelDownloads.Status.VERIFYING -> "Checking the file…"
    ModelDownloads.Status.READY -> "Ready on this phone"
    ModelDownloads.Status.FAILED -> (s.message ?: "Download stopped") + if (s.bytes > 0) " (${s.percent}% kept)" else ""
}
