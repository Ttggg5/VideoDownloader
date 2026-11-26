package com.vdbrowser.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** One card in the tab grid: a single tab, or a collapsed tab group. */
sealed class TabEntry {
    data class Single(val index: Int) : TabEntry()
    data class Group(val group: TabGroups.Group, val indices: List<Int>) : TabEntry()
}

/** The tab switcher grid: tab cards with page snapshots, and group cards with a 2x2 collage. */
class TabAdapter(
    private val tabs: List<Tab>,
    private val entries: () -> List<TabEntry>,
    private val currentIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onLongPress: (Int, View) -> Unit,
    private val onOpenGroup: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<TabEntry> = entries()

    fun refresh() {
        items = entries()
        notifyDataSetChanged()
    }

    fun entryAt(position: Int): TabEntry? = items.getOrNull(position)

    class TabVH(view: View) : RecyclerView.ViewHolder(view) {
        val header: View = view.findViewById(R.id.tabHeader)
        val favicon: ImageView = view.findViewById(R.id.tabFavicon)
        val title: TextView = view.findViewById(R.id.tabTitle)
        val preview: ImageView = view.findViewById(R.id.tabPreview)
        val close: ImageButton = view.findViewById(R.id.tabClose)
        val groupDot: View = view.findViewById(R.id.tabGroupDot)
    }

    class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val dot: View = view.findViewById(R.id.groupDot)
        val name: TextView = view.findViewById(R.id.groupName)
        val count: TextView = view.findViewById(R.id.groupCount)
        val shots: List<ImageView> = listOf(
            view.findViewById(R.id.groupShot1), view.findViewById(R.id.groupShot2),
            view.findViewById(R.id.groupShot3), view.findViewById(R.id.groupShot4)
        )
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is TabEntry.Group) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) GroupVH(inflater.inflate(R.layout.item_tab_group, parent, false))
        else TabVH(inflater.inflate(R.layout.item_tab, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = items[position]) {
            is TabEntry.Single -> bindTab(holder as TabVH, entry.index)
            is TabEntry.Group -> bindGroup(holder as GroupVH, entry)
        }
    }

    private fun bindTab(holder: TabVH, index: Int) {
        val tab = tabs[index]
        val ctx = holder.itemView.context
        holder.title.text = tab.title.ifBlank { tab.url.ifBlank { "New Tab" } }
        val fav = tab.favicon
        if (fav != null) holder.favicon.setImageBitmap(fav)
        else holder.favicon.setImageResource(R.drawable.ic_public)
        showShot(holder.preview, tab)

        val group = TabGroups.get(ctx, tab.groupId)
        holder.groupDot.visibility = if (group != null) View.VISIBLE else View.GONE
        group?.let { holder.groupDot.background?.mutate()?.setTint(it.color) }

        // The current tab gets a gradient title strip with white text.
        when {
            index == currentIndex() -> {
                holder.header.setBackgroundResource(R.drawable.bg_brand_gradient)
                holder.title.setTextColor(Color.WHITE)
                holder.close.setColorFilter(Color.WHITE)
            }
            tab.incognito -> {
                holder.header.setBackgroundColor(ContextCompat.getColor(ctx, R.color.incognito_header))
                holder.title.setTextColor(ContextCompat.getColor(ctx, R.color.incognito_on))
                holder.close.setColorFilter(ContextCompat.getColor(ctx, R.color.incognito_on_variant))
            }
            else -> {
                holder.header.background = null
                holder.title.setTextColor(MaterialColors.getColor(holder.title, com.google.android.material.R.attr.colorOnSurface))
                holder.close.setColorFilter(MaterialColors.getColor(holder.close, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }

        holder.itemView.setOnClickListener { onSelect(index) }
        holder.itemView.setOnLongClickListener { onLongPress(index, it); true }
        holder.close.setOnClickListener { onClose(index) }
    }

    private fun bindGroup(holder: GroupVH, entry: TabEntry.Group) {
        val ctx = holder.itemView.context
        holder.name.text = entry.group.name
        holder.count.text = ctx.resources.getQuantityString(R.plurals.group_tab_count, entry.indices.size, entry.indices.size)
        holder.dot.background?.mutate()?.setTint(entry.group.color)
        holder.name.setTextColor(entry.group.color)
        holder.shots.forEachIndexed { i, view ->
            val tab = entry.indices.getOrNull(i)?.let { tabs[it] }
            if (tab == null) {
                view.setImageDrawable(null)
            } else {
                showShot(view, tab)
            }
        }
        val density = ctx.resources.displayMetrics.density
        holder.card.strokeColor = entry.group.color
        holder.card.strokeWidth = if (currentIndex() in entry.indices) (3 * density).toInt() else 0
        holder.itemView.setOnClickListener { onOpenGroup(entry.group.id) }
        holder.itemView.setOnLongClickListener(null)
    }

    private fun showShot(view: ImageView, tab: Tab) {
        val shot = tab.preview
        if (shot != null) {
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(shot)
        } else {
            view.scaleType = ImageView.ScaleType.CENTER
            view.setImageResource(R.drawable.ic_public)
        }
    }

    override fun getItemCount(): Int = items.size
}
