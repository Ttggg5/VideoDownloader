package com.vdbrowser.app

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Builds a download request from a [MediaItem] and hands it to [DownloadService],
 * which runs the multi-connection [DownloadEngine]. The browser's cookies,
 * user-agent and the page URL (as Referer) are replayed so gated media works.
 */
object DownloadHelper {

    fun enqueue(context: Context, item: MediaItem, pageUrl: String?, userAgent: String?) {
        try {
            val fileName = sanitize(item.label, item.type)
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(DownloadService.EX_URL, item.url)
                putExtra(DownloadService.EX_NAME, fileName)
                putExtra(DownloadService.EX_MIME, mimeFor(item.type))
                putExtra(DownloadService.EX_COOKIE, CookieManager.getInstance().getCookie(item.url))
                putExtra(DownloadService.EX_UA, userAgent)
                putExtra(DownloadService.EX_REFERER, pageUrl)
            }
            ContextCompat.startForegroundService(context, intent)
            Toast.makeText(
                context,
                context.getString(R.string.download_started, item.label),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.download_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun mimeFor(type: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.lowercase())

    private fun sanitize(label: String, type: String): String {
        var name = label.substringBefore('?').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (name.isBlank()) name = "video_${System.currentTimeMillis()}.$type"
        if (!name.contains('.')) name = "$name.$type"
        return name
    }
}
