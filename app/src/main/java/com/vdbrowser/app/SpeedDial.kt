package com.vdbrowser.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Opera-style start page tiles: the sites the user added. */
object SpeedDial {

    data class Tile(val title: String, val url: String, val pinned: Boolean)

    private const val PREF = "speed_dial"

    /** Only sites the user added; visited sites are never added automatically. */
    fun tiles(context: Context, max: Int): List<Tile> = pinned(context).take(max)

    fun pinned(context: Context): List<Tile> {
        val arr = runCatching { JSONArray(prefs(context).getString("pinned", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Tile(it.optString("t"), it.optString("u"), true) }
        }.filter { it.url.isNotBlank() }
    }

    fun pin(context: Context, title: String, url: String, index: Int = -1) {
        val list = pinned(context).filter { it.url != url }.toMutableList()
        val tile = Tile(title.ifBlank { host(url).ifBlank { url } }, url, true)
        if (index in 0..list.size) list.add(index, tile) else list.add(tile)
        savePinned(context, list)
        unhide(context, host(url))
    }

    /** Returns the tile's old position so Undo can put it back. */
    fun unpin(context: Context, url: String): Int {
        val list = pinned(context).toMutableList()
        val i = list.indexOfFirst { it.url == url }
        if (i >= 0) { list.removeAt(i); savePinned(context, list) }
        return i
    }

    fun update(context: Context, oldUrl: String, title: String, url: String) {
        val list = pinned(context).map { if (it.url == oldUrl) Tile(title, url, true) else it }
        savePinned(context, list)
    }

    fun hide(context: Context, url: String) =
        prefs(context).edit().putStringSet("hidden", hidden(context) + host(url)).apply()

    fun unhide(context: Context, host: String) =
        prefs(context).edit().putStringSet("hidden", hidden(context) - host).apply()

    fun host(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    private fun hidden(context: Context): Set<String> =
        prefs(context).getStringSet("hidden", emptySet())?.toSet() ?: emptySet()

    private fun savePinned(context: Context, list: List<Tile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs(context).edit().putString("pinned", arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
