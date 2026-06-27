package com.vdbrowser.app

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * Analyzes an HLS (.m3u8) playlist to (a) tell a master from a media playlist,
 * (b) list a master's variant playlists so they can be hidden, and (c) estimate
 * the stream's byte size so it shows like a normal video.
 *
 * Size estimates use the *average* bitrate where available (AVERAGE-BANDWIDTH
 * for a master; sampled segment sizes for a media playlist) multiplied by the
 * total duration — far closer to the real file size than peak bitrate.
 */
object HlsSize {

    data class Info(val size: Long, val variants: List<String>, val isMaster: Boolean)

    private val pool = Executors.newFixedThreadPool(2)
    private const val MAX_PLAYLIST = 4 * 1024 * 1024

    private data class Variant(val bandwidth: Long, val url: String)

    fun analyze(url: String, pageUrl: String?, userAgent: String?, onResult: (Info) -> Unit) {
        pool.execute {
            onResult(runCatching { compute(url, pageUrl, userAgent) }
                .getOrDefault(Info(-1L, emptyList(), false)))
        }
    }

    private fun compute(url: String, pageUrl: String?, ua: String?): Info {
        val text = fetchText(url, pageUrl, ua) ?: return Info(-1L, emptyList(), false)

        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(text, url)
            val urls = variants.map { it.url }
            val best = variants.maxByOrNull { it.bandwidth }
                ?: return Info(-1L, urls, true)
            val variantText = fetchText(best.url, pageUrl, ua)
            val duration = variantText?.let { totalDuration(it) } ?: 0.0
            val size = if (duration > 0 && best.bandwidth > 0)
                (best.bandwidth / 8.0 * duration).toLong() else -1L
            return Info(size, urls, true)
        }

        if (text.contains("#EXTINF")) {
            return Info(estimateMediaSize(text, url, pageUrl, ua), emptyList(), false)
        }

        return Info(-1L, emptyList(), false)
    }

    /** Variants with AVERAGE-BANDWIDTH (preferred) or BANDWIDTH, absolute URLs. */
    private fun parseVariants(master: String, baseUrl: String): List<Variant> {
        val lines = master.lines()
        val out = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val avg = Regex("AVERAGE-BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                val peak = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                val uri = lines.getOrNull(i + 1)?.trim()
                if (!uri.isNullOrEmpty() && !uri.startsWith("#")) {
                    val abs = resolve(baseUrl, uri)
                    if (abs != null) out.add(Variant(avg ?: peak ?: 0L, abs))
                }
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    /** Sample a few segments to estimate bytes/sec, then scale by total duration. */
    private fun estimateMediaSize(media: String, baseUrl: String, pageUrl: String?, ua: String?): Long {
        val segments = parseSegments(media, baseUrl)
        val duration = segments.sumOf { it.second }
        if (segments.isEmpty() || duration <= 0) return -1L

        val sampleIdx = listOf(0, segments.size / 2, segments.size - 1).distinct()
        var sumBytes = 0L
        var sumDur = 0.0
        for (idx in sampleIdx) {
            val (segUrl, segDur) = segments[idx]
            val len = headLength(segUrl, pageUrl, ua)
            if (len > 0 && segDur > 0) {
                sumBytes += len
                sumDur += segDur
            }
        }
        if (sumBytes <= 0 || sumDur <= 0) return -1L
        return (sumBytes / sumDur * duration).toLong()
    }

    private fun parseSegments(media: String, baseUrl: String): List<Pair<String, Double>> {
        val lines = media.lines()
        val out = ArrayList<Pair<String, Double>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val m = Regex("#EXTINF:([0-9.]+)").find(line)
            if (m != null) {
                val dur = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val uri = lines.getOrNull(i + 1)?.trim()
                if (!uri.isNullOrEmpty() && !uri.startsWith("#")) {
                    resolve(baseUrl, uri)?.let { out.add(it to dur) }
                }
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    private fun totalDuration(media: String): Double =
        Regex("#EXTINF:([0-9.]+)").findAll(media).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

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
