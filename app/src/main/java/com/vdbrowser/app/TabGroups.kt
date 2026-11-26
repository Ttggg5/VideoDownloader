package com.vdbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Named, colored tab groups (Chrome-style), saved alongside the session. */
object TabGroups {

    data class Group(val id: String, val name: String, val color: Int)

    /** Group colors (ARGB), chosen by index. */
    val COLORS = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFFFF4D8D.toInt(), 0xFF2979FF.toInt(),
        0xFF00C853.toInt(), 0xFFFF9100.toInt(), 0xFF00BFA5.toInt()
    )

    private const val PREF = "tab_groups"
    private var cache: MutableList<Group>? = null

    fun all(context: Context): List<Group> = load(context)

    fun get(context: Context, id: String?): Group? =
        if (id == null) null else load(context).firstOrNull { it.id == id }

    fun create(context: Context, name: String, color: Int): Group {
        val g = Group("g" + System.currentTimeMillis().toString(36), name, color)
        load(context).add(g)
        save(context)
        return g
    }

    fun rename(context: Context, id: String, name: String) {
        val list = load(context)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = list[i].copy(name = name); save(context) }
    }

    /** Drop groups that no tab uses any more. */
    fun prune(context: Context, inUse: Set<String>) {
        val list = load(context)
        if (list.removeAll { it.id !in inUse }) save(context)
    }

    private fun load(context: Context): MutableList<Group> {
        cache?.let { return it }
        val raw = prefs(context).getString("groups", "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Group(it.optString("id"), it.optString("n"), it.optInt("c")) }
        }.filter { it.id.isNotBlank() }.toMutableList()
        cache = list
        return list
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        load(context).forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color)) }
        prefs(context).edit().putString("groups", arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
