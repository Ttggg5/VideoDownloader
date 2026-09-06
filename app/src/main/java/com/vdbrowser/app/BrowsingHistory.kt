package com.vdbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent record of visited pages, stored as JSON in SharedPreferences.
 * Revisiting a URL bumps its existing entry to the top with a fresh timestamp
 * instead of adding a duplicate row, so the list stays a de-duplicated,
 * most-recently-visited-first history rather than a raw visit log.
 */
object BrowsingHistory {

    data class Entry(val title: String, val url: String, val time: Long)

    private const val PREF = "browsing_history"
    private const val KEY = "items"
    private const val MAX = 500

    fun record(context: Context, title: String, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val list = all(context)
        list.removeAll { it.url == url }
        list.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        save(context, if (list.size > MAX) list.subList(0, MAX) else list)
    }

    fun all(context: Context): MutableList<Entry> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Entry(o.optString("t"), o.optString("u"), o.optLong("ts")))
        }
        return list
    }

    fun remove(context: Context, url: String) =
        save(context, all(context).filter { it.url != url })

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("t", it.title).put("u", it.url).put("ts", it.time))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
