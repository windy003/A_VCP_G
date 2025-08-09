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
        val packageManager = context.packageManager
        val possiblePackages = listOf(
            "com.youdao.dict",           // 有道词典标准版
            "com.youdao.dict.android",   // 有道词典Android版
            "com.netease.youdaodict",    // 网易有道词典
            "com.youdao.dictvoice",      // 有道语音词典
            "com.youdao.dict.lite",      // 有道词典精简版
            "com.youdao.dict.pro",       // 有道词典专业版
            "com.netease.dict",          // 网易词典
            "com.youdao.edu.dict"        // 有道教育词典
        )
        
        try {
            // Method 1: Check which Youdao package is installed
            var installedPackage: String? = null
            for (pkg in possiblePackages) {
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    installedPackage = pkg
                    android.util.Log.d("YoudaoTranslation", "Found installed package: $pkg")
                    break
                } catch (e: Exception) {
                    // Package not installed, try next
                    android.util.Log.d("YoudaoTranslation", "Package not found: $pkg")
                }
            }
            
            // Also check all installed packages for any that might be Youdao-related
            val allPackages = packageManager.getInstalledPackages(0)
            val youdaoPackages = allPackages.filter { packageInfo ->
                val pkg = packageInfo.packageName.lowercase()
                (pkg.contains("youdao") || pkg.contains("netease")) &&
                (pkg.contains("dict") || pkg.contains("translate") || pkg.contains("trans"))
            }.map { it.packageName }
            
            // Also add broader dictionary search
            val dictPackages = allPackages.filter { packageInfo ->
                val pkg = packageInfo.packageName.lowercase()
                pkg.contains("dict") && (
                    pkg.contains("china") || pkg.contains("chinese") || 
                    pkg.contains("translate") || pkg.contains("trans")
                )
            }.map { it.packageName }
            
            val allDetectedPackages = (youdaoPackages + dictPackages).distinct()
            
            android.util.Log.d("YoudaoTranslation", "All Youdao-related packages: $youdaoPackages")
            android.util.Log.d("YoudaoTranslation", "All detected packages: $allDetectedPackages")
            
            // If we didn't find the expected packages, try any detected package
            if (installedPackage == null && allDetectedPackages.isNotEmpty()) {
                installedPackage = allDetectedPackages.first()
                android.util.Log.d("YoudaoTranslation", "Using alternative package: $installedPackage")
            }
            
            // DIAGNOSTIC: Show all detected packages to user temporarily
            showPackageDetectionDialog(context, allDetectedPackages, possiblePackages, installedPackage, text)
            
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
    
    private fun showPackageDetectionDialog(context: Context, detectedPackages: List<String>, possiblePackages: List<String>, foundPackage: String?, text: String) {
        val message = StringBuilder()
        message.append("有道词典集成诊断：\n\n")
        
        if (foundPackage != null) {
            message.append("✓ 找到匹配的包：$foundPackage\n\n")
        } else {
            message.append("✗ 未找到预期的有道词典包\n\n")
        }
        
        message.append("预期的有道词典包名：\n")
        possiblePackages.forEach { pkg ->
            val found = if (pkg == foundPackage) " ✓" else ""
            message.append("• $pkg$found\n")
        }
        
        message.append("\n检测到的所有词典相关包：\n")
        if (detectedPackages.isEmpty()) {
            message.append("• 未检测到任何词典相关包\n")
        } else {
            detectedPackages.forEach { message.append("• $it\n") }
        }
        
        message.append("\n请截图此信息并反馈，以便改进检测逻辑。")
        
        AlertDialog.Builder(context)
            .setTitle("有道词典检测诊断")
            .setMessage(message.toString())
            .setPositiveButton("继续翻译") { _, _ -> 
                // Continue with the translation attempt
                continueTranslationAfterDiagnostic(context, text, foundPackage)
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }
    
    private fun continueTranslationAfterDiagnostic(context: Context, text: String, installedPackage: String?) {
        if (installedPackage != null) {
            // Try to launch with the found package
            attemptTranslationWithPackage(context, text, installedPackage)
        } else {
            // No package found, show install dialog
            showInstallYoudaoDialog(context, text)
        }
    }
    
    private fun attemptTranslationWithPackage(context: Context, text: String, packageName: String) {
        val packageManager = context.packageManager
        
        // Method 1: Try different intent approaches
        val intentsToTry = listOf(
            // Standard search intent
            Intent().apply {
                action = Intent.ACTION_SEARCH
                putExtra("query", text)
                putExtra(android.app.SearchManager.QUERY, text)
                setPackage(packageName)
            },
            // Send text intent
            Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra("word", text)
                setPackage(packageName)
            },
            // Process text intent (Android 6.0+)
            Intent().apply {
                action = Intent.ACTION_PROCESS_TEXT
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                setPackage(packageName)
            },
            // Custom search action
            Intent().apply {
                action = "com.youdao.dict.SEARCH"
                putExtra("EXTRA_QUERY", text)
                putExtra("query", text)
                setPackage(packageName)
            }
        )
        
        for (intent in intentsToTry) {
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    android.util.Log.d("YoudaoTranslation", "Intent failed: ${e.message}")
                }
            }
        }
        
        // Method 2: Try URL schemes
        val urlSchemes = listOf(
            "youdaodict://m.youdao.com/dict?le=eng&q=${Uri.encode(text)}",
            "youdao://dict/${Uri.encode(text)}",
            "netease-youdao://dict/${Uri.encode(text)}",
            "youdaodict://dict/${Uri.encode(text)}"
        )
        
        for (scheme in urlSchemes) {
            try {
                val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                if (schemeIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(schemeIntent)
                    return
                }
            } catch (e: Exception) {
                android.util.Log.d("YoudaoTranslation", "URL scheme failed: ${e.message}")
            }
        }
        
        // Method 3: Launch app directly with clipboard
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            // Copy text to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Search Text", text)
            clipboard.setPrimaryClip(clip)
            
            launchIntent.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("query", text)
                putExtra("word", text)
            }
            
            context.startActivity(launchIntent)
            Toast.makeText(context, "词典已打开，\"$text\" 已复制到剪贴板", Toast.LENGTH_LONG).show()
            return
        }
        
        // All methods failed
        Toast.makeText(context, "无法启动词典应用，请手动打开有道词典", Toast.LENGTH_LONG).show()
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