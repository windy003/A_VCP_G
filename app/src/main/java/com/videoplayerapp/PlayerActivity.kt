package com.videoplayerapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.videoplayerapp.adapter.SubtitleAdapter
import com.videoplayerapp.databinding.ActivityPlayerBinding
import com.videoplayerapp.model.SubtitleItem
import com.videoplayerapp.service.FloatingPlayerService
import com.videoplayerapp.utils.SubtitleParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

class PlayerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var subtitles = listOf<SubtitleItem>()
    private var subtitleAdapter: SubtitleAdapter? = null
    private var floatingService: FloatingPlayerService? = null
    private var isServiceBound = false
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var subtitleDelayMs = 0L
    private lateinit var sharedPreferences: SharedPreferences
    private var currentVideoId: String = ""
    private var isSeekBarTracking = false
    private var lastSavedPosition = 0L
    private val savePositionInterval = 5000L // Save position every 5 seconds
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingPlayerService.LocalBinder
            floatingService = binder.getService()
            floatingService?.setPlayer(exoPlayer)
            isServiceBound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            floatingService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Keep screen on and hide system UI
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        
        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE)
        
        setupPlayer()
        setupUI()
        updateSubtitleOffsetDisplay()
        loadContent()
        startFloatingService()
    }
    
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
    
    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.loadingIndicator.visibility = View.GONE
                        }
                        Player.STATE_BUFFERING -> {
                            binding.loadingIndicator.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            // Video ended
                        }
                    }
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val errorMessage = when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> 
                            getString(R.string.error_file_not_found)
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> 
                            getString(R.string.error_no_permission)
                        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> 
                            getString(R.string.error_unsupported_format)
                        else -> getString(R.string.error_loading_video) + ": " + (error.message ?: "Unknown error")
                    }
                    showError(errorMessage)
                }
            })
        }
        
        binding.playerView.player = exoPlayer
        binding.playerView.useController = false // Hide default controls
    }
    
    private fun setupUI() {
        binding.apply {
            btnSubtitlePanel.setOnClickListener {
                toggleSubtitlePanel()
            }
            
            btnCloseSubtitlePanel.setOnClickListener {
                hideSubtitlePanel()
            }
            
            btnSubtitleDelayPlus?.setOnClickListener {
                adjustSubtitleDelay(300) // +0.3 seconds
            }
            
            btnSubtitleDelayMinus?.setOnClickListener {
                adjustSubtitleDelay(-300) // -0.3 seconds
            }
            
            setupSeekBar()
        }
    }
    
    private fun setupSeekBar() {
        binding.seekBarProgress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.let { player ->
                        val duration = player.duration
                        if (duration > 0) {
                            val position = (duration * progress) / 1000L
                            binding.tvCurrentTime?.text = formatTime(position)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = false
                exoPlayer?.let { player ->
                    val duration = player.duration
                    if (duration > 0) {
                        val position = (duration * (seekBar?.progress ?: 0)) / 1000L
                        player.seekTo(position)
                        savePlaybackPosition(position)
                    }
                }
            }
        })
    }
    
    private fun loadContent() {
        val videoUrl = intent.getStringExtra("video_url") ?: return
        val audioUrl = intent.getStringExtra("audio_url")
        val subtitleUrl = intent.getStringExtra("subtitle_url")
        
        // Generate unique ID for this video combination
        currentVideoId = generateVideoId(videoUrl, audioUrl, subtitleUrl)
        
        // Load saved subtitle offset for this video
        loadSubtitleOffset()
        
        // Load saved playback position
        loadPlaybackPosition()
        
        // Create media source
        val mediaSource = createMediaSource(videoUrl, audioUrl)
        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        
        // Load subtitles if provided
        if (!subtitleUrl.isNullOrEmpty()) {
            loadSubtitles(subtitleUrl)
        }
        
        startSubtitleUpdates()
    }
    
    private fun createMediaSource(videoUrl: String, audioUrl: String?): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(this)
        
        return try {
            if (audioUrl != null && audioUrl.isNotEmpty()) {
                // Validate and prepare URIs
                val videoUri = Uri.parse(videoUrl)
                val audioUri = Uri.parse(audioUrl)
                
                // Check URI validity before proceeding
                if (!isUriAccessible(videoUri)) {
                    showUriNotAccessibleDialog(getString(R.string.error_video_file_not_accessible))
                    throw IllegalArgumentException("Video URI not accessible")
                }
                
                if (!isUriAccessible(audioUri)) {
                    showUriNotAccessibleDialog(getString(R.string.error_audio_file_not_accessible))
                    throw IllegalArgumentException("Audio URI not accessible")
                }
                
                grantUriPermissionIfNeeded(videoUri)
                grantUriPermissionIfNeeded(audioUri)
                
                // Create separate video and audio sources
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUri))
                
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(audioUri))
                
                // Merge video and audio sources
                MergingMediaSource(videoSource, audioSource)
            } else {
                // Single media source (video with embedded audio or video only)
                val videoUri = Uri.parse(videoUrl)
                
                if (!isUriAccessible(videoUri)) {
                    showUriNotAccessibleDialog(getString(R.string.error_video_file_not_accessible))
                    throw IllegalArgumentException("Video URI not accessible")
                }
                
                grantUriPermissionIfNeeded(videoUri)
                
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUri))
            }
        } catch (e: Exception) {
            showError("${getString(R.string.error_loading_video)}: ${e.message}")
            // Return a dummy source to prevent crash
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        }
    }
    
    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "content" -> {
                    // Check if content provider is available
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use { true } ?: false
                }
                "file" -> {
                    // Check if file exists
                    val file = java.io.File(uri.path ?: "")
                    file.exists() && file.canRead()
                }
                "http", "https" -> {
                    // For URLs, assume accessible (will be checked during loading)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun grantUriPermissionIfNeeded(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (e: Exception) {
            // Permission might already be granted or not needed
        }
    }
    
    private fun loadSubtitles(subtitleUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = if (subtitleUrl.startsWith("http")) {
                    // Load from URL
                    val client = OkHttpClient()
                    val request = Request.Builder().url(subtitleUrl).build()
                    val response = client.newCall(request).execute()
                    
                    if (!response.isSuccessful) {
                        throw IOException("Failed to load subtitles: ${response.code}")
                    }
                    
                    response.body?.byteStream() ?: throw IOException("Empty response body")
                } else {
                    // Load from local file/content URI
                    contentResolver.openInputStream(Uri.parse(subtitleUrl))
                        ?: throw IOException("Cannot open subtitle file")
                }
                
                val parsedSubtitles = SubtitleParser.parseSRT(inputStream)
                inputStream.close()
                
                withContext(Dispatchers.Main) {
                    subtitles = parsedSubtitles
                    setupSubtitleAdapter()
                    binding.btnSubtitlePanel.visibility = View.VISIBLE
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.error_loading_subtitles) + ": " + e.message)
                }
            }
        }
    }
    
    private fun setupSubtitleAdapter() {
        subtitleAdapter = SubtitleAdapter(subtitles) { subtitle ->
            // Seek to subtitle time when clicked, accounting for delay offset
            exoPlayer?.seekTo(subtitle.startTime - subtitleDelayMs)
        }
        
        binding.subtitleRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = subtitleAdapter
        }
    }
    
    private fun adjustSubtitleDelay(delayMs: Long) {
        subtitleDelayMs += delayMs
        updateSubtitleOffsetDisplay()
        saveSubtitleOffset()
    }
    
    private fun updateSubtitleOffsetDisplay() {
        val delaySeconds = subtitleDelayMs / 1000.0
        val offsetText = when {
            subtitleDelayMs > 0 -> "字幕: +${delaySeconds}s"
            subtitleDelayMs < 0 -> "字幕: ${delaySeconds}s"
            else -> "字幕: 0.0s"
        }
        binding.tvSubtitleOffset?.text = offsetText
    }
    
    private fun generateVideoId(videoUrl: String, audioUrl: String?, subtitleUrl: String?): String {
        val combined = "$videoUrl|${audioUrl ?: ""}|${subtitleUrl ?: ""}"
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(combined.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            combined.hashCode().toString()
        }
    }
    
    private fun saveSubtitleOffset() {
        if (currentVideoId.isNotEmpty()) {
            sharedPreferences.edit()
                .putLong("offset_$currentVideoId", subtitleDelayMs)
                .apply()
        }
    }
    
    private fun loadSubtitleOffset() {
        if (currentVideoId.isNotEmpty()) {
            subtitleDelayMs = sharedPreferences.getLong("offset_$currentVideoId", 0L)
            updateSubtitleOffsetDisplay()
        }
    }
    
    private fun savePlaybackPosition(position: Long) {
        if (currentVideoId.isNotEmpty()) {
            sharedPreferences.edit()
                .putLong("position_$currentVideoId", position)
                .apply()
        }
    }
    
    private fun loadPlaybackPosition() {
        if (currentVideoId.isNotEmpty()) {
            val savedPosition = sharedPreferences.getLong("position_$currentVideoId", 0L)
            if (savedPosition > 0) {
                // Seek to saved position when player is ready
                exoPlayer?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            exoPlayer?.seekTo(savedPosition)
                            exoPlayer?.removeListener(this)
                        }
                    }
                })
            }
        }
    }
    
    private fun formatTime(timeMs: Long): String {
        if (timeMs <= 0) return "00:00"
        
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            String.format("%02d:%02d:%02d", hours, remainingMinutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    private fun startSubtitleUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    val currentPosition = player.currentPosition
                    val adjustedPosition = currentPosition + subtitleDelayMs
                    val duration = player.duration
                    
                    // Update progress bar (only if not being dragged)
                    if (!isSeekBarTracking && duration > 0) {
                        val progress = ((currentPosition * 1000) / duration).toInt()
                        binding.seekBarProgress?.progress = progress
                        binding.tvCurrentTime?.text = formatTime(currentPosition)
                        binding.tvDuration?.text = formatTime(duration)
                        
                        // Save position periodically
                        if (currentPosition - lastSavedPosition > savePositionInterval) {
                            savePlaybackPosition(currentPosition)
                            lastSavedPosition = currentPosition
                        }
                    }
                    
                    // Update current subtitle display (with adjusted position)
                    val currentSubtitle = subtitles.find { it.isActiveAt(adjustedPosition) }
                    if (currentSubtitle != null) {
                        binding.subtitleView.text = currentSubtitle.text
                        binding.subtitleView.visibility = View.VISIBLE
                    } else {
                        binding.subtitleView.visibility = View.GONE
                    }
                    
                    // Update subtitle panel (with adjusted position)
                    subtitleAdapter?.updateActivePosition(adjustedPosition)
                }
                
                updateHandler.postDelayed(this, 100) // Update every 100ms
            }
        }
        updateHandler.post(updateRunnable!!)
    }
    
    private fun toggleSubtitlePanel() {
        if (binding.subtitlePanel.visibility == View.VISIBLE) {
            hideSubtitlePanel()
        } else {
            showSubtitlePanel()
        }
    }
    
    private fun showSubtitlePanel() {
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.btnSubtitlePanel.text = getString(R.string.hide_subtitles)
        
        // Auto-scroll to current subtitle if playing
        exoPlayer?.let { player ->
            val currentPosition = player.currentPosition + subtitleDelayMs
            subtitleAdapter?.updateActivePosition(currentPosition)
        }
    }
    
    private fun hideSubtitlePanel() {
        binding.subtitlePanel.visibility = View.GONE
        binding.btnSubtitlePanel.text = getString(R.string.show_subtitles)
    }
    
    private fun startFloatingService() {
        val intent = Intent(this, FloatingPlayerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.loadingIndicator.visibility = View.GONE
    }
    
    private fun showUriNotAccessibleDialog(message: String) {
        binding.loadingIndicator.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("File Access Issue")
            .setMessage("$message\n\nThis usually happens when:\n" +
                    "• Files were selected through a third-party file manager\n" +
                    "• The file manager app was updated or uninstalled\n" +
                    "• Files were moved or deleted\n\n" +
                    "Please go back and select the files again using the built-in Android file picker.")
            .setPositiveButton("Go Back") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onStop() {
        super.onStop()
        floatingService?.showFloatingWindow()
    }
    
    override fun onStart() {
        super.onStart()
        floatingService?.hideFloatingWindow()
    }
    
    override fun onPause() {
        super.onPause()
        // Save current position when leaving the activity
        exoPlayer?.let { player ->
            if (player.currentPosition > 0) {
                savePlaybackPosition(player.currentPosition)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
        
        exoPlayer?.release()
        
        // Stop floating service
        stopService(Intent(this, FloatingPlayerService::class.java))
    }
}