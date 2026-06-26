package com.vdbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent bookmarks, stored as a JSON array in SharedPreferences. */
object Bookmarks {

    data class Item(val title: String, val url: String)

    private const val PREF = "bookmarks"
    private const val KEY = "items"

    fun all(context: Context): MutableList<Item> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Item(o.optString("t"), o.optString("u")))
        }
        return list
    }

    fun isBookmarked(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return all(context).any { it.url == url }
    }

    fun add(context: Context, title: String, url: String) {
        if (url.isBlank()) return
        val list = all(context)
        if (list.none { it.url == url }) {
            list.add(0, Item(title.ifBlank { url }, url))
            save(context, list)
        }
    }

    fun remove(context: Context, url: String) {
        save(context, all(context).filter { it.url != url })
    }

    private fun save(context: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
