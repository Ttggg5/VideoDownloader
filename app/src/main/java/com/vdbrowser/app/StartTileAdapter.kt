package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Speed dial tiles on the start page, ending with an "Add site" tile. */
class StartTileAdapter(
    private val items: List<SpeedDial.Tile>,
    private val onOpen: (SpeedDial.Tile) -> Unit,
    private val onLongPress: (SpeedDial.Tile, View) -> Unit,
    private val onAdd: () -> Unit
) : RecyclerView.Adapter<StartTileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.startTileIcon)
        val label: TextView = view.findViewById(R.id.startTileLabel)
        val pin: View = view.findViewById(R.id.startTilePin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_start_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == items.size) {
            holder.icon.tag = null
            holder.icon.setImageResource(R.drawable.ic_add)
            holder.icon.setPadding(dp(holder, 20))
            holder.label.setText(R.string.add_site)
            holder.pin.visibility = View.GONE
            holder.itemView.setOnClickListener { onAdd() }
            holder.itemView.setOnLongClickListener(null)
            return
        }
        val item = items[position]
        val host = SpeedDial.host(item.url)
        holder.label.text = item.title.ifBlank { host.ifBlank { item.url } }
        holder.pin.visibility = if (item.pinned) View.VISIBLE else View.GONE
        holder.icon.setPadding(dp(holder, 18))
        holder.icon.setImageResource(R.drawable.ic_public)
        holder.icon.tag = host
        if (host.isNotBlank()) {
            FaviconLoader.load(host) { bmp ->
                if (bmp != null && holder.icon.tag == host) holder.icon.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener { onLongPress(item, it); true }
    }

    override fun getItemCount(): Int = items.size + 1

    private fun dp(h: VH, v: Int) = (v * h.itemView.resources.displayMetrics.density).toInt()

    private fun View.setPadding(p: Int) = setPadding(p, p, p, p)
}
