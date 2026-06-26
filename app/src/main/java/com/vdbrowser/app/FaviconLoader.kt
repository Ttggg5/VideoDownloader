package com.vdbrowser.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches a website favicon (by domain) using Google's public favicon service,
 * off the main thread, with an in-memory cache. Results are delivered on the
 * main thread. Returns null on any failure so callers can fall back to a glyph.
 */
object FaviconLoader {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(host: String, onResult: (Bitmap?) -> Unit) {
        cache[host]?.let { onResult(it); return }
        pool.execute {
            val bmp = fetch(host)
            if (bmp != null) cache[host] = bmp
            main.post { onResult(bmp) }
        }
    }

    private fun fetch(host: String): Bitmap? {
        return try {
            val conn = URL("https://www.google.com/s2/favicons?sz=64&domain=$host")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}
