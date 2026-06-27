package com.vdbrowser.app

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Analyzes an HLS (.m3u8) playlist to: tell a master from a media playlist,
 * list a master's variant playlists (so they can be hidden), choose the
 * variant to download, and estimate the byte size.
 *
 * The size is measured, not guessed: it sums the actual Content-Length of the
 * chosen variant's segments (sampled evenly and scaled by duration for long
 * playlists), so it closely matches the file you'll actually download.
 */
object HlsSize {

    data class Info(
        val size: Long,
        val variants: List<String>,
        val isMaster: Boolean,
        val variantUrl: String?
    )

    private val pool = Executors.newFixedThreadPool(2)
    private const val MAX_PLAYLIST = 8 * 1024 * 1024
    private const val SAMPLE_CAP = 60   // segments HEAD-ed to estimate size

    private data class Variant(val bandwidth: Long, val url: String)

    fun analyze(url: String, pageUrl: String?, userAgent: String?, onResult: (Info) -> Unit) {
        pool.execute {
            onResult(runCatching { compute(url, pageUrl, userAgent) }
                .getOrDefault(Info(-1L, emptyList(), false, null)))
        }
    }

    private fun compute(url: String, pageUrl: String?, ua: String?): Info {
        val text = fetchText(url, pageUrl, ua) ?: return Info(-1L, emptyList(), false, null)

        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(text, url)
            val urls = variants.map { it.url }
            val best = variants.maxByOrNull { it.bandwidth }
                ?: return Info(-1L, urls, true, null)
            val variantText = fetchText(best.url, pageUrl, ua)
            var size = variantText?.let { sizeOfMedia(it, best.url, pageUrl, ua) } ?: -1L
            if (size <= 0 && variantText != null && best.bandwidth > 0) {
                val dur = totalDuration(variantText)
                if (dur > 0) size = (best.bandwidth / 8.0 * dur).toLong()
            }
            return Info(size, urls, true, best.url)
        }

        if (text.contains("#EXTINF")) {
            return Info(sizeOfMedia(text, url, pageUrl, ua), emptyList(), false, null)
        }

        return Info(-1L, emptyList(), false, null)
    }

    /** Sum the real segment sizes (sampled for long playlists), scaled by duration. */
    private fun sizeOfMedia(media: String, base: String, pageUrl: String?, ua: String?): Long {
        val segments = parseSegments(media, base)
        val totalDur = segments.sumOf { it.second }
        if (segments.isEmpty() || totalDur <= 0) return -1L

        val sample = if (segments.size <= SAMPLE_CAP) segments
        else (0 until SAMPLE_CAP).map { segments[(it.toLong() * segments.size / SAMPLE_CAP).toInt()] }

        val workers = Executors.newFixedThreadPool(min(8, sample.size))
        try {
            val tasks = sample.map { (u, d) -> Callable { segLength(u, pageUrl, ua) to d } }
            var sumBytes = 0L
            var sumDur = 0.0
            for (future in workers.invokeAll(tasks)) {
                val (len, dur) = runCatching { future.get() }.getOrNull() ?: continue
                if (len > 0) { sumBytes += len; sumDur += dur }
            }
            if (sumBytes <= 0 || sumDur <= 0) return -1L
            return (sumBytes / sumDur * totalDur).toLong()
        } finally {
            workers.shutdownNow()
        }
    }

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
                    resolve(baseUrl, uri)?.let { out.add(Variant(avg ?: peak ?: 0L, it)) }
                }
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    private fun parseSegments(media: String, baseUrl: String): List<Pair<String, Double>> {
        val lines = media.lines()
        val out = ArrayList<Pair<String, Double>>()
        var i = 0
        while (i < lines.size) {
            val m = Regex("#EXTINF:([0-9.]+)").find(lines[i].trim())
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

    /** Segment length via HEAD, falling back to a 1-byte ranged GET. */
    private fun segLength(url: String, pageUrl: String?, ua: String?): Long {
        headLength(url, pageUrl, ua).let { if (it > 0) return it }
        return try {
            val conn = open(url, pageUrl, ua)
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.connect()
            val total = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")?.toLongOrNull() ?: conn.contentLengthLong
            conn.disconnect()
            total
        } catch (e: Exception) {
            -1L
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
