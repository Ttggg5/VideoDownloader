package com.vdbrowser.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal desktop-style tab strip shown on top on large screens. Each chip
 * is a tab; the active one is highlighted. Tapping selects, the × closes.
 */
class TabStripAdapter(
    private val tabs: List<Tab>,
    private val currentIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<TabStripAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val favicon: ImageView = view.findViewById(R.id.tabChipFavicon)
        val title: TextView = view.findViewById(R.id.tabChipTitle)
        val close: ImageButton = view.findViewById(R.id.tabChipClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab_strip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { tab.url.ifBlank { "New Tab" } }
        val fav = tab.favicon
        if (fav != null) holder.favicon.setImageBitmap(fav)
        else holder.favicon.setImageResource(R.drawable.ic_public)

        val active = position == currentIndex()
        holder.itemView.setBackgroundResource(
            if (active) R.drawable.bg_tab_chip_active else R.drawable.bg_tab_chip
        )
        val textColor = if (active) Color.WHITE else 0xFF888888.toInt()
        holder.title.setTextColor(textColor)
        holder.close.setColorFilter(textColor)

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
