package com.vdbrowser.app

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Host-based ad/tracker blocker.
 *
 * A small built-in list works immediately; on first run a comprehensive
 * blocklist (thousands of ad/tracker domains) is downloaded on the device,
 * cached, and refreshed weekly — this is what makes blocking actually
 * effective. A request is blocked when its host, or any parent domain, is in
 * the set; blocked requests get an empty response so the page keeps rendering.
 */
object AdBlocker {

    private const val CACHE_FILE = "adblock_hosts.txt"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000  // 1 week

    // Hosts-format ad/tracker lists, tried in order. These resolve on-device.
    private val SOURCES = listOf(
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    private val builtIn = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagservices.com", "googletagmanager.com",
        "adservice.google.com", "adnxs.com", "adsystem.com", "amazon-adsystem.com",
        "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "moatads.com", "adcolony.com", "applovin.com",
        "smartadserver.com", "casalemedia.com", "advertising.com", "yieldmo.com",
        "media.net", "zedo.com", "adform.net", "bidswitch.net", "3lift.com",
        "sharethrough.com", "teads.tv", "scorecardresearch.com", "quantserve.com",
        "hotjar.com", "mixpanel.com", "segment.io", "branch.io", "amplitude.com",
        "chartbeat.com", "newrelic.com", "bugsnag.com", "mouseflow.com",
        "fullstory.com", "crazyegg.com"
    )

    @Volatile
    private var hosts: Set<String> = builtIn

    private val executor = Executors.newSingleThreadExecutor()

    /** Load the cached blocklist and refresh it in the background if stale. */
    fun init(context: Context) {
        val cache = File(context.filesDir, CACHE_FILE)
        executor.execute {
            if (cache.exists()) runCatching { loadFromCache(cache) }
            val stale = !cache.exists() ||
                System.currentTimeMillis() - cache.lastModified() > REFRESH_MS
            if (stale) refresh(cache)
        }
    }

    fun shouldBlock(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        val set = hosts
        var h = host
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    // --------------------------------------------------------------- internals

    private fun loadFromCache(file: File) {
        val set = HashSet<String>(builtIn)
        file.bufferedReader().useLines { lines ->
            lines.forEach { val d = it.trim(); if (d.isNotEmpty()) set.add(d) }
        }
        if (set.size > builtIn.size) hosts = set
    }

    private fun refresh(cache: File) {
        for (url in SOURCES) {
            val parsed = runCatching { download(url) }.getOrNull()
            if (parsed != null && parsed.size > 100) {
                val set = HashSet<String>(builtIn)
                set.addAll(parsed)
                hosts = set
                runCatching {
                    cache.bufferedWriter().use { w ->
                        parsed.forEach { w.write(it); w.newLine() }
                    }
                }
                return
            }
        }
    }

    private fun download(url: String): Set<String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val set = HashSet<String>(8192)
            conn.inputStream.bufferedReader().forEachLine { line ->
                val cleaned = line.substringBefore('#').trim()
                if (cleaned.isEmpty()) return@forEachLine
                val parts = cleaned.split(Regex("\\s+"))
                val domain = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                if (isValidDomain(domain)) set.add(domain)
            }
            return set
        } finally {
            conn.disconnect()
        }
    }

    private val ipLike = Regex("^[0-9.:]+$")
    private val skip = setOf(
        "localhost", "localhost.localdomain", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "local"
    )

    private fun isValidDomain(d: String): Boolean =
        d.contains('.') && d !in skip && !ipLike.matches(d) && !d.startsWith("#")
}
