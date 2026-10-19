package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/** Cards in the tab switcher grid: favicon, title, close button and a page snapshot. */
class TabAdapter(
    private val tabs: List<Tab>,
    private val currentIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.tabCard)
        val favicon: ImageView = view.findViewById(R.id.tabFavicon)
        val title: TextView = view.findViewById(R.id.tabTitle)
        val preview: ImageView = view.findViewById(R.id.tabPreview)
        val close: ImageButton = view.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { tab.url.ifBlank { "New Tab" } }
        val fav = tab.favicon
        if (fav != null) holder.favicon.setImageBitmap(fav)
        else holder.favicon.setImageResource(R.drawable.ic_public)

        val shot = tab.preview
        if (shot != null) {
            holder.preview.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.preview.setImageBitmap(shot)
        } else {
            holder.preview.scaleType = ImageView.ScaleType.CENTER
            holder.preview.setImageResource(R.drawable.ic_public)
        }

        val density = holder.itemView.resources.displayMetrics.density
        holder.card.strokeWidth = if (position == currentIndex()) (3 * density).toInt() else 0

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSelect(pos)
        }
        holder.close.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClose(pos)
        }
    }

    override fun getItemCount(): Int = tabs.size
}
