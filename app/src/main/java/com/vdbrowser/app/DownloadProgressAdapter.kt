package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Shows live progress (with speed) and pause/resume/cancel controls per download. */
class DownloadProgressAdapter(
    private var items: List<Downloads.Snapshot>,
    private val onPause: (Long) -> Unit = {},
    private val onResume: (Long) -> Unit = {},
    private val onCancel: (Long) -> Unit = {}
) : RecyclerView.Adapter<DownloadProgressAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.downloadTitle)
        val state: TextView = view.findViewById(R.id.downloadState)
        val bytes: TextView = view.findViewById(R.id.downloadBytes)
        val bar: ProgressBar = view.findViewById(R.id.downloadBar)
        val pauseResume: ImageButton = view.findViewById(R.id.btnPauseResume)
        val cancel: ImageButton = view.findViewById(R.id.btnCancel)
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
        val running = item.state == Downloads.State.RUNNING
        holder.bar.isIndeterminate = running && item.total <= 0
        if (!holder.bar.isIndeterminate) holder.bar.progress = percent

        holder.state.text = when (item.state) {
            Downloads.State.RUNNING ->
                if (item.total > 0) "Downloading · $percent% · ${speed(item.speed)}"
                else "Downloading · ${speed(item.speed)}"
            Downloads.State.PAUSED -> "Paused · $percent%"
            Downloads.State.COMPLETED -> "Completed"
            Downloads.State.FAILED -> "Failed"
            Downloads.State.CANCELED -> "Canceled"
        }

        holder.bytes.text = if (item.total > 0) {
            "${formatBytes(item.downloaded)} / ${formatBytes(item.total)}"
        } else {
            formatBytes(item.downloaded)
        }

        // Controls: pause/resume only for resumable, in-flight downloads; cancel while active.
        val active = item.state == Downloads.State.RUNNING || item.state == Downloads.State.PAUSED
        holder.cancel.visibility = if (active) View.VISIBLE else View.GONE
        holder.pauseResume.visibility = if (active && item.resumable) View.VISIBLE else View.GONE

        if (item.state == Downloads.State.PAUSED) {
            holder.pauseResume.setImageResource(R.drawable.ic_play)
            holder.pauseResume.setOnClickListener { onResume(item.id) }
        } else {
            holder.pauseResume.setImageResource(R.drawable.ic_pause)
            holder.pauseResume.setOnClickListener { onPause(item.id) }
        }
        holder.cancel.setOnClickListener { onCancel(item.id) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<Downloads.Snapshot>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0) "…" else "${formatBytes(bytesPerSec)}/s"
}
