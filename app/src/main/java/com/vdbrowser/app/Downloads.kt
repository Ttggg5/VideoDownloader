package com.vdbrowser.app

import android.app.DownloadManager

/**
 * In-memory registry of downloads started this session, plus a helper to read
 * their live progress back from the system [DownloadManager].
 */
object Downloads {

    data class Entry(val id: Long, val label: String)

    /** A single download's current state, suitable for display. */
    data class Status(
        val id: Long,
        val title: String,
        val state: Int,
        val downloaded: Long,
        val total: Long
    )

    private val entries = mutableListOf<Entry>()

    /** Newest first. */
    @Synchronized
    fun register(id: Long, label: String) {
        entries.add(0, Entry(id, label))
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Query current progress for all registered downloads, newest first. */
    @Synchronized
    fun statuses(dm: DownloadManager): List<Status> {
        if (entries.isEmpty()) return emptyList()
        val ids = entries.map { it.id }.toLongArray()
        val byId = HashMap<Long, Status>()
        val query = DownloadManager.Query().setFilterById(*ids)
        dm.query(query)?.use { c ->
            val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                byId[id] = Status(
                    id = id,
                    title = c.getString(titleIdx) ?: "",
                    state = c.getInt(statusIdx),
                    downloaded = c.getLong(soFarIdx),
                    total = c.getLong(totalIdx)
                )
            }
        }
        // Preserve newest-first order; drop entries the manager no longer knows.
        return entries.mapNotNull { e ->
            byId[e.id] ?: Status(e.id, e.label, -1, 0, -1)
        }
    }
}
