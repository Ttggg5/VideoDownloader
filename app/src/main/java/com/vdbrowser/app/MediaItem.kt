package com.vdbrowser.app

/**
 * A single downloadable media resource discovered on the current page.
 *
 * @param url      the absolute resource URL
 * @param type     file extension / container, e.g. "mp4", "m3u8"
 * @param label    a human-friendly file name for display and saving
 * @param isStream true for adaptive streams (HLS/DASH) that are not a single file
 */
data class MediaItem(
    val url: String,
    val type: String,
    val label: String,
    val isStream: Boolean
) {
    /** Resolved content length in bytes, or one of the sentinels below. */
    @Volatile
    var sizeBytes: Long = SIZE_UNKNOWN

    /** Lazily-loaded preview frame, and whether a load has been attempted. */
    @Volatile
    var thumbnail: android.graphics.Bitmap? = null

    @Volatile
    var thumbRequested: Boolean = false

    companion object {
        const val SIZE_UNKNOWN = -1L
        const val SIZE_FETCHING = -2L
    }
}
