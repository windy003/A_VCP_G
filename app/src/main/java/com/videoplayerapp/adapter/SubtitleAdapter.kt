package com.videoplayerapp.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
                
                // Enable text selection
                tvSubtitleText.setTextIsSelectable(true)
                tvSubtitleText.customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        // Add custom menu item for Youdao translation
                        menu?.add(0, R.id.menu_translate_youdao, 0, R.string.translate_with_youdao)
                        return true
                    }
                    
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        return false
                    }
                    
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        return when (item?.itemId) {
                            R.id.menu_translate_youdao -> {
                                val selectedText = getSelectedText(tvSubtitleText)
                                if (selectedText.isNotEmpty()) {
                                    openYoudaoTranslation(tvSubtitleText.context, selectedText)
                                }
                                mode?.finish()
                                true
                            }
                            else -> false
                        }
                    }
                    
                    override fun onDestroyActionMode(mode: ActionMode?) {
                        // Action mode destroyed
                    }
                }
                
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
                
                // Click on timestamp to jump to time
                tvTimestamp.setOnClickListener {
                    onItemClick(subtitle)
                }
                
                // Prevent root click when selecting text
                root.setOnClickListener { view ->
                    if (!tvSubtitleText.hasSelection()) {
                        onItemClick(subtitle)
                    }
                }
            }
        }
    }
    
    // Extension function to check if TextView has selected text
    private fun TextView.hasSelection(): Boolean {
        return selectionStart != selectionEnd
    }
    
    private fun getSelectedText(textView: TextView): String {
        val start = textView.selectionStart
        val end = textView.selectionEnd
        return if (start >= 0 && end >= 0 && start != end) {
            textView.text.substring(start, end)
        } else {
            ""
        }
    }
    
    private fun openYoudaoTranslation(context: android.content.Context, text: String) {
        try {
            // Method 1: Try Youdao Dictionary's search intent
            val searchIntent = Intent().apply {
                action = Intent.ACTION_SEARCH
                putExtra("query", text)
                setPackage("com.youdao.dict")
            }
            
            val packageManager = context.packageManager
            if (searchIntent.resolveActivity(packageManager) != null) {
                context.startActivity(searchIntent)
                return
            }
            
            // Method 2: Try Youdao's custom scheme (multiple variations)
            val schemeIntents = listOf(
                Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("youdaodict://m.youdao.com/dict?le=eng&q=${Uri.encode(text)}")
                },
                Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("youdao://dict/${Uri.encode(text)}")
                },
                Intent().apply {
                    action = "com.youdao.dict.SEARCH"
                    putExtra("EXTRA_QUERY", text)
                    setPackage("com.youdao.dict")
                }
            )
            
            for (schemeIntent in schemeIntents) {
                if (schemeIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(schemeIntent)
                    return
                }
            }
            
            // Method 3: Try launching Youdao app directly and use clipboard
            val launchIntent = packageManager.getLaunchIntentForPackage("com.youdao.dict")
            if (launchIntent != null) {
                // Copy text to clipboard for user to paste in the app
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Search Text", text)
                clipboard.setPrimaryClip(clip)
                
                // Add additional launch parameters if possible
                launchIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("query", text)
                    putExtra("word", text)
                }
                
                context.startActivity(launchIntent)
                
                // Show helpful message
                val message = if (text.length <= 20) {
                    "有道词典已打开，\"$text\" 已复制到剪贴板\n点击搜索框粘贴查词"
                } else {
                    "有道词典已打开，选中文本已复制到剪贴板\n点击搜索框粘贴查词"
                }
                
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                return
            }
            
            // Method 4: Check if user wants to install Youdao Dictionary
            showInstallYoudaoDialog(context, text)
            
        } catch (e: Exception) {
            // Final fallback to web version
            openYoudaoWeb(context, text)
        }
    }
    
    private fun openYoudaoWeb(context: android.content.Context, text: String) {
        try {
            val encodedText = Uri.encode(text)
            val webUrl = "https://fanyi.youdao.com/#/en/zh/$encodedText"
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
            context.startActivity(webIntent)
        } catch (e: Exception) {
            // If browser fails, show error message
            android.widget.Toast.makeText(
                context, 
                context.getString(R.string.translation_failed), 
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun showInstallYoudaoDialog(context: Context, text: String) {
        AlertDialog.Builder(context)
            .setTitle("有道词典未安装")
            .setMessage("检测到您尚未安装有道词典APP\n\n您可以选择：")
            .setPositiveButton("安装有道词典") { _, _ ->
                try {
                    // Try to open app store
                    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.youdao.dict"))
                    context.startActivity(playStoreIntent)
                } catch (e: Exception) {
                    // Fallback to web store
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.youdao.dict"))
                    context.startActivity(webIntent)
                }
            }
            .setNegativeButton("使用网页版") { _, _ ->
                openYoudaoWeb(context, text)
            }
            .setNeutralButton("取消") { _, _ ->
                // Do nothing
            }
            .show()
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