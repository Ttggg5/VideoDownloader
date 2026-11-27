package com.vdbrowser.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Loads a page in an invisible WebView and collects the videos it plays, the same
 * way the browser tabs do (network sniffing + DOM scan), without showing the page.
 */
class PageScanner(
    private val host: FrameLayout,
    private val pageUrl: String,
    private val onChange: () -> Unit
) {
    val sniffer = MediaSniffer()
    val userAgent: String

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    /** Re-scan the DOM and nudge players to start, a few times while the page settles. */
    private val rescan = object : Runnable {
        override fun run() {
            web?.evaluateJavascript(MediaScanJs.START_PLAYBACK, null)
            web?.evaluateJavascript(MediaScanJs.SCRIPT, null)
            main.postDelayed(this, 2000)
        }
    }

    init {
        val ctx = host.context
        val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val desktop = prefs.getBoolean("desktop", false)
        userAgent = if (desktop) MainActivity.DESKTOP_UA
        else WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        // The shared link may itself be a video file: no page to load then.
        sniffer.consider(pageUrl)?.let { item ->
            MediaSizer.fetch(item, sniffer, null, userAgent) { main.post(onChange) }
            onChange()
            return
        }

        val ctx = host.context
        val adBlock = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("adblock", true)
        AdBlocker.init(ctx.applicationContext)

        val w = WebView(ctx)
        web = w
        // Laid out at a normal size but invisible: many pages only run their
        // players once they actually have a size on screen.
        w.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        w.alpha = 0f
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "VDBridge")

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (adBlock && AdBlocker.shouldBlock(url)) return AdBlocker.blockedResponse()
                found(url)
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                main.removeCallbacks(rescan)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                main.removeCallbacks(rescan)
                main.post(rescan)
            }
        }
        host.addView(w, 0)
        w.loadUrl(pageUrl)
    }

    /** Runs on any thread. */
    private fun found(url: String) {
        val item = sniffer.consider(url) ?: return
        MediaSizer.fetch(item, sniffer, pageUrl, userAgent) { main.post(onChange) }
        main.post(onChange)
    }

    fun stop() {
        main.removeCallbacks(rescan)
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onMediaFound(url: String) = found(url)
    }
}
