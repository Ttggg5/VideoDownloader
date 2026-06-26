package com.vdbrowser.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import java.util.concurrent.Executors

/**
 * Grabs a single preview frame for a detected video using MediaMetadataRetriever,
 * off the main thread. The page's cookies/UA/Referer are replayed as headers so
 * gated media still resolves. Best-effort: returns null on any failure (e.g.
 * some HLS variants or DRM).
 */
object ThumbnailLoader {

    private val pool = Executors.newFixedThreadPool(2)
    private const val TARGET_W = 160

    fun load(url: String, pageUrl: String?, userAgent: String?, onResult: (Bitmap?) -> Unit) {
        pool.execute { onResult(grab(url, pageUrl, userAgent)) }
    }

    private fun grab(url: String, pageUrl: String?, userAgent: String?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val headers = HashMap<String, String>()
            if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
            if (!pageUrl.isNullOrBlank()) headers["Referer"] = pageUrl
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                ?.let { headers["Cookie"] = it }

            retriever.setDataSource(url, headers)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            scale(frame)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        if (src.width <= TARGET_W) return src
        val ratio = TARGET_W.toFloat() / src.width
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, TARGET_W, h, true)
        if (scaled != src) src.recycle()
        return scaled
    }
}
