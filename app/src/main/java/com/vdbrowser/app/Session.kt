package com.vdbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The open tabs, saved when the app goes to the background and reopened at launch. */
object Session {

    data class SavedTab(val url: String, val title: String, val group: String?)
    data class Saved(val tabs: List<SavedTab>, val current: Int)

    private const val PREF = "session"

    fun save(context: Context, tabs: List<SavedTab>, current: Int) {
        val arr = JSONArray()
        tabs.forEach {
            arr.put(JSONObject().put("u", it.url).put("t", it.title).put("g", it.group ?: ""))
        }
        prefs(context).edit()
            .putString("tabs", arr.toString())
            .putInt("current", current)
            .apply()
    }

    fun load(context: Context): Saved? {
        val raw = prefs(context).getString("tabs", null) ?: return null
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val list = ArrayList<SavedTab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u")
            if (url.isBlank()) continue
            list.add(SavedTab(url, o.optString("t"), o.optString("g").ifBlank { null }))
        }
        if (list.isEmpty()) return null
        return Saved(list, prefs(context).getInt("current", 0).coerceIn(0, list.size - 1))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
