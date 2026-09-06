package com.vdbrowser.app

import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows in the browsing-history sheet: tap to open, × to delete. */
class BrowsingHistoryAdapter(
    private val items: MutableList<BrowsingHistory.Entry>,
    private val onOpen: (BrowsingHistory.Entry) -> Unit,
    private val onDelete: (BrowsingHistory.Entry) -> Unit
) : RecyclerView.Adapter<BrowsingHistoryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val favicon: ImageView = view.findViewById(R.id.pageHistoryFavicon)
        val title: TextView = view.findViewById(R.id.pageHistoryTitle)
        val subtitle: TextView = view.findViewById(R.id.pageHistorySubtitle)
        val delete: ImageButton = view.findViewById(R.id.pageHistoryDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title.ifBlank { item.url }
        val when_ = DateUtils.getRelativeTimeSpanString(
            item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.subtitle.text = "${item.url} · $when_"

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
