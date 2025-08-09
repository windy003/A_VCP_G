package com.videoplayerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.videoplayerapp.databinding.ActivityMainBinding
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var selectedAudioUri: Uri? = null
    private var selectedSubtitleUri: Uri? = null
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedVideoUri = it
            binding.etVideoUrl.setText(it.toString())
            updatePlayButtonState()
        }
    }
    
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedAudioUri = it
            binding.etAudioUrl.setText(it.toString())
        }
    }
    
    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedSubtitleUri = it
            binding.etSubtitleUrl.setText(it.toString())
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
        
        setupUI()
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
    
    private fun showError(message: String) {
        binding.tvStatus.apply {
            text = message
            visibility = View.VISIBLE
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}