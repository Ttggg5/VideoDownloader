package com.vdbrowser.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Minimal host-based ad/tracker blocker.
 *
 * A request is blocked when its host equals, or is a sub-domain of, any entry
 * in [blockedHosts]. Blocked requests are answered with an empty 200 response so
 * the page keeps rendering without the ad/tracker content. This is a curated
 * starter list — not a full filter list like EasyList — and can be extended.
 */
object AdBlocker {

    private val blockedHosts = setOf(
        // Google ads / analytics
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagservices.com",
        "googletagmanager.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        // Common ad networks / exchanges
        "adnxs.com",
        "adsystem.com",
        "amazon-adsystem.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "moatads.com",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "smartadserver.com",
        "casalemedia.com",
        "advertising.com",
        "yieldmo.com",
        "media.net",
        "zedo.com",
        "adform.net",
        "bidswitch.net",
        "3lift.com",
        "sharethrough.com",
        "teads.tv",
        // Trackers / analytics
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "branch.io",
        "amplitude.com",
        "chartbeat.com",
        "newrelic.com",
        "bugsnag.com",
        "mouseflow.com",
        "fullstory.com",
        "crazyegg.com"
    )

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )

    fun shouldBlock(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return blockedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun blockedResponse(): WebResourceResponse = emptyResponse
}
