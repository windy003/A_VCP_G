package com.videoplayerapp

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.videoplayerapp.databinding.ActivityMainBinding
import com.videoplayerapp.utils.FilePathUtils
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var selectedVideoUri: Uri? = null
    private var selectedAudioUri: Uri? = null
    private var selectedSubtitleUri: Uri? = null
    
    companion object {
        private const val PREFS_NAME = "VideoPlayerPrefs"
        private const val KEY_LAST_VIDEO_URL = "last_video_url"
        private const val KEY_LAST_VIDEO_PATH = "last_video_path"
        private const val KEY_LAST_VIDEO_NAME = "last_video_name"
        private const val KEY_LAST_AUDIO_URL = "last_audio_url"
        private const val KEY_LAST_AUDIO_PATH = "last_audio_path"
        private const val KEY_LAST_AUDIO_NAME = "last_audio_name"
        private const val KEY_LAST_SUBTITLE_URL = "last_subtitle_url"
        private const val KEY_LAST_SUBTITLE_PATH = "last_subtitle_path"
        private const val KEY_LAST_SUBTITLE_NAME = "last_subtitle_name"
    }
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                // Take persistable URI permission
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be grantable, but continue anyway
            }
            
            selectedVideoUri = it
            
            // Try to get real file path
            val realPath = FilePathUtils.getRealPathFromURI(this, it)
            val displayName = FilePathUtils.getDisplayName(this, it)
            
            // Use real path if available, otherwise use URI
            val pathToShow = if (realPath != null) "file://$realPath" else it.toString()
            binding.etVideoUrl.setText(pathToShow)
            
            // Save file info immediately - save the path we're actually showing
            saveFileInfo("video", pathToShow, realPath, displayName)
            updatePlayButtonState()
        }
    }
    
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be grantable
            }
            
            selectedAudioUri = it
            
            val realPath = FilePathUtils.getRealPathFromURI(this, it)
            val displayName = FilePathUtils.getDisplayName(this, it)
            
            val pathToShow = if (realPath != null) "file://$realPath" else it.toString()
            binding.etAudioUrl.setText(pathToShow)
            
            saveFileInfo("audio", pathToShow, realPath, displayName)
        }
    }
    
    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be grantable
            }
            
            selectedSubtitleUri = it
            
            val realPath = FilePathUtils.getRealPathFromURI(this, it)
            val displayName = FilePathUtils.getDisplayName(this, it)
            
            val pathToShow = if (realPath != null) "file://$realPath" else it.toString()
            binding.etSubtitleUrl.setText(pathToShow)
            
            saveFileInfo("subtitle", pathToShow, realPath, displayName)
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        setupUI()
        restoreLastSelection()
        requestPermissions()
    }
    
    private fun setupUI() {
        binding.apply {
            etVideoUrl.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updatePlayButtonState()
                }
            })
            
            btnSelectVideoFile.setOnClickListener {
                checkStoragePermissionAndPickVideo()
            }
            
            btnSelectAudioFile.setOnClickListener {
                checkStoragePermissionAndPickAudio()
            }
            
            btnSelectSubtitleFile.setOnClickListener {
                checkStoragePermissionAndPickSubtitle()
            }
            
            btnPlay.setOnClickListener {
                playVideo()
            }
            
            btnHelp.setOnClickListener {
                showFileAccessTip()
            }
        }
    }
    
    private fun checkStoragePermissionAndPickVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                return
            }
        }
        videoPickerLauncher.launch("video/*")
    }
    
    private fun checkStoragePermissionAndPickAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                return
            }
        }
        audioPickerLauncher.launch("audio/*")
    }
    
    private fun checkStoragePermissionAndPickSubtitle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                return
            }
        }
        subtitlePickerLauncher.launch("*/*")
    }
    
    private fun updatePlayButtonState() {
        val videoText = binding.etVideoUrl.text.toString().trim()
        binding.btnPlay.isEnabled = videoText.isNotEmpty() && (isValidUrl(videoText) || isValidFilePath(videoText))
    }
    
    private fun isValidUrl(url: String): Boolean {
        val urlPattern = Pattern.compile(
            "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
        )
        return urlPattern.matcher(url).matches()
    }
    
    private fun isValidFilePath(path: String): Boolean {
        return path.startsWith("content://") || path.startsWith("file://") || path.startsWith("/")
    }
    
    private fun playVideo() {
        val videoUrl = binding.etVideoUrl.text.toString().trim()
        val audioUrl = binding.etAudioUrl.text.toString().trim()
        val subtitleUrl = binding.etSubtitleUrl.text.toString().trim()
        
        if (videoUrl.isEmpty()) {
            showError(getString(R.string.error_invalid_url))
            return
        }
        
        checkOverlayPermission {
            // Save current selection before starting player
            saveCurrentSelection()
            
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("video_url", videoUrl)
                if (audioUrl.isNotEmpty()) {
                    putExtra("audio_url", audioUrl)
                }
                if (subtitleUrl.isNotEmpty()) {
                    putExtra("subtitle_url", subtitleUrl)
                }
            }
            startActivity(intent)
        }
    }
    
    private fun checkOverlayPermission(onPermissionGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("This app needs overlay permission to show floating controls when video is playing in background.")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        overlayPermissionLauncher.launch(intent)
                    }
                    .setNegativeButton("Continue Without") { _, _ ->
                        onPermissionGranted()
                    }
                    .show()
            } else {
                onPermissionGranted()
            }
        } else {
            onPermissionGranted()
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    private fun restoreLastSelection() {
        Log.d("MainActivity", "Starting to restore last selection")
        restoreFileSelection("video", binding.etVideoUrl)
        restoreFileSelection("audio", binding.etAudioUrl)
        restoreFileSelection("subtitle", binding.etSubtitleUrl)
        updatePlayButtonState()
        Log.d("MainActivity", "Finished restoring last selection")
    }
    
    private fun restoreFileSelection(type: String, editText: com.google.android.material.textfield.TextInputEditText) {
        val urlKey = when(type) {
            "video" -> KEY_LAST_VIDEO_URL
            "audio" -> KEY_LAST_AUDIO_URL
            "subtitle" -> KEY_LAST_SUBTITLE_URL
            else -> return
        }
        val pathKey = when(type) {
            "video" -> KEY_LAST_VIDEO_PATH
            "audio" -> KEY_LAST_AUDIO_PATH
            "subtitle" -> KEY_LAST_SUBTITLE_PATH
            else -> return
        }
        val nameKey = when(type) {
            "video" -> KEY_LAST_VIDEO_NAME
            "audio" -> KEY_LAST_AUDIO_NAME
            "subtitle" -> KEY_LAST_SUBTITLE_NAME
            else -> return
        }
        
        val lastUrl = sharedPreferences.getString(urlKey, "")
        val lastPath = sharedPreferences.getString(pathKey, "")
        val lastName = sharedPreferences.getString(nameKey, "")
        
        Log.d("MainActivity", "Restoring $type: url=$lastUrl, path=$lastPath, name=$lastName")
        
        if (!lastUrl.isNullOrEmpty()) {
            // Strategy 1: Try real file path first
            if (!lastPath.isNullOrEmpty() && FilePathUtils.fileExists(lastPath)) {
                Log.d("MainActivity", "Strategy 1 success for $type: using real path")
                editText.setText("file://$lastPath")
                return
            }
            
            // Strategy 2: Try original URI
            if (isUriStillValid(lastUrl)) {
                Log.d("MainActivity", "Strategy 2 success for $type: using original URI")
                editText.setText(lastUrl)
                return
            }
            
            // Strategy 3: Smart recovery - try to find file by name
            if (!lastName.isNullOrEmpty()) {
                val recoveredPath = findFileByName(lastName, type)
                if (recoveredPath != null) {
                    Log.d("MainActivity", "Strategy 3 success for $type: found file at $recoveredPath")
                    editText.setText("file://$recoveredPath")
                    // Update saved path
                    saveFileInfo(type, "file://$recoveredPath", recoveredPath, lastName)
                    return
                }
            }
            
            // Strategy 4: Clear invalid data
            Log.d("MainActivity", "All strategies failed for $type: clearing data")
            clearFileInfo(type)
        } else {
            Log.d("MainActivity", "No saved data for $type - keeping field empty")
            // For audio and subtitle, if no data is saved, keep the field empty
            // For video, this should not happen as video is required
            editText.setText("")
        }
    }
    
    private fun isUriStillValid(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    // Check if content provider is still available
                    contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
                }
                "file" -> {
                    // Check if file still exists
                    val file = java.io.File(uri.path ?: "")
                    file.exists() && file.canRead()
                }
                "http", "https" -> {
                    // URLs are assumed to be valid (will be checked during playback)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun saveFileInfo(type: String, url: String, realPath: String?, displayName: String?) {
        Log.d("MainActivity", "Saving $type: url=$url, realPath=$realPath, displayName=$displayName")
        val editor = sharedPreferences.edit()
        when(type) {
            "video" -> {
                editor.putString(KEY_LAST_VIDEO_URL, url)
                editor.putString(KEY_LAST_VIDEO_PATH, realPath)
                editor.putString(KEY_LAST_VIDEO_NAME, displayName)
            }
            "audio" -> {
                editor.putString(KEY_LAST_AUDIO_URL, url)
                editor.putString(KEY_LAST_AUDIO_PATH, realPath)
                editor.putString(KEY_LAST_AUDIO_NAME, displayName)
            }
            "subtitle" -> {
                editor.putString(KEY_LAST_SUBTITLE_URL, url)
                editor.putString(KEY_LAST_SUBTITLE_PATH, realPath)
                editor.putString(KEY_LAST_SUBTITLE_NAME, displayName)
            }
        }
        editor.apply()
        Log.d("MainActivity", "Saved $type info to SharedPreferences")
    }
    
    private fun clearFileInfo(type: String) {
        val editor = sharedPreferences.edit()
        when(type) {
            "video" -> {
                editor.remove(KEY_LAST_VIDEO_URL)
                editor.remove(KEY_LAST_VIDEO_PATH)
                editor.remove(KEY_LAST_VIDEO_NAME)
            }
            "audio" -> {
                editor.remove(KEY_LAST_AUDIO_URL)
                editor.remove(KEY_LAST_AUDIO_PATH)
                editor.remove(KEY_LAST_AUDIO_NAME)
            }
            "subtitle" -> {
                editor.remove(KEY_LAST_SUBTITLE_URL)
                editor.remove(KEY_LAST_SUBTITLE_PATH)
                editor.remove(KEY_LAST_SUBTITLE_NAME)
            }
        }
        editor.apply()
    }
    
    private fun findFileByName(fileName: String, type: String): String? {
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(Environment.getExternalStorageDirectory(), "Download")
        )
        
        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val file = File(dir, fileName)
                if (file.exists() && file.canRead()) {
                    return file.absolutePath
                }
                
                // Also search subdirectories (one level deep)
                dir.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory) {
                        val subFile = File(subDir, fileName)
                        if (subFile.exists() && subFile.canRead()) {
                            return subFile.absolutePath
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    private fun saveCurrentSelection() {
        // This method is called when playing video
        // File info is already saved when selecting files
        val videoText = binding.etVideoUrl.text.toString().trim()
        val audioText = binding.etAudioUrl.text.toString().trim()
        val subtitleText = binding.etSubtitleUrl.text.toString().trim()
        
        if (videoText.isNotEmpty()) {
            saveFileInfo("video", videoText, null, null)
        }
        if (audioText.isNotEmpty()) {
            saveFileInfo("audio", audioText, null, null)
        } else {
            // Clear audio data if field is empty
            clearFileInfo("audio")
        }
        if (subtitleText.isNotEmpty()) {
            saveFileInfo("subtitle", subtitleText, null, null)
        } else {
            // Clear subtitle data if field is empty
            clearFileInfo("subtitle")
        }
    }
    
    private fun showError(message: String) {
        binding.tvStatus.apply {
            text = message
            visibility = View.VISIBLE
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showFileAccessTip() {
        AlertDialog.Builder(this)
            .setTitle("File Access & Troubleshooting")
            .setMessage("File Access Tips:\n\n" +
                    "1. Use built-in Android file picker for best compatibility\n" +
                    "2. Avoid third-party file managers when possible\n" +
                    "3. Select files from Downloads, Documents, or Movies folders\n" +
                    "4. For separated audio/video files, ensure both are accessible\n" +
                    "5. If files become inaccessible, select them again\n" +
                    "6. URLs work reliably for online content\n\n" +
                    "Common Issues:\n" +
                    "• File manager app updates can break file access\n" +
                    "• Some file providers don't support persistent access\n" +
                    "• Files moved or deleted will need re-selection")
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }
}