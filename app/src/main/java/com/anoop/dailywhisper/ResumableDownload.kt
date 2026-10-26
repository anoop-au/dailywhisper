package com.anoop.dailywhisper

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads one big file into [part] with HTTP Range resume, then checks its size and SHA-256 and renames it to
 * [dest]. Pure Kotlin + java.net (no Android imports) so tools/translate-harness tests exactly this code.
 * Only the file URL is contacted; nothing about the user is sent.
 */
class ResumableDownload(
    private val url: String,
    private val part: File,
    private val dest: File,
    private val size: Long,
    private val sha256: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    enum class Result { DONE, STOPPED, FAILED }

    /** Why the last [run] returned FAILED (plain words for the UI). */
    var error: String? = null
        private set

    /**
     * Blocking. [keepGoing] is polled between reads (pause, Wi-Fi lost); [progress] gets (bytes, total).
     * [verifying] is called once before the SHA-256 pass.
     */
    fun run(keepGoing: () -> Boolean, progress: (Long, Long) -> Unit, verifying: () -> Unit = {}): Result {
        error = null
        part.parentFile?.mkdirs()
        if (dest.exists() && dest.length() == size) return Result.DONE
        var have = if (part.exists()) part.length() else 0L
        if (have > size) { part.delete(); have = 0 }
        if (have < size) {
            val conn = try { open(URL(url)) } catch (e: IOException) { return fail("Couldn't reach the download server") }
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                val append = when {
                    code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> {
                        // Content-Range must start where our partial file ends, else start over.
                        val start = conn.getHeaderField("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        if (start != have) { part.delete(); return fail("The download got out of step. It will start again") }
                        true
                    }
                    code == HttpURLConnection.HTTP_OK -> { have = 0; false } // server ignored Range: from the start
                    code == 416 -> { part.delete(); return fail("The download got out of step. It will start again") }
                    else -> return fail("Download server said $code")
                }
                progress(have, size)
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            if (!keepGoing()) { out.fd.sync(); return Result.STOPPED }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have > size) { out.close(); part.delete(); return fail("The file was bigger than expected") }
                            if (have - lastReport >= 1_000_000 || have == size) { progress(have, size); lastReport = have }
                        }
                        out.fd.sync()
                    }
                }
            } catch (e: IOException) {
                return fail("Connection dropped. It will pick up where it left off")
            } finally {
                conn.disconnect()
            }
        }
        if (part.length() != size) return fail("Connection dropped. It will pick up where it left off")
        verifying()
        if (!sha256Of(part).equals(sha256, ignoreCase = true)) {
            part.delete()
            return fail("The download didn't check out, so it was removed. Try again")
        }
        if (!part.renameTo(dest)) return fail("Couldn't save the model file")
        return Result.DONE
    }

    private fun fail(msg: String): Result { error = msg; return Result.FAILED }

    companion object {
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
