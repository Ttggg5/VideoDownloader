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

    /** The suggested file name shown in the rename dialog. */
    fun defaultName(item: MediaItem): String =
        sanitizeToExt(item.label, extFor(item))

    /**
     * @param fileName optional user-chosen name; the default is used when null.
     */
    fun enqueue(
        context: Context,
        item: MediaItem,
        pageUrl: String?,
        userAgent: String?,
        fileName: String? = null
    ) {
        try {
            // Adaptive streams are remuxed by FFmpeg into a single .mp4. Download
            // the specific variant we measured, so size and file match.
            val downloadUrl = if (item.isStream) item.downloadUrl ?: item.url else item.url
            val finalName = sanitizeToExt(fileName ?: item.label, extFor(item))
            val mime = if (item.isStream) "video/mp4" else mimeFor(item.type)
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(DownloadService.EX_URL, downloadUrl)
                putExtra(DownloadService.EX_NAME, finalName)
                putExtra(DownloadService.EX_MIME, mime)
                putExtra(DownloadService.EX_COOKIE, CookieManager.getInstance().getCookie(downloadUrl))
                putExtra(DownloadService.EX_UA, userAgent)
                putExtra(DownloadService.EX_REFERER, pageUrl)
                putExtra(DownloadService.EX_STREAM, item.isStream)
                // Seed the progress total with the size already estimated for the
                // popup, so the address-bar indicator can show real percentage
                // instead of falling back to indeterminate for every HLS download.
                if (item.sizeBytes > 0) putExtra(DownloadService.EX_SIZE_HINT, item.sizeBytes)
            }
            ContextCompat.startForegroundService(context, intent)
            Toast.makeText(
                context,
                context.getString(R.string.download_started, finalName),
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

    private fun extFor(item: MediaItem): String = if (item.isStream) "mp4" else item.type

    private fun mimeFor(type: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.lowercase())

    // Characters that are actually illegal in a file name on Android's storage
    // (FAT/exFAT reserved chars + path separators + control chars). Everything
    // else — CJK, Cyrillic, Arabic, accented Latin, emoji, etc. — is kept as-is;
    // the previous ASCII-only filter replaced every such character with "_".
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private fun sanitizeToExt(rawName: String, ext: String): String {
        var name = rawName.substringBefore('?').trim()
            .replace(illegalChars, "_")
            .trim(' ', '.') // trailing dots/spaces are stripped/rejected on some filesystems
        if (name.isBlank()) name = "video_${System.currentTimeMillis()}"
        name = truncateToBytes(name, MAX_BASENAME_BYTES)
        if (!name.endsWith(".$ext", ignoreCase = true)) name = "$name.$ext"
        return name
    }

    /** Trim to a UTF-8 byte budget without splitting a multi-byte character. */
    private fun truncateToBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        var end = s.length
        while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return s.substring(0, end)
    }

    private const val MAX_BASENAME_BYTES = 180
}
