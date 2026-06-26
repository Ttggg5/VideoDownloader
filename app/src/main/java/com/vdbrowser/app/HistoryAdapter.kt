package com.vdbrowser.app

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows in the downloads history sheet. */
class HistoryAdapter(
    private val items: List<DownloadHistory.Entry>
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.historyTitle)
        val subtitle: TextView = view.findViewById(R.id.historySubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val state = when (item.state) {
            "COMPLETED" -> "Completed"
            "FAILED" -> "Failed"
            "CANCELED" -> "Canceled"
            else -> item.state
        }
        val when_ = DateUtils.getRelativeTimeSpanString(
            item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val size = if (item.bytes > 0) " · ${formatBytes(item.bytes)}" else ""
        holder.subtitle.text = "$state$size · $when_"
    }

    override fun getItemCount(): Int = items.size
}
