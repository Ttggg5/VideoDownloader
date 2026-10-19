package com.vdbrowser.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Bookmark shortcuts on the new-tab start page. */
class StartTileAdapter(
    private val items: List<Bookmarks.Item>,
    private val onOpen: (Bookmarks.Item) -> Unit
) : RecyclerView.Adapter<StartTileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.startTileIcon)
        val label: TextView = view.findViewById(R.id.startTileLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_start_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val host = runCatching { Uri.parse(item.url).host }.getOrNull().orEmpty()
        holder.label.text = item.title.ifBlank { host.ifBlank { item.url } }

        holder.icon.setImageResource(R.drawable.ic_public)
        holder.icon.tag = host
        if (host.isNotBlank()) {
            FaviconLoader.load(host) { bmp ->
                if (bmp != null && holder.icon.tag == host) holder.icon.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onOpen(item) }
    }

    override fun getItemCount(): Int = items.size
}
