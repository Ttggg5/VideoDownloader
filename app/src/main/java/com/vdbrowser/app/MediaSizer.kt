package com.vdbrowser.app

/** Resolves a detected item's size in the background (drives the badge and size filter). */
object MediaSizer {

    /** [onUpdate] runs on a background thread once the size (or variant info) is known. */
    fun fetch(item: MediaItem, sniffer: MediaSniffer, pageUrl: String?, userAgent: String?, onUpdate: () -> Unit) {
        if (item.sizeBytes != MediaItem.SIZE_UNKNOWN) return
        // Direct files: only size the representative copy. Streams: analyze all,
        // so we can discover which playlist is the master.
        if (!item.isStream && !sniffer.isRepresentative(item)) return
        item.sizeBytes = MediaItem.SIZE_FETCHING
        if (item.isStream) {
            HlsSize.analyze(item.url, pageUrl, userAgent) { info ->
                item.isMaster = info.isMaster
                item.downloadUrl = info.variantUrl
                if (info.variants.isNotEmpty()) sniffer.addVariants(info.variants)
                item.sizeBytes = if (info.size > 0) info.size else MediaItem.SIZE_UNKNOWN
                onUpdate()
            }
        } else {
            SizeFetcher.fetch(item.url, pageUrl, userAgent) { size ->
                item.sizeBytes = if (size > 0) size else MediaItem.SIZE_UNKNOWN
                onUpdate()
            }
        }
    }
}
