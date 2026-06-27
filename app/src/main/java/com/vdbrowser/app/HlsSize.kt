package com.vdbrowser.app

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * Estimates the byte size of an HLS (.m3u8) stream so it can be shown like a
 * normal video. For a master playlist it picks the highest-bandwidth variant
 * and multiplies its advertised bitrate by the stream's total duration. For a
 * media playlist it estimates the bitrate from the first segment. Best-effort:
 * returns -1 when it can't determine a size.
 */
object HlsSize {

    private val pool = Executors.newFixedThreadPool(2)
    private const val MAX_PLAYLIST = 2 * 1024 * 1024  // 2 MB safety cap

    fun estimate(url: String, pageUrl: String?, userAgent: String?, onResult: (Long) -> Unit) {
        pool.execute { onResult(runCatching { compute(url, pageUrl, userAgent) }.getOrDefault(-1L)) }
    }

    private fun compute(url: String, pageUrl: String?, ua: String?): Long {
        val text = fetchText(url, pageUrl, ua) ?: return -1L

        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = bestVariant(text, url) ?: return -1L
            val variantText = fetchText(variant.second, pageUrl, ua) ?: return -1L
            val duration = totalDuration(variantText)
            if (duration <= 0) return -1L
            return (variant.first / 8.0 * duration).toLong()
        }

        if (text.contains("#EXTINF")) {
            val duration = totalDuration(text)
            val firstDur = firstSegmentDuration(text)
            val firstSeg = firstSegmentUrl(text, url)
            if (duration <= 0 || firstDur <= 0 || firstSeg == null) return -1L
            val segLen = headLength(firstSeg, pageUrl, ua)
            if (segLen <= 0) return -1L
            val bitrate = segLen * 8.0 / firstDur          // bits per second
            return (bitrate / 8.0 * duration).toLong()
        }

        return -1L
    }

    /** Returns the highest BANDWIDTH variant as (bandwidth, absoluteUrl). */
    private fun bestVariant(master: String, baseUrl: String): Pair<Long, String>? {
        val lines = master.lines()
        var best: Pair<Long, String>? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lines.getOrNull(i + 1)?.trim()
                if (!uri.isNullOrEmpty() && !uri.startsWith("#")) {
                    val abs = resolve(baseUrl, uri)
                    if (abs != null && (best == null || bandwidth > best!!.first)) {
                        best = bandwidth to abs
                    }
                }
                i += 2
            } else {
                i++
            }
        }
        return best
    }

    private fun totalDuration(media: String): Double =
        Regex("#EXTINF:([0-9.]+)").findAll(media).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    private fun firstSegmentDuration(media: String): Double =
        Regex("#EXTINF:([0-9.]+)").find(media)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun firstSegmentUrl(media: String, baseUrl: String): String? {
        val lines = media.lines()
        for (j in lines.indices) {
            if (lines[j].trim().startsWith("#EXTINF")) {
                val uri = lines.getOrNull(j + 1)?.trim()
                if (!uri.isNullOrEmpty() && !uri.startsWith("#")) return resolve(baseUrl, uri)
            }
        }
        return null
    }

    private fun resolve(baseUrl: String, ref: String): String? = runCatching {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else URI(baseUrl).resolve(ref).toString()
    }.getOrNull()

    private fun fetchText(url: String, pageUrl: String?, ua: String?): String? {
        return try {
            val conn = open(url, pageUrl, ua)
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText().take(MAX_PLAYLIST) }
        } catch (e: Exception) {
            null
        }
    }

    private fun headLength(url: String, pageUrl: String?, ua: String?): Long {
        return try {
            val conn = open(url, pageUrl, ua)
            conn.requestMethod = "HEAD"
            conn.connect()
            val len = conn.contentLengthLong
            conn.disconnect()
            len
        } catch (e: Exception) {
            -1L
        }
    }

    private fun open(url: String, pageUrl: String?, ua: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        if (!ua.isNullOrBlank()) conn.setRequestProperty("User-Agent", ua)
        if (!pageUrl.isNullOrBlank()) conn.setRequestProperty("Referer", pageUrl)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            ?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }
}
