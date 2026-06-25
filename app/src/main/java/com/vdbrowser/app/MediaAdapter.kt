package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Renders the list of detected media (with sizes) inside the downloads bottom sheet. */
class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.mediaTitle)
        val subtitle: TextView = view.findViewById(R.id.mediaSubtitle)
        val size: TextView = view.findViewById(R.id.mediaSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.label
        val kind = if (item.isStream) "Stream" else "Video"
        holder.subtitle.text = "$kind · ${item.type.uppercase()} · ${shortUrl(item.url)}"
        holder.size.text = when (item.sizeBytes) {
            MediaItem.SIZE_FETCHING -> "…"
            MediaItem.SIZE_UNKNOWN -> "—"
            else -> formatBytes(item.sizeBytes)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    /** Refresh the size cell for a given item once its length is resolved. */
    fun updateSize(item: MediaItem, size: Long) {
        item.sizeBytes = size
        val index = items.indexOf(item)
        if (index >= 0) notifyItemChanged(index)
    }

    private fun shortUrl(url: String): String =
        if (url.length > 60) url.take(57) + "…" else url
}
