package com.videoplayerapp.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoplayerapp.R
import com.videoplayerapp.databinding.ItemSubtitleBinding
import com.videoplayerapp.model.SubtitleItem

class SubtitleAdapter(
    private val subtitles: List<SubtitleItem>,
    private val onItemClick: (SubtitleItem) -> Unit
) : RecyclerView.Adapter<SubtitleAdapter.SubtitleViewHolder>() {
    
    private var currentActivePosition = -1
    private var recyclerView: RecyclerView? = null
    
    inner class SubtitleViewHolder(private val binding: ItemSubtitleBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(subtitle: SubtitleItem, isActive: Boolean) {
            binding.apply {
                tvTimestamp.text = subtitle.getFormattedStartTime()
                tvSubtitleText.text = subtitle.text
                
                // Highlight active subtitle
                if (isActive) {
                    tvTimestamp.setTypeface(null, Typeface.BOLD)
                    tvSubtitleText.setTypeface(null, Typeface.BOLD)
                    root.setBackgroundColor(ContextCompat.getColor(root.context, R.color.primary_light))
                    tvTimestamp.setTextColor(ContextCompat.getColor(root.context, R.color.primary))
                    tvSubtitleText.setTextColor(ContextCompat.getColor(root.context, R.color.on_primary))
                } else {
                    tvTimestamp.setTypeface(null, Typeface.NORMAL)
                    tvSubtitleText.setTypeface(null, Typeface.NORMAL)
                    root.setBackgroundColor(ContextCompat.getColor(root.context, android.R.color.transparent))
                    tvTimestamp.setTextColor(ContextCompat.getColor(root.context, R.color.primary))
                    tvSubtitleText.setTextColor(ContextCompat.getColor(root.context, R.color.on_background))
                }
                
                root.setOnClickListener {
                    onItemClick(subtitle)
                }
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
        val binding = ItemSubtitleBinding.inflate(
            LayoutInflater.from(parent.context), 
            parent, 
            false
        )
        return SubtitleViewHolder(binding)
    }
    
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }
    
    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
        holder.bind(subtitles[position], position == currentActivePosition)
    }
    
    override fun getItemCount(): Int = subtitles.size
    
    fun updateActivePosition(currentTimeMs: Long) {
        val newActivePosition = subtitles.indexOfFirst { it.isActiveAt(currentTimeMs) }
        
        if (newActivePosition != currentActivePosition) {
            val oldPosition = currentActivePosition
            currentActivePosition = newActivePosition
            
            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition)
            }
            if (currentActivePosition >= 0) {
                notifyItemChanged(currentActivePosition)
                // Auto-scroll to current active subtitle
                recyclerView?.smoothScrollToPosition(currentActivePosition)
            }
        }
    }
}