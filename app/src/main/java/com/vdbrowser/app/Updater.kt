package com.vdbrowser.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from the repo's GitHub Releases: CI publishes each signed build
 * as release "v1.0.<versionCode>" with the APK attached.
 */
object Updater {

    data class Update(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val size: Long,
        val notes: String
    )

    sealed class CheckResult {
        data class Available(val update: Update) : CheckResult()
        object UpToDate : CheckResult()
        object Failed : CheckResult()
    }

    private const val LATEST_URL =
        "https://api.github.com/repos/Ttggg5/VideoDownloader/releases/latest"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** An APK waiting for the "install unknown apps" permission. */
    var pendingInstall: File? = null
        private set

    fun check(onResult: (CheckResult) -> Unit) {
        worker.execute {
            val result = runCatching { fetchLatest() }.getOrElse { CheckResult.Failed }
            main.post { onResult(result) }
        }
    }

    private fun fetchLatest(): CheckResult {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "VideoDownloader/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            // No release published yet.
            if (conn.responseCode == 404) return CheckResult.UpToDate
            if (conn.responseCode !in 200..299) return CheckResult.Failed
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return CheckResult.Failed
            if (code <= BuildConfig.VERSION_CODE) return CheckResult.UpToDate

            val assets = json.optJSONArray("assets") ?: return CheckResult.Failed
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return CheckResult.Available(
                        Update(
                            versionCode = code,
                            versionName = tag.removePrefix("v"),
                            apkUrl = a.getString("browser_download_url"),
                            size = a.optLong("size"),
                            notes = json.optString("body").trim()
                        )
                    )
                }
            }
            return CheckResult.Failed
        } finally {
            conn.disconnect()
        }
    }

    /** Download the APK; [onProgress] gets 0..100 or -1 when the size is unknown. */
    fun download(
        context: Context,
        update: Update,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
        onDone: (File?) -> Unit
    ) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "update.apk")
        worker.execute {
            val ok = runCatching {
                file.delete()
                // GitHub redirects asset downloads to its CDN (HTTPS -> HTTPS).
                val conn = URL(update.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "VideoDownloader/${BuildConfig.VERSION_NAME}")
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    if (conn.responseCode !in 200..299) return@runCatching false
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.size
                    var done = 0L
                    var lastPct = -2
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled()) return@runCatching false
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { onProgress(pct) }
                                }
                            }
                        }
                    }
                    total <= 0 || done == total
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (!ok) file.delete()
            main.post { onDone(if (ok) file else null) }
        }
    }

    /**
     * Hand the APK to the system installer. If the user hasn't allowed this app
     * to install apps yet, opens that setting instead; call [resumePendingInstall]
     * from onResume to continue once they come back.
     */
    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstall = file
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        pendingInstall = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun resumePendingInstall(activity: Activity) {
        val file = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstall = null   // user declined; don't loop back into settings
            return
        }
        if (file.exists()) install(activity, file) else pendingInstall = null
    }
}
