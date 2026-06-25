package com.vdbrowser.app

import android.app.DownloadManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Shows live progress for each registered download in the progress dialog. */
class DownloadProgressAdapter(
    private var items: List<Downloads.Status>
) : RecyclerView.Adapter<DownloadProgressAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.downloadTitle)
        val state: TextView = view.findViewById(R.id.downloadState)
        val bytes: TextView = view.findViewById(R.id.downloadBytes)
        val bar: ProgressBar = view.findViewById(R.id.downloadBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title

        val percent = if (item.total > 0) ((item.downloaded * 100) / item.total).toInt() else 0
        holder.bar.isIndeterminate =
            item.state == DownloadManager.STATUS_RUNNING && item.total <= 0
        if (!holder.bar.isIndeterminate) holder.bar.progress = percent

        holder.state.text = when (item.state) {
            DownloadManager.STATUS_PENDING -> "Pending"
            DownloadManager.STATUS_RUNNING -> if (item.total > 0) "Downloading · $percent%" else "Downloading"
            DownloadManager.STATUS_PAUSED -> "Paused"
            DownloadManager.STATUS_SUCCESSFUL -> "Completed"
            DownloadManager.STATUS_FAILED -> "Failed"
            else -> "Queued"
        }

        holder.bytes.text = if (item.total > 0) {
            "${formatBytes(item.downloaded)} / ${formatBytes(item.total)}"
        } else {
            formatBytes(item.downloaded)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<Downloads.Status>) {
        items = newItems
        notifyDataSetChanged()
    }
}
