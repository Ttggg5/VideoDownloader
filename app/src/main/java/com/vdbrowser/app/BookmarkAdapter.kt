package com.vdbrowser.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows in the bookmarks sheet: tap to open, × to delete. */
class BookmarkAdapter(
    private val items: MutableList<Bookmarks.Item>,
    private val onOpen: (Bookmarks.Item) -> Unit,
    private val onDelete: (Bookmarks.Item) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val favicon: ImageView = view.findViewById(R.id.bookmarkFavicon)
        val title: TextView = view.findViewById(R.id.bookmarkTitle)
        val url: TextView = view.findViewById(R.id.bookmarkUrl)
        val delete: ImageButton = view.findViewById(R.id.bookmarkDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookmark, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title.ifBlank { item.url }
        holder.url.text = item.url

        // Load the site favicon, falling back to the globe glyph.
        val host = runCatching { Uri.parse(item.url).host }.getOrNull().orEmpty()
        holder.favicon.setImageResource(R.drawable.ic_public)
        holder.favicon.tag = host
        if (host.isNotBlank()) {
            FaviconLoader.load(host) { bmp ->
                if (bmp != null && holder.favicon.tag == host) holder.favicon.setImageBitmap(bmp)
            }
        }

        holder.itemView.setOnClickListener { onOpen(item) }
        holder.delete.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val removed = items.removeAt(pos)
                notifyItemRemoved(pos)
                onDelete(removed)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
