package com.vdbrowser.app

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects candidate media URLs seen while a page loads.
 *
 * URLs arrive from two sources:
 *  - [WebViewClient.shouldInterceptRequest] for every network request, and
 *  - JavaScript injected after the page settles, reporting <video>/<source> tags.
 *
 * Ad/tracker-hosted media is ignored, and the exposed lists are de-duplicated:
 * direct files by host+path (so token-varied URLs collapse) and adaptive streams
 * by host+directory (so an HLS master and its variant playlists show as one).
 */
class MediaSniffer {

    private val items = ConcurrentHashMap<String, MediaItem>()
    private val seq = AtomicLong(1)

    // Normalized (host+path) keys of HLS variant playlists referenced by a
    // master, so they can be hidden in favour of the master.
    private val variantKeys = ConcurrentHashMap.newKeySet<String>()

    // Note: ".ts" is intentionally excluded — those are HLS segments, not
    // standalone downloads, and would flood the list.
    private val directExt = setOf(
        "mp4", "webm", "mkv", "mov", "avi", "m4v", "3gp", "flv",
        "mp3", "m4a", "aac", "ogg", "oga", "wav"
    )
    private val streamExt = setOf("m3u8", "mpd")

    fun clear() {
        items.clear()
        variantKeys.clear()
    }

    /** Record variant playlist URLs reported by an HLS master so they're hidden. */
    fun addVariants(urls: List<String>) {
        urls.forEach { variantKeys.add(norm(it)) }
    }

    private fun norm(url: String): String {
        val u = runCatching { Uri.parse(url) }.getOrNull()
        return (u?.host ?: "") + (u?.path ?: url)
    }

    /** De-duplicated list, streams first. */
    fun snapshot(): List<MediaItem> =
        deduped().sortedWith(compareByDescending<MediaItem> { it.isStream }.thenBy { it.label })

    /** Items that pass the minimum-size filter (unknown sizes always pass). */
    fun snapshotPassing(minBytes: Long): List<MediaItem> =
        snapshot().filter { passes(it, minBytes) }

    fun countPassing(minBytes: Long): Int = deduped().count { passes(it, minBytes) }

    /** True while at least one shown item's size is still being resolved. */
    fun hasPendingSizes(): Boolean =
        deduped().any { it.sizeBytes == MediaItem.SIZE_FETCHING }

    /** Whether this item is the representative kept after de-duplication. */
    fun isRepresentative(item: MediaItem): Boolean = deduped().any { it === item }

    private fun passes(item: MediaItem, minBytes: Long): Boolean =
        minBytes <= 0 || item.sizeBytes <= 0 || item.sizeBytes >= minBytes

    /** Keep one item per group; hide HLS variant playlists, prefer the master. */
    private fun deduped(): List<MediaItem> {
        val byKey = HashMap<String, MediaItem>()
        for (item in items.values.sortedBy { it.order }) {
            // Skip variant playlists referenced by a master we've already seen.
            if (item.isStream && variantKeys.contains(norm(item.url))) continue
            val key = dedupKey(item)
            val existing = byKey[key]
            when {
                existing == null -> byKey[key] = item
                // Within a host group, prefer the master playlist.
                item.isStream && item.isMaster && !existing.isMaster -> byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    private fun dedupKey(item: MediaItem): String {
        val uri = runCatching { Uri.parse(item.url) }.getOrNull()
        val host = uri?.host ?: ""
        val path = uri?.path ?: item.url
        // Streams: one per host (collapses an HLS master + its variant playlists,
        // which may live in different sub-directories). Direct files: by host+path.
        return if (item.isStream) "S|$host" else "D|$host$path"
    }

    /** Inspect a URL and remember it if it looks like media. Returns the new item, or null. */
    fun consider(rawUrl: String?): MediaItem? {
        if (rawUrl.isNullOrBlank() || items.containsKey(rawUrl)) return null
        // Blob URLs are in-memory and cannot be fetched over HTTP.
        if (rawUrl.startsWith("blob:") || rawUrl.startsWith("data:")) return null
        // Don't list videos served from ad/tracker hosts.
        if (AdBlocker.shouldBlock(rawUrl)) return null

        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val path = uri.path ?: ""
        val ext = path.substringAfterLast('.', "").lowercase()
        val lower = rawUrl.lowercase()

        return when {
            ext in streamExt || lower.contains(".m3u8") || lower.contains(".mpd") ->
                add(rawUrl, uri, if (lower.contains(".mpd")) "mpd" else "m3u8", isStream = true)

            ext in directExt ->
                add(rawUrl, uri, ext, isStream = false)

            // Heuristics for hosts that hide the extension behind query params.
            lower.contains("mime=video") || lower.contains("/videoplayback") ->
                add(rawUrl, uri, "mp4", isStream = false)

            else -> null
        }
    }

    private fun add(url: String, uri: Uri, type: String, isStream: Boolean): MediaItem {
        val name = uri.lastPathSegment
            ?.takeIf { it.isNotBlank() && it.contains('.') }
            ?: "video.$type"
        val item = MediaItem(url, type, name, isStream)
        item.order = seq.getAndIncrement()
        items[url] = item
        return item
    }
}
