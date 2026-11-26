package com.vdbrowser.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One row in the address-bar suggestion list. */
data class Suggestion(val kind: Kind, val title: String, val target: String) {
    enum class Kind { HISTORY, BOOKMARK, SEARCH }
}

/** Search-engine autocomplete (OpenSearch "[query, [suggestions...]]" format). */
object SearchSuggest {

    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fetch(engine: String, query: String, onResult: (List<String>) -> Unit) {
        pool.execute {
            val list = runCatching { load(engine, query) }.getOrDefault(emptyList())
            main.post { onResult(list) }
        }
    }

    private fun load(engine: String, query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = when (engine) {
            "bing" -> "https://api.bing.com/osjson.aspx?query=$q"
            "ddg" -> "https://duckduckgo.com/ac/?q=$q&type=list"
            else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VideoDownloader")
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body).optJSONArray(1) ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } finally {
            conn.disconnect()
        }
    }
}
