package com.vdbrowser.app

/** Human-readable byte size, e.g. 1536 -> "1.5 KB". Returns "—" when unknown. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

/**
 * The first web link in shared text, which can be a bare link or a sentence with
 * one buried in it ("check this out: https://..."). Null when there is none.
 */
fun extractUrl(text: String): String? {
    val matcher = android.util.Patterns.WEB_URL.matcher(text)
    return if (matcher.find()) text.substring(matcher.start(), matcher.end()) else null
}
