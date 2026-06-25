package com.vdbrowser.app

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Resolves the byte size of a remote media resource off the main thread.
 *
 * Tries a cheap HEAD request first; if the server doesn't report a usable
 * Content-Length that way, falls back to a 1-byte ranged GET and reads the
 * total from the Content-Range header. The page's cookies, user-agent and
 * Referer are replayed so gated media reports its real size.
 */
object SizeFetcher {

    private val pool = Executors.newFixedThreadPool(3)

    fun fetch(url: String, pageUrl: String?, userAgent: String?, onResult: (Long) -> Unit) {
        pool.execute { onResult(resolve(url, pageUrl, userAgent)) }
    }

    private fun resolve(url: String, pageUrl: String?, userAgent: String?): Long {
        head(url, pageUrl, userAgent).let { if (it > 0) return it }
        return rangedGet(url, pageUrl, userAgent)
    }

    private fun open(url: String, pageUrl: String?, userAgent: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        if (!userAgent.isNullOrBlank()) conn.setRequestProperty("User-Agent", userAgent)
        if (!pageUrl.isNullOrBlank()) conn.setRequestProperty("Referer", pageUrl)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            ?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun head(url: String, pageUrl: String?, userAgent: String?): Long {
        return try {
            val conn = open(url, pageUrl, userAgent)
            conn.requestMethod = "HEAD"
            conn.connect()
            val len = conn.contentLengthLong
            conn.disconnect()
            len
        } catch (e: Exception) {
            -1L
        }
    }

    private fun rangedGet(url: String, pageUrl: String?, userAgent: String?): Long {
        return try {
            val conn = open(url, pageUrl, userAgent)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.connect()
            // "Content-Range: bytes 0-0/123456" -> total after the slash.
            val total = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?: conn.contentLengthLong
            conn.disconnect()
            total
        } catch (e: Exception) {
            -1L
        }
    }
}
