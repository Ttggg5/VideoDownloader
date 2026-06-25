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
)
