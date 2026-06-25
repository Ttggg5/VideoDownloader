package com.vdbrowser.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast

/**
 * Hands a [MediaItem] off to the system [DownloadManager], replaying the browser's
 * authentication context (cookies), user-agent and the page URL as Referer so that
 * hosts that gate media behind those headers still serve the file.
 */
object DownloadHelper {

    /** Enqueue a download and return its DownloadManager id, or -1 on failure. */
    fun enqueue(context: Context, item: MediaItem, pageUrl: String?, userAgent: String?): Long {
        try {
            val request = DownloadManager.Request(Uri.parse(item.url)).apply {
                CookieManager.getInstance().getCookie(item.url)?.let {
                    if (it.isNotBlank()) addRequestHeader("Cookie", it)
                }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                if (!pageUrl.isNullOrBlank()) addRequestHeader("Referer", pageUrl)

                val fileName = sanitize(item.label, item.type)
                setTitle(fileName)
                setDescription(context.getString(R.string.download_description))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            Downloads.register(id, sanitize(item.label, item.type))
            Toast.makeText(
                context,
                context.getString(R.string.download_started, item.label),
                Toast.LENGTH_SHORT
            ).show()
            return id
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.download_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            return -1L
        }
    }

    private fun sanitize(label: String, type: String): String {
        var name = label.substringBefore('?').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (name.isBlank()) name = "video_${System.currentTimeMillis()}.$type"
        if (!name.contains('.')) name = "$name.$type"
        return name
    }
}
