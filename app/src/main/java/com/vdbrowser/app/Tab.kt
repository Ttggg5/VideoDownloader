package com.vdbrowser.app

import android.webkit.WebView

/**
 * A single browser tab: its own WebView (with independent history and scroll
 * position) and its own media sniffer so detected videos are scoped per tab.
 */
class Tab(val webView: WebView) {
    val sniffer = MediaSniffer()
    var title: String = "New Tab"
    var url: String = ""
    var favicon: android.graphics.Bitmap? = null
}
