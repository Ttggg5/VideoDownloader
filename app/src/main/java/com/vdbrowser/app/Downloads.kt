package com.vdbrowser.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory registry of downloads handled by [DownloadEngine] this session.
 * The progress dialog and the foreground service both read from here.
 */
object Downloads {

    enum class State { RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

    /** Mutable live state for one download. Bytes are atomic for cross-thread updates. */
    class Item(val id: Long, @Volatile var title: String, @Volatile var total: Long) {
        val downloaded = AtomicLong(0)

        @Volatile var state: State = State.RUNNING
        @Volatile var speed: Long = 0L          // bytes/sec, refreshed by sampleSpeed()

        // Control flags read by the download engine.
        @Volatile var paused: Boolean = false
        @Volatile var cancelled: Boolean = false
        @Volatile var resumable: Boolean = false

        // Sampling state for speed calculation.
        @Volatile var lastBytes: Long = 0L
        @Volatile var lastTime: Long = 0L
    }

    /** Immutable snapshot for the UI. */
    data class Snapshot(
        val id: Long,
        val title: String,
        val downloaded: Long,
        val total: Long,
        val state: State,
        val speed: Long,
        val resumable: Boolean
    )

    private val items = CopyOnWriteArrayList<Item>()
    private val seq = AtomicLong(1)

    fun create(title: String, total: Long): Item {
        val item = Item(seq.getAndIncrement(), title, total)
        items.add(0, item) // newest first
        return item
    }

    fun snapshot(): List<Snapshot> = items.map {
        Snapshot(it.id, it.title, it.downloaded.get(), it.total, it.state, it.speed, it.resumable)
    }

    fun activeCount(): Int = items.count { it.state == State.RUNNING }

    private fun find(id: Long): Item? = items.firstOrNull { it.id == id }

    fun pause(id: Long) = find(id)?.let {
        if (it.state == State.RUNNING) { it.paused = true; it.state = State.PAUSED }
    }

    fun resume(id: Long) = find(id)?.let {
        if (it.state == State.PAUSED) { it.paused = false; it.lastTime = 0L; it.state = State.RUNNING }
    }

    fun cancel(id: Long) = find(id)?.let { it.cancelled = true; it.paused = false }

    fun totalProgressPercent(): Int {
        var done = 0L
        var total = 0L
        for (it in items) {
            if (it.state == State.RUNNING && it.total > 0) {
                done += it.downloaded.get()
                total += it.total
            }
        }
        return if (total > 0) ((done * 100) / total).toInt() else 0
    }

    /** Refresh per-item speed from byte deltas. Call roughly twice a second. */
    fun sampleSpeed() {
        val now = System.currentTimeMillis()
        for (it in items) {
            if (it.state != State.RUNNING) {
                it.speed = 0L
                continue
            }
            if (it.lastTime == 0L) {
                it.lastTime = now
                it.lastBytes = it.downloaded.get()
                continue
            }
            val dt = now - it.lastTime
            if (dt >= 500) {
                val current = it.downloaded.get()
                it.speed = (current - it.lastBytes) * 1000 / dt
                it.lastTime = now
                it.lastBytes = current
            }
        }
    }
}
