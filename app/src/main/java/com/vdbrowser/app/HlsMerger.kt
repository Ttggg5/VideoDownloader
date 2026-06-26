package com.vdbrowser.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Merges an adaptive stream (HLS .m3u8 / DASH .mpd) into a single .mp4 using
 * FFmpeg. FFmpeg fetches the playlist and all segments itself (decrypting
 * AES-128 when keyed) and remuxes by stream copy — no re-encoding, so it's fast
 * and lossless. The browser's cookies/UA/Referer are passed through as headers.
 */
object HlsMerger {

    /** Blocking; runs on a worker thread. Updates [item]. Returns true on success. */
    fun merge(context: Context, job: DownloadEngine.Job, item: Downloads.Item): Boolean {
        var uri: Uri? = null
        var outFile: File? = null
        val output: String

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, job.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return fail(item)
                // Lets FFmpeg write into a scoped-storage URI via the saf: protocol.
                output = FFmpegKitConfig.getSafParameterForWrite(context, uri)
                    ?: return fail(item)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                outFile = uniqueFile(dir, job.fileName)
                output = outFile.absolutePath
            }

            val args = buildArgs(job, output)
            val ok = runFfmpeg(args, item)

            if (uri != null) {
                if (ok) {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            } else if (outFile != null) {
                if (ok) {
                    MediaScannerConnection.scanFile(
                        context, arrayOf(outFile.absolutePath), arrayOf("video/mp4"), null
                    )
                } else {
                    outFile.delete()
                }
            }

            item.state = if (ok) Downloads.State.COMPLETED else Downloads.State.FAILED
            return ok
        } catch (e: Exception) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            outFile?.let { runCatching { it.delete() } }
            return fail(item)
        }
    }

    private fun buildArgs(job: DownloadEngine.Job, output: String): Array<String> {
        val args = mutableListOf<String>()

        val headers = buildString {
            if (!job.referer.isNullOrBlank()) append("Referer: ${job.referer}\r\n")
            if (!job.cookie.isNullOrBlank()) append("Cookie: ${job.cookie}\r\n")
        }
        if (headers.isNotEmpty()) {
            args += "-headers"; args += headers
        }
        if (!job.userAgent.isNullOrBlank()) {
            args += "-user_agent"; args += job.userAgent
        }
        args += listOf(
            "-i", job.url,
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",   // fix AAC bitstream for the mp4 container
            "-f", "mp4",
            "-y", output
        )
        return args.toTypedArray()
    }

    private fun runFfmpeg(args: Array<String>, item: Downloads.Item): Boolean {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        FFmpegKit.executeWithArgumentsAsync(
            args,
            { session -> sessionRef.set(session); latch.countDown() },
            { /* log */ },
            { stats -> item.downloaded.set(stats.size) }  // output bytes so far
        )
        latch.await()
        val returnCode = sessionRef.get()?.returnCode
        return returnCode != null && ReturnCode.isSuccess(returnCode)
    }

    private fun fail(item: Downloads.Item): Boolean {
        item.state = Downloads.State.FAILED
        return false
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }
}
