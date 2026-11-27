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
 * Ad/tracker blocker.
 *
 * Three layers:
 *  1. Host blocking: a built-in list works immediately; on first run several
 *     public blocklists are downloaded, MERGED, cached and refreshed weekly.
 *  2. URL rules: common ad script/path patterns that ads served from
 *     first-party or CDN hosts still match.
 *  3. Cosmetic filtering: [COSMETIC_JS] hides well-known ad containers that
 *     are left behind on the page.
 */
object AdBlocker {

    private const val CACHE_FILE = "adblock_hosts_v2.txt"
    private const val OLD_CACHE_FILE = "adblock_hosts.txt"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000  // 1 week

    // All of these are downloaded and merged (hosts or ||domain^ format).
    private val SOURCES = listOf(
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    )

    private val builtIn = setOf(
        // Ad exchanges / networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsystem.com", "amazon-adsystem.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "moatads.com", "adcolony.com", "applovin.com",
        "smartadserver.com", "casalemedia.com", "advertising.com", "yieldmo.com",
        "media.net", "zedo.com", "adform.net", "bidswitch.net", "3lift.com",
        "sharethrough.com", "teads.tv", "mgid.com", "revcontent.com", "adskeeper.com",
        "adskeeper.co.uk", "contextweb.com", "indexww.com", "lijit.com", "sovrn.com",
        "33across.com", "gumgum.com", "spotxchange.com", "spotx.tv", "springserve.com",
        "adsrvr.org", "serving-sys.com", "flashtalking.com", "innovid.com",
        "yieldlab.net", "adition.com", "unrulymedia.com", "inmobi.com", "vungle.com",
        "unityads.unity3d.com", "chartboost.com", "ironsrc.com", "adkernel.com",
        "adtelligent.com", "e-planning.net", "emxdgt.com", "districtm.io",
        "admixer.net", "ad.gt", "adpushup.com", "ezoic.net", "ezodn.com",
        "carbonads.net", "buysellads.com", "adthrive.com", "mediavine.com",
        "zergnet.com", "nativo.com", "plista.com", "ligatus.com", "yandexadexchange.net",
        "an.yandex.ru", "ads.yahoo.com", "adtech.de", "admob.com",
        // Pop-up / pop-under / redirect networks
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "adsterra.com", "adsterratools.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.com", "trafficjunky.net", "trafficstars.com",
        "tsyndicate.com", "onclickads.net", "onclkds.com", "onclickmax.com",
        "hilltopads.net", "hilltopads.com", "clickadu.com", "adcash.com",
        "admaven.com", "ad-maven.com", "richpush.co", "pushground.com",
        "popmyads.com", "popunder.net", "plugrush.com", "adxpansion.com",
        "clickaine.com", "a-ads.com", "bidvertiser.com", "revenuehits.com",
        "zeropark.com", "galaksion.com", "monetag.com", "highperformanceformat.com",
        "highcpmgate.com", "realsrv.com", "magsrv.com", "displayvertising.com",
        "pemsrv.com", "trafficfactory.biz", "adspyglass.com", "ero-advertising.com",
        // Trackers
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com",
        "quantserve.com", "hotjar.com", "mixpanel.com", "segment.io", "amplitude.com",
        "chartbeat.com", "mouseflow.com", "fullstory.com", "crazyegg.com",
        "adsafeprotected.com", "doubleverify.com", "imrworldwide.com"
    )

