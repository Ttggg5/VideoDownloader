package com.vdbrowser.app

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects candidate media URLs seen while a page loads.
 *
 * URLs arrive from two sources:
 *  - [WebViewClient.shouldInterceptRequest] for every network request, and
 *  - JavaScript injected after the page settles, reporting <video>/<source> tags.
 *
 * The collection is keyed by URL so duplicates are ignored, and it is cleared on
 * every new navigation so the list always reflects the current page.
 */
class MediaSniffer {

    private val items = ConcurrentHashMap<String, MediaItem>()

    private val directExt = setOf(
        "mp4", "webm", "mkv", "mov", "avi", "m4v", "3gp", "flv",
        "mp3", "m4a", "aac", "ogg", "oga", "wav", "ts"
    )
    private val streamExt = setOf("m3u8", "mpd")

    fun clear() = items.clear()

    /** Current de-duplicated list, streams first. */
    fun snapshot(): List<MediaItem> =
        items.values.sortedWith(compareByDescending<MediaItem> { it.isStream }.thenBy { it.label })

    fun count(): Int = items.size

    /** Inspect a URL and remember it if it looks like downloadable media. */
    fun consider(rawUrl: String?) {
        if (rawUrl.isNullOrBlank() || items.containsKey(rawUrl)) return
        // Blob URLs are in-memory and cannot be fetched over HTTP.
        if (rawUrl.startsWith("blob:") || rawUrl.startsWith("data:")) return

        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
        val path = uri.path ?: ""
        val ext = path.substringAfterLast('.', "").lowercase()
        val lower = rawUrl.lowercase()

        when {
            ext in streamExt || lower.contains(".m3u8") || lower.contains(".mpd") ->
                add(rawUrl, uri, if (lower.contains(".mpd")) "mpd" else "m3u8", isStream = true)

            ext in directExt ->
                add(rawUrl, uri, ext, isStream = false)

            // Heuristics for hosts that hide the extension behind query params.
            lower.contains("mime=video") || lower.contains("/videoplayback") ->
                add(rawUrl, uri, "mp4", isStream = false)
        }
    }

    private fun add(url: String, uri: Uri, type: String, isStream: Boolean) {
        val name = uri.lastPathSegment
            ?.takeIf { it.isNotBlank() && it.contains('.') }
            ?: "video.$type"
        items[url] = MediaItem(url, type, name, isStream)
    }
}
