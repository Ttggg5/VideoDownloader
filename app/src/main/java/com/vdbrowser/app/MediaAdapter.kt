package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Renders detected media (thumbnail + size) inside the downloads bottom sheet. */
class MediaAdapter(
    private val items: MutableList<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val onNeedThumb: (MediaItem) -> Unit = {}
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.mediaThumb)
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

        val thumb = item.thumbnail
        if (thumb != null) {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.thumb.setImageBitmap(thumb)
        } else {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.thumb.setImageResource(R.drawable.ic_download)
            if (!item.thumbRequested) {
                item.thumbRequested = true
                onNeedThumb(item)
            }
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

    /** Refresh the row once its thumbnail is resolved. */
    fun updateThumb(item: MediaItem) {
        val index = items.indexOf(item)
        if (index >= 0) notifyItemChanged(index)
    }

    /** Remove a row (e.g. filtered out by the minimum-size option). */
    fun removeItem(item: MediaItem) {
        val index = items.indexOf(item)
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    private fun shortUrl(url: String): String =
        if (url.length > 60) url.take(57) + "…" else url
}
