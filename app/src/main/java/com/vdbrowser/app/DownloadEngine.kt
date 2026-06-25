package com.vdbrowser.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-connection downloader. When the server supports HTTP range requests,
 * the file is split into several byte-range segments fetched in parallel and
 * written into one destination via positional [FileChannel] writes, which is
 * typically much faster than a single sequential stream. Falls back to a single
 * stream when ranges aren't supported.
 */
object DownloadEngine {

    private const val MAX_SEGMENTS = 6
    private const val MIN_SEGMENT = 1L * 1024 * 1024   // 1 MB
    private const val BUFFER = 64 * 1024

    data class Job(
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val cookie: String?,
        val userAgent: String?,
        val referer: String?
    )

    /** Blocking. Runs on a worker thread; updates [item]. Returns true on success. */
    fun download(context: Context, job: Job, item: Downloads.Item): Boolean {
        return try {
            val probe = probe(job)
            if (probe.size > 0) item.total = probe.size

            val dest = openDestination(context, job.fileName, job.mimeType)
                ?: throw IOException("cannot open destination")
            try {
                if (probe.acceptRanges && probe.size > MIN_SEGMENT * 2) {
                    multi(job, item, dest.channel, probe.size)
                } else {
                    single(job, item, dest.channel)
                }
                dest.finish()
            } catch (e: Exception) {
                dest.abort()
                throw e
            }
            item.state = Downloads.State.COMPLETED
            true
        } catch (e: Exception) {
            item.state = Downloads.State.FAILED
            false
        }
    }

    // ------------------------------------------------------------- probing

    private data class Probe(val size: Long, val acceptRanges: Boolean)

    private fun probe(job: Job): Probe {
        val conn = open(job)
        conn.setRequestProperty("Range", "bytes=0-0")
        return try {
            conn.connect()
            when (conn.responseCode) {
                206 -> {
                    // "Content-Range: bytes 0-0/123456"
                    val total = conn.getHeaderField("Content-Range")
                        ?.substringAfterLast('/', "")?.toLongOrNull() ?: -1L
                    Probe(total, total > 0)
                }
                else -> {
                    val total = conn.contentLengthLong
                    val accept = conn.getHeaderField("Accept-Ranges")
                        ?.equals("bytes", true) == true
                    Probe(total, accept && total > 0)
                }
            }
        } catch (e: Exception) {
            Probe(-1L, false)
        } finally {
            conn.disconnect()
        }
    }

    // ----------------------------------------------------------- transfers

    private fun multi(job: Job, item: Downloads.Item, channel: FileChannel, size: Long) {
        val segments = min(MAX_SEGMENTS, max(2, (size / MIN_SEGMENT).toInt()))
        val part = size / segments
        val pool = Executors.newFixedThreadPool(segments)
        try {
            val futures = (0 until segments).map { i ->
                val start = i * part
                val end = if (i == segments - 1) size - 1 else start + part - 1
                pool.submit { downloadRange(job, start, end, channel, item.downloaded) }
            }
            futures.forEach { it.get() } // rethrows the first segment failure
        } finally {
            pool.shutdownNow()
        }
        channel.force(true)
    }

    private fun downloadRange(
        job: Job, start: Long, end: Long, channel: FileChannel, counter: AtomicLong
    ) {
        val conn = open(job)
        conn.setRequestProperty("Range", "bytes=$start-$end")
        try {
            conn.connect()
            if (conn.responseCode != 206) throw IOException("range not honored: ${conn.responseCode}")
            conn.inputStream.use { input ->
                val buf = ByteArray(BUFFER)
                var pos = start
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    writeFully(channel, buf, read, pos)
                    pos += read
                    counter.addAndGet(read.toLong())
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun single(job: Job, item: Downloads.Item, channel: FileChannel) {
        val conn = open(job)
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            if (item.total <= 0) item.total = conn.contentLengthLong
            conn.inputStream.use { input ->
                val buf = ByteArray(BUFFER)
                var pos = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    writeFully(channel, buf, read, pos)
                    pos += read
                    item.downloaded.addAndGet(read.toLong())
                }
            }
            channel.force(true)
        } finally {
            conn.disconnect()
        }
    }

    private fun writeFully(channel: FileChannel, data: ByteArray, len: Int, position: Long) {
        val bb = ByteBuffer.wrap(data, 0, len)
        var pos = position
        while (bb.hasRemaining()) {
            pos += channel.write(bb, pos)
        }
    }

    // --------------------------------------------------------- destination

    private class Dest(
        val channel: FileChannel,
        val finish: () -> Unit,
        val abort: () -> Unit
    )

    private fun openDestination(context: Context, fileName: String, mime: String?): Dest? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            Dest(
                channel = channel,
                finish = {
                    runCatching { channel.force(true); channel.close(); pfd.close() }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                },
                abort = {
                    runCatching { channel.close(); pfd.close() }
                    runCatching { resolver.delete(uri, null, null) }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = uniqueFile(dir, fileName)
            val raf = RandomAccessFile(file, "rw")
            val channel = raf.channel
            Dest(
                channel = channel,
                finish = {
                    runCatching { channel.force(true); raf.close() }
                    MediaScannerConnection.scanFile(
                        context, arrayOf(file.absolutePath), mime?.let { arrayOf(it) }, null
                    )
                },
                abort = { runCatching { raf.close() }; runCatching { file.delete() } }
            )
        }
    }

    private fun uniqueFile(dir: java.io.File, name: String): java.io.File {
        var candidate = java.io.File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = java.io.File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    // ------------------------------------------------------------ network

    private fun open(job: Job): HttpURLConnection {
        val conn = URL(job.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        if (!job.userAgent.isNullOrBlank()) conn.setRequestProperty("User-Agent", job.userAgent)
        if (!job.referer.isNullOrBlank()) conn.setRequestProperty("Referer", job.referer)
        if (!job.cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", job.cookie)
        return conn
    }
}