    /** Path/script patterns that identify ad requests even on first-party hosts. */
    private val urlRules = Regex(
        "(/ads?/|/adserver|/adsrv|/pagead|adsbygoogle|/prebid|prebid[.-]|/popunder|" +
            "/advert|/ad[_-]?banner|/adframe|/ad[_-]?unit|/adview|/ad\\.js|/ads\\.js|" +
            "[?&]ad_?type=|/vast/|/vpaid|show_ads|/sponsored-ads)",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    private var hosts: Set<String> = builtIn

    private val executor = Executors.newSingleThreadExecutor()

    /** Load the cached blocklist and refresh it in the background if stale. */
    fun init(context: Context) {
        val cache = File(context.filesDir, CACHE_FILE)
        executor.execute {
            runCatching { File(context.filesDir, OLD_CACHE_FILE).delete() }
            if (cache.exists()) runCatching { loadFromCache(cache) }
            val stale = !cache.exists() ||
                System.currentTimeMillis() - cache.lastModified() > REFRESH_MS
            if (stale) refresh(cache)
        }
    }

    /** True if the host (or any parent domain) is a known ad/tracker host. */
    fun isAdHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        val set = hosts
        var h = host
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    /**
     * Whether a request should be blocked. Top-level pages are only matched by
     * host so ordinary pages whose path happens to contain "/ads/" still open.
     */
    fun shouldBlock(url: String, isMainFrame: Boolean = false): Boolean {
        if (isAdHost(url)) return true
        if (isMainFrame) return false
        return urlRules.containsMatchIn(url)
    }

    /** An empty response of a type matching the request, so pages don't error. */
    fun blockedResponse(url: String? = null): WebResourceResponse {
        val path = url?.substringBefore('?')?.lowercase().orEmpty()
        val mime = when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            else -> "text/plain"
        }
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /** Hides common ad containers; safe to run repeatedly (guarded). */
    val COSMETIC_JS: String = run {
        val selectors = listOf(
            "ins.adsbygoogle", "[id^=\"google_ads\"]", "[id^=\"div-gpt-ad\"]",
            "[id^=\"gpt-ad\"]", "[data-google-query-id]", "[data-ad-slot]",
            "[data-ad-unit]", "[data-adunit]", "iframe[src*=\"doubleclick.net\"]",
            "iframe[src*=\"googlesyndication\"]", "iframe[id^=\"google_ads_iframe\"]",
            "iframe[src*=\"/ads/\"]", "iframe[src*=\"adserver\"]",
            ".adsbygoogle", ".adsbox", ".ad-container", ".ad-slot", ".ad-banner",
            ".ad-wrapper", ".ad-unit", ".ad-placeholder", ".adunit", ".adslot",
            ".advertisement", ".advert", ".ads-banner", ".banner-ad", ".top-ad",
            ".bottom-ad", ".sidebar-ad", ".sticky-ad", ".adhesion-ad", ".dfp-ad",
            ".gpt-ad", ".trc_rbox_container", ".trc_related_container",
            "[id^=\"taboola-\"]", ".taboola", ".OUTBRAIN", "[data-widget-id^=\"AR_\"]",
            "#ad-container", "#ad_banner", "#ads", "#ad-slot", "#adbanner",
            "amp-ad", "amp-embed[type=\"taboola\"]", ".mgbox", "[id^=\"mgid_\"]",
            "[id^=\"rcjsload\"]", ".zergnet-widget", "[class*=\"sponsored-ad\"]",
            "div[aria-label=\"Advertisement\"]", "div[aria-label=\"Ads\"]"
        )
        val css = selectors.joinToString(",") + "{display:none!important;visibility:hidden!important;}"
        val escaped = css.replace("\\", "\\\\").replace("'", "\\'")
        """
        (function(){
          if (window.__vdAdCss) return;
          window.__vdAdCss = true;
          function add(){
            var root = document.head || document.documentElement;
            if (!root) return;
            if (document.getElementById('__vd_adcss')) return;
            var s = document.createElement('style');
            s.id = '__vd_adcss';
            s.textContent = '$escaped';
            root.appendChild(s);
          }
          add();
          try {
            new MutationObserver(add).observe(document.documentElement, {childList:true, subtree:false});
          } catch (e) {}
          document.addEventListener('DOMContentLoaded', add);
        })();
        """.trimIndent()
    }

    // --------------------------------------------------------------- internals

    private fun loadFromCache(file: File) {
        val set = HashSet<String>(builtIn)
        file.bufferedReader().useLines { lines ->
            lines.forEach { val d = it.trim(); if (d.isNotEmpty()) set.add(d) }
        }
        if (set.size > builtIn.size) hosts = set
    }

    private fun refresh(cache: File) {
        val merged = HashSet<String>(200_000)
        for (url in SOURCES) {
            val parsed = runCatching { download(url) }.getOrNull()
            if (parsed != null && parsed.size > 100) merged.addAll(parsed)
        }
        if (merged.size <= 100) return
        val set = HashSet<String>(merged)
        set.addAll(builtIn)
        hosts = set
        runCatching {
            cache.bufferedWriter().use { w ->
                merged.forEach { w.write(it); w.newLine() }
            }
        }
    }

    private val ws = Regex("\\s+")

    private fun download(url: String): Set<String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val set = HashSet<String>(16384)
            conn.inputStream.bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("!") || t.startsWith("@@")) {
                    return@forEachLine
                }
                val domain = if (t.startsWith("||")) {
                    // Adblock syntax: ||example.com^ (skip rules with paths/options)
                    val body = t.removePrefix("||")
                    val end = body.indexOf('^')
                    if (end <= 0 || end != body.length - 1) return@forEachLine
                    body.substring(0, end)
                } else {
                    val parts = t.substringBefore('#').trim().split(ws)
                    if (parts.size >= 2) parts[1] else parts[0]
                }.lowercase()
                if (isValidDomain(domain)) set.add(domain)
            }
            return set
        } finally {
            conn.disconnect()
        }
    }

    private val ipLike = Regex("^[0-9.:]+$")
    private val domainChars = Regex("^[a-z0-9.-]+$")
    private val skip = setOf(
        "localhost", "localhost.localdomain", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "local", "0.0.0.0"
    )

    private fun isValidDomain(d: String): Boolean =
        d.contains('.') && d !in skip && !ipLike.matches(d) && domainChars.matches(d)
}
