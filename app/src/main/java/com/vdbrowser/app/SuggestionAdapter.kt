package com.vdbrowser.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestionAdapter(
    private val onPick: (Suggestion) -> Unit,
    private val onFill: (String) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

    private var items: List<Suggestion> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.suggestionIcon)
        val title: TextView = view.findViewById(R.id.suggestionTitle)
        val subtitle: TextView = view.findViewById(R.id.suggestionSubtitle)
        val fill: ImageButton = view.findViewById(R.id.suggestionFill)
    }

    fun submit(list: List<Suggestion>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.icon.setImageResource(
            when (s.kind) {
                Suggestion.Kind.HISTORY -> R.drawable.ic_history
                Suggestion.Kind.BOOKMARK -> R.drawable.ic_star
                Suggestion.Kind.SEARCH -> R.drawable.ic_search
            }
        )
        holder.title.text = s.title
        if (s.kind == Suggestion.Kind.SEARCH) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = s.target
        }
        holder.itemView.setOnClickListener { onPick(s) }
        holder.fill.setOnClickListener { onFill(s.target) }
    }

    override fun getItemCount(): Int = items.size
}
