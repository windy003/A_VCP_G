package com.videoplayerapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
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
    private var lastSavedPosition = 0L
    private val savePositionInterval = 5000L // Save position every 5 seconds
    private var mediaSession: MediaSessionCompat? = null
    private var isClearScreenMode = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingPlayerService.LocalBinder
            floatingService = binder.getService()
            // 确保服务获得正确的播放器引用，并立即同步状态
            exoPlayer?.let { player ->
                floatingService?.setPlayer(player)
            }
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
        setupMediaSession()
        updateSubtitleOffsetDisplay()
        loadContent()
        startFloatingService()

        // Ensure Activity always receives key events
        // Request focus after all setup is complete
        window.decorView.requestFocus()
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
                            // 确保悬浮窗服务获得最新的播放器引用
                            floatingService?.setPlayer(this@apply)
                            // 强制显示控制器
                            binding.playerView.showController()
                        }
                        Player.STATE_BUFFERING -> {
                            binding.loadingIndicator.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            // Video ended - restart for loop playback
                            exoPlayer?.seekTo(0)
                            exoPlayer?.playWhenReady = true
                        }
                    }
                    updateMediaSessionPlaybackState()
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateMediaSessionPlaybackState()
                }
                
                override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                    // 当播放位置发生跳跃时，确保悬浮窗立即同步
                    floatingService?.let { service ->
                        service.setPlayer(this@apply) // 重新设置播放器确保同步
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
        binding.playerView.useController = true // Use default ExoPlayer controls
        binding.playerView.controllerAutoShow = true // Show automatically
        binding.playerView.controllerHideOnTouch = true // Allow hiding on touch

        // Make PlayerView focusable to receive key events
        binding.playerView.isFocusable = true
        binding.playerView.isFocusableInTouchMode = true
        binding.playerView.requestFocus()

        // Critical: Intercept DPAD key events BEFORE the controller handles them
        binding.playerView.setOnKeyListener { view, keyCode, event ->
            android.util.Log.d("PlayerActivity", "PlayerView.onKey: keyCode=$keyCode, action=${event.action}")

            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        android.util.Log.d("PlayerActivity", ">>> PlayerView: DPAD_LEFT intercepted <<<")
                        exoPlayer?.let { player ->
                            val currentPosition = player.currentPosition
                            val newPosition = (currentPosition - 5000).coerceAtLeast(0)
                            android.util.Log.d("PlayerActivity", "Seeking from $currentPosition to $newPosition")
                            player.seekTo(newPosition)
                            if (!isClearScreenMode) {
                                binding.playerView.showController()
                            }
                            Toast.makeText(this, "后退5秒", Toast.LENGTH_SHORT).show()
                        }
                        true // Consume the event to prevent controller from handling it
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        android.util.Log.d("PlayerActivity", ">>> PlayerView: DPAD_RIGHT intercepted <<<")
                        exoPlayer?.let { player ->
                            val currentPosition = player.currentPosition
                            val duration = player.duration
                            val newPosition = if (duration > 0) {
                                (currentPosition + 5000).coerceAtMost(duration)
                            } else {
                                currentPosition + 5000
                            }
                            android.util.Log.d("PlayerActivity", "Seeking from $currentPosition to $newPosition")
                            player.seekTo(newPosition)
                            if (!isClearScreenMode) {
                                binding.playerView.showController()
                            }
                            Toast.makeText(this, "前进5秒", Toast.LENGTH_SHORT).show()
                        }
                        true // Consume the event to prevent controller from handling it
                    }
                    else -> false // Let controller handle other keys
                }
            } else {
                false
            }
        }
    }
    
    private fun setupUI() {
        binding.apply {
            // Set touch listener on root layout to reclaim focus after any touch
            rootLayout.setOnTouchListener { v, event ->
                // Re-request focus after touch
                v.requestFocus()
                false // Don't consume the event, let it propagate
            }

            btnToggleControls?.setOnClickListener {
                if (binding.playerView.isControllerFullyVisible()) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
                // Re-request focus after button click
                binding.rootLayout.requestFocus()
            }

            btnSubtitlePanel.setOnClickListener {
                toggleSubtitlePanel()
                binding.rootLayout.requestFocus()
            }

            btnCloseSubtitlePanel.setOnClickListener {
                hideSubtitlePanel()
                binding.rootLayout.requestFocus()
            }

            btnSubtitleDelayPlus?.setOnClickListener {
                adjustSubtitleDelay(300) // +0.3 seconds
                binding.rootLayout.requestFocus()
            }

            btnSubtitleDelayMinus?.setOnClickListener {
                adjustSubtitleDelay(-300) // -0.3 seconds
                binding.rootLayout.requestFocus()
            }

            btnClearScreenInline?.setOnClickListener {
                toggleClearScreenMode()
                binding.rootLayout.requestFocus()
            }

            btnClearScreenFloat?.setOnClickListener {
                toggleClearScreenMode()
                binding.rootLayout.requestFocus()
            }

            btnClearScreenTop?.setOnClickListener {
                toggleClearScreenMode()
                binding.rootLayout.requestFocus()
            }

            clearScreenOverlay?.setOnClickListener {
                toggleClearScreenMode()
                binding.rootLayout.requestFocus()
            }
        }
    }
    
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VideoPlayerApp").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    exoPlayer?.play()
                }
                
                override fun onPause() {
                    exoPlayer?.pause()
                }
                
                override fun onSkipToNext() {
                    // 快进5秒并显示进度条
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        val newPosition = if (duration > 0) {
                            (currentPosition + 5000).coerceAtMost(duration)
                        } else {
                            currentPosition + 5000
                        }
                        player.seekTo(newPosition)
                        // 只有在非清屏模式下才显示控制器
                        if (!isClearScreenMode) {
                            binding.playerView.showController()
                        }
                    }
                }
                
                override fun onSkipToPrevious() {
                    // 后退5秒并显示进度条
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
                        player.seekTo(newPosition)
                        // 只有在非清屏模式下才显示控制器
                        if (!isClearScreenMode) {
                            binding.playerView.showController()
                        }
                    }
                }
                
                override fun onSeekTo(pos: Long) {
                    exoPlayer?.seekTo(pos)
                }
            })
            isActive = true
        }
        updateMediaSessionPlaybackState()
    }
    
    private fun updateMediaSessionPlaybackState() {
        val player = exoPlayer ?: return
        val session = mediaSession ?: return
        
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, player.currentPosition, 1.0f)
            .build()
            
        session.setPlaybackState(playbackState)
    }
    
    
    private fun loadContent() {
        // Check if this activity was launched to view a video file from external app
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            // Handle external file opening
            val videoUri = intent.data!!
            val videoUrl = videoUri.toString()
            
            // Generate unique ID for this video
            currentVideoId = generateVideoId(videoUrl, null, null)
            
            // Load saved subtitle offset for this video
            loadSubtitleOffset()
            
            // Load saved playback position
            loadPlaybackPosition()
            
            // Create media source for the external video
            val mediaSource = createMediaSource(videoUrl, null)
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
            
            startSubtitleUpdates()
            return
        }
        
        // Original logic for internal app usage
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

                // Create separate video and audio sources using ProgressiveMediaSource
                // (for local files, separate audio/video tracks are typically progressive)
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUri))

                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(audioUri))

                // Merge video and audio sources
                MergingMediaSource(videoSource, audioSource)
            } else {
                // Single media source - let ExoPlayer auto-detect the appropriate source type
                // This supports HTTP/HTTPS, RTSP, DASH, HLS, and local files
                val videoUri = Uri.parse(videoUrl)

                if (!isUriAccessible(videoUri)) {
                    showUriNotAccessibleDialog(getString(R.string.error_video_file_not_accessible))
                    throw IllegalArgumentException("Video URI not accessible")
                }

                grantUriPermissionIfNeeded(videoUri)

                // Use ProgressiveMediaSource for local files and simple HTTP streams
                // ExoPlayer will automatically handle RTSP, DASH, HLS when respective libraries are included
                when (videoUri.scheme?.lowercase()) {
                    "rtsp" -> {
                        // For RTSP, use MediaItem and let ExoPlayer create the appropriate source
                        // The RTSP library will be used automatically
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(videoUri))
                    }
                    "http", "https" -> {
                        // For HTTP/HTTPS, check if it's HLS or DASH by URL pattern
                        // Otherwise use Progressive
                        val urlString = videoUri.toString().lowercase()
                        if (urlString.contains(".m3u8") || urlString.contains("hls")) {
                            // HLS stream
                            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(videoUri))
                        } else if (urlString.contains(".mpd") || urlString.contains("dash")) {
                            // DASH stream
                            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(videoUri))
                        } else {
                            // Progressive HTTP stream or regular video file
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(videoUri))
                        }
                    }
                    else -> {
                        // Local files (file://, content://) use Progressive
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(videoUri))
                    }
                }
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
            when (uri.scheme?.lowercase()) {
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
                "http", "https", "rtsp", "rtmp" -> {
                    // For network streams, assume accessible (will be checked during loading)
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
                android.util.Log.d("SubtitleLoader", "Loading subtitles from: $subtitleUrl")
                
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
                    android.util.Log.d("SubtitleLoader", "Opening local file: $subtitleUrl")
                    val uri = Uri.parse(subtitleUrl)
                    
                    // Try different methods to open the file
                    when {
                        subtitleUrl.startsWith("content://") -> {
                            // Content URI from system file picker
                            contentResolver.openInputStream(uri)
                        }
                        subtitleUrl.startsWith("file://") -> {
                            // File URI from third-party apps - try multiple approaches
                            try {
                                // Method 1: Try ContentResolver first
                                contentResolver.openInputStream(uri)
                            } catch (e: Exception) {
                                android.util.Log.d("SubtitleLoader", "ContentResolver failed, trying direct file access")
                                try {
                                    // Method 2: Try direct file access
                                    val path = uri.path ?: throw IOException("Invalid file path")
                                    java.io.FileInputStream(java.io.File(path))
                                } catch (e2: Exception) {
                                    android.util.Log.d("SubtitleLoader", "Direct file access failed, trying DocumentFile")
                                    // Method 3: Try DocumentFile API
                                    val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@PlayerActivity, uri)
                                    if (documentFile?.exists() == true) {
                                        contentResolver.openInputStream(uri)
                                    } else {
                                        throw IOException("File not accessible through any method: $subtitleUrl")
                                    }
                                }
                            }
                        }
                        else -> {
                            // Other URI schemes
                            contentResolver.openInputStream(uri)
                        }
                    } ?: throw IOException("Cannot open subtitle file: $subtitleUrl")
                }
                
                android.util.Log.d("SubtitleLoader", "Parsing subtitles...")
                val parsedSubtitles = SubtitleParser.parseSubtitles(inputStream)
                inputStream.close()
                
                android.util.Log.d("SubtitleLoader", "Parsed ${parsedSubtitles.size} subtitle items")
                
                withContext(Dispatchers.Main) {
                    if (parsedSubtitles.isEmpty()) {
                        showError("No subtitles found in file. Please check if the file format is correct (SRT/VTT).")
                    } else {
                        subtitles = parsedSubtitles
                        setupSubtitleAdapter()
                        binding.btnSubtitlePanel.visibility = View.VISIBLE
                        android.util.Log.d("SubtitleLoader", "Subtitles loaded successfully")
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("SubtitleLoader", "Error loading subtitles", e)
                withContext(Dispatchers.Main) {
                    val errorMessage = when (e) {
                        is IllegalArgumentException -> "Invalid subtitle format: ${e.message}"
                        is IOException -> "File access error: ${e.message}"
                        else -> "Unexpected error: ${e.message}"
                    }
                    showError("${getString(R.string.error_loading_subtitles)}: $errorMessage")
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
                    
                    // Save position periodically
                    if (currentPosition - lastSavedPosition > savePositionInterval) {
                        savePlaybackPosition(currentPosition)
                        lastSavedPosition = currentPosition
                    }
                    
                    // Update current subtitle display (with adjusted position for delay)
                    // 只有在非清屏模式下才显示字幕
                    if (!isClearScreenMode) {
                        val currentSubtitle = subtitles.find { it.isActiveAt(adjustedPosition) }
                        if (currentSubtitle != null) {
                            binding.subtitleView.text = currentSubtitle.text
                            binding.subtitleView.visibility = View.VISIBLE
                        } else {
                            binding.subtitleView.visibility = View.GONE
                        }
                    }
                    
                    // Update subtitle panel (with adjusted position for delay)
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
    
    private fun toggleClearScreenMode() {
        isClearScreenMode = !isClearScreenMode
        updateClearScreenUI()
    }
    
    private fun updateClearScreenUI() {
        if (isClearScreenMode) {
            // 清屏模式：隐藏所有控制元素和UI
            binding.playerView.hideController()
            binding.playerView.useController = false
            binding.controlsOverlay.visibility = View.GONE
            binding.subtitleView.visibility = View.GONE
            binding.subtitlePanel.visibility = View.GONE
            binding.btnClearScreenInline?.visibility = View.GONE
            binding.btnClearScreenFloat?.visibility = View.GONE
            binding.btnClearScreenTop?.visibility = View.GONE
            binding.clearScreenOverlay?.visibility = View.VISIBLE
            binding.clearScreenHint?.visibility = View.VISIBLE
            
            // 3秒后隐藏提示文字
            Handler(Looper.getMainLooper()).postDelayed({
                binding.clearScreenHint?.visibility = View.GONE
            }, 3000)
            
            // 隐藏系统UI获得完全全屏体验
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } else {
            // 正常模式：显示控制器和界面元素
            binding.playerView.useController = true
            binding.playerView.controllerHideOnTouch = false
            binding.playerView.controllerShowTimeoutMs = 0
            binding.playerView.showController()
            binding.controlsOverlay.visibility = View.VISIBLE
            binding.btnClearScreenInline?.visibility = View.VISIBLE
            binding.btnClearScreenFloat?.visibility = View.VISIBLE
            binding.btnClearScreenTop?.visibility = View.VISIBLE
            binding.clearScreenOverlay?.visibility = View.GONE
            binding.clearScreenHint?.visibility = View.GONE
            
            // 恢复原来的系统UI设置
            hideSystemUI()
            
            // 字幕根据实际情况显示
            exoPlayer?.let { player ->
                val currentPosition = player.currentPosition + subtitleDelayMs
                val currentSubtitle = subtitles.find { it.isActiveAt(currentPosition) }
                if (currentSubtitle != null) {
                    binding.subtitleView.text = currentSubtitle.text
                    binding.subtitleView.visibility = View.VISIBLE
                } else {
                    binding.subtitleView.visibility = View.GONE
                }
            }
        }
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
        // 强制同步进度，但不自动显示悬浮窗
        floatingService?.forceUpdateProgress()
        // 移除自动显示悬浮窗的逻辑，只有通过 QS Tile 按钮才能显示
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
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // CRITICAL: Handle DPAD keys BEFORE calling super to prevent PlayerView from consuming them
        if (event.action == KeyEvent.ACTION_DOWN) {
            android.util.Log.d("PlayerActivity", "dispatchKeyEvent: keyCode=${event.keyCode}, name=${KeyEvent.keyCodeToString(event.keyCode)}")

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    android.util.Log.d("PlayerActivity", ">>> DPAD_LEFT - Seeking backward <<<")
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
                        android.util.Log.d("PlayerActivity", "Seek: $currentPosition -> $newPosition")
                        player.seekTo(newPosition)
                        if (!isClearScreenMode) {
                            binding.playerView.showController()
                        }
                        Toast.makeText(this, "⏪ 后退5秒", Toast.LENGTH_SHORT).show()
                    }
                    return true // Consume event - don't let it reach PlayerView
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    android.util.Log.d("PlayerActivity", ">>> DPAD_RIGHT - Seeking forward <<<")
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        val newPosition = if (duration > 0) {
                            (currentPosition + 5000).coerceAtMost(duration)
                        } else {
                            currentPosition + 5000
                        }
                        android.util.Log.d("PlayerActivity", "Seek: $currentPosition -> $newPosition")
                        player.seekTo(newPosition)
                        if (!isClearScreenMode) {
                            binding.playerView.showController()
                        }
                        Toast.makeText(this, "⏩ 前进5秒", Toast.LENGTH_SHORT).show()
                    }
                    return true // Consume event - don't let it reach PlayerView
                }
            }
        }

        // For all other keys, let the system handle them
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.e("PlayerActivity", "!!! onKeyDown called: keyCode=$keyCode, name=${KeyEvent.keyCodeToString(keyCode)}")
        Toast.makeText(this, "Key: ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                android.util.Log.e("PlayerActivity", "!!! onKeyDown handling DPAD_LEFT")
                exoPlayer?.let { player ->
                    val currentPosition = player.currentPosition
                    val newPosition = (currentPosition - 5000).coerceAtLeast(0)
                    player.seekTo(newPosition)
                    if (!isClearScreenMode) {
                        binding.playerView.showController()
                    }
                    Toast.makeText(this, "⏪ 后退5秒 (onKeyDown)", Toast.LENGTH_SHORT).show()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                android.util.Log.e("PlayerActivity", "!!! onKeyDown handling DPAD_RIGHT")
                exoPlayer?.let { player ->
                    val currentPosition = player.currentPosition
                    val duration = player.duration
                    val newPosition = if (duration > 0) {
                        (currentPosition + 5000).coerceAtMost(duration)
                    } else {
                        currentPosition + 5000
                    }
                    player.seekTo(newPosition)
                    if (!isClearScreenMode) {
                        binding.playerView.showController()
                    }
                    Toast.makeText(this, "⏩ 前进5秒 (onKeyDown)", Toast.LENGTH_SHORT).show()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                // 处理播放/暂停按键
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                    return true
                }
                false
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                // 处理播放按键
                exoPlayer?.play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // 处理暂停按键
                exoPlayer?.pause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.e("PlayerActivity", "!!! onKeyUp called: keyCode=$keyCode")
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-request focus whenever window regains focus
            window.decorView.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure we have focus when resuming
        window.decorView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Save current position before destroying
        exoPlayer?.let { player ->
            if (player.currentPosition > 0) {
                savePlaybackPosition(player.currentPosition)
            }
        }

        updateRunnable?.let { updateHandler.removeCallbacks(it) }

        if (isServiceBound) {
            unbindService(serviceConnection)
        }

        exoPlayer?.release()

        // Release media session
        mediaSession?.release()

        // Stop floating service
        stopService(Intent(this, FloatingPlayerService::class.java))
    }
}