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
    var preview: android.graphics.Bitmap? = null
    /** True for a fresh "New tab" until it loads its first page. */
    var showingStart: Boolean = false
    /** Restored from the last session but not loaded until the tab is first shown. */
    var pendingUrl: String? = null
    var groupId: String? = null
    /** Last finger-down position on the page, in view pixels. */
    var lastTouchX: Float = 0f
    var lastTouchY: Float = 0f
}
