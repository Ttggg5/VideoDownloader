package com.vdbrowser.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that runs [DownloadEngine] jobs so downloads continue even
 * if the user leaves the app, and keeps a progress notification up to date.
 */
class DownloadService : Service() {

    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EX_URL) ?: return START_NOT_STICKY
        val job = DownloadEngine.Job(
            url = url,
            fileName = intent.getStringExtra(EX_NAME) ?: "video",
            mimeType = intent.getStringExtra(EX_MIME),
            cookie = intent.getStringExtra(EX_COOKIE),
            userAgent = intent.getStringExtra(EX_UA),
            referer = intent.getStringExtra(EX_REFERER)
        )

        val isStream = intent.getBooleanExtra(EX_STREAM, false)
        val item = Downloads.create(job.fileName, -1)
        active.incrementAndGet()
        startForeground(NOTIF_ID, buildNotification())
        ensureUpdater()

        pool.execute {
            if (isStream) {
                HlsMerger.merge(applicationContext, job, item)
            } else {
                DownloadEngine.download(applicationContext, job, item)
            }
            if (active.decrementAndGet() <= 0) handler.post { finishIfIdle() }
        }
        return START_NOT_STICKY
    }

    private fun ensureUpdater() {
        if (updating) return
        updating = true
        handler.post(object : Runnable {
            override fun run() {
                Downloads.sampleSpeed()
                if (active.get() > 0) {
                    notify(buildNotification())
                    handler.postDelayed(this, 700)
                } else {
                    updating = false
                }
            }
        })
    }

    private fun finishIfIdle() {
        if (active.get() > 0) return
        // Leave a final "complete" notification behind after the service stops.
        notify(buildFinalNotification())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(false)
        }
        stopSelf()
    }

    // ----------------------------------------------------------- notifications

    private fun buildNotification(): android.app.Notification {
        val count = active.get()
        val percent = Downloads.totalProgressPercent()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_downloading, count))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        if (percent > 0) {
            builder.setProgress(100, percent, false)
            builder.setContentText("$percent%")
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun buildFinalNotification(): android.app.Notification {
        val failed = Downloads.snapshot().count { it.state == Downloads.State.FAILED }
        val text = if (failed > 0) getString(R.string.notif_done_some_failed, failed)
        else getString(R.string.notif_done)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun contentIntent() = android.app.PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(notification: android.app.Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS not granted; downloads still proceed.
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EX_URL = "url"
        const val EX_NAME = "name"
        const val EX_MIME = "mime"
        const val EX_COOKIE = "cookie"
        const val EX_UA = "ua"
        const val EX_REFERER = "referer"
        const val EX_STREAM = "stream"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1001
    }
}
