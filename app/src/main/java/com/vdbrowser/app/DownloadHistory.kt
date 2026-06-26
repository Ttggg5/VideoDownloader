package com.vdbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent record of finished downloads, stored as JSON in SharedPreferences. */
object DownloadHistory {

    data class Entry(val title: String, val bytes: Long, val state: String, val time: Long)

    private const val PREF = "download_history"
    private const val KEY = "items"
    private const val MAX = 200

    fun record(context: Context, title: String, bytes: Long, state: String) {
        val list = all(context)
        list.add(0, Entry(title, bytes, state, System.currentTimeMillis()))
        save(context, if (list.size > MAX) list.subList(0, MAX) else list)
    }

    fun all(context: Context): MutableList<Entry> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Entry(o.optString("t"), o.optLong("b"), o.optString("s"), o.optLong("ts")))
        }
        return list
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("t", it.title).put("b", it.bytes)
                    .put("s", it.state).put("ts", it.time)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
