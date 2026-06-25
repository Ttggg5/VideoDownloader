package com.vdbrowser.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows in the tab switcher: one per open tab, with a close button. */
class TabAdapter(
    private val tabs: List<Tab>,
    private val currentIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tabTitle)
        val url: TextView = view.findViewById(R.id.tabUrl)
        val close: ImageButton = view.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { "New Tab" }
        holder.url.text = tab.url.ifBlank { "about:blank" }
        holder.itemView.setBackgroundColor(
            if (position == currentIndex()) 0x223F51B5 else Color.TRANSPARENT
        )
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
