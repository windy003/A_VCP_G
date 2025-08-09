package com.videoplayerapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
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

class PlayerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var subtitles = listOf<SubtitleItem>()
    private var subtitleAdapter: SubtitleAdapter? = null
    private var floatingService: FloatingPlayerService? = null
    private var isServiceBound = false
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
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
        
        setupPlayer()
        setupUI()
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
                    showError(getString(R.string.error_loading_video) + ": " + error.message)
                }
            })
        }
        
        binding.playerView.player = exoPlayer
    }
    
    private fun setupUI() {
        binding.apply {
            btnSubtitlePanel.setOnClickListener {
                toggleSubtitlePanel()
            }
            
            btnCloseSubtitlePanel.setOnClickListener {
                hideSubtitlePanel()
            }
        }
    }
    
    private fun loadContent() {
        val videoUrl = intent.getStringExtra("video_url") ?: return
        val audioUrl = intent.getStringExtra("audio_url")
        val subtitleUrl = intent.getStringExtra("subtitle_url")
        
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
        
        return if (audioUrl != null && audioUrl.isNotEmpty()) {
            // Create separate video and audio sources
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))
            
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            
            // Merge video and audio sources
            MergingMediaSource(videoSource, audioSource)
        } else {
            // Single media source (video with embedded audio or video only)
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))
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
            // Seek to subtitle time when clicked
            exoPlayer?.seekTo(subtitle.startTime)
        }
        
        binding.subtitleRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = subtitleAdapter
        }
    }
    
    private fun startSubtitleUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    val currentPosition = player.currentPosition
                    
                    // Update current subtitle display
                    val currentSubtitle = subtitles.find { it.isActiveAt(currentPosition) }
                    if (currentSubtitle != null) {
                        binding.subtitleView.text = currentSubtitle.text
                        binding.subtitleView.visibility = View.VISIBLE
                    } else {
                        binding.subtitleView.visibility = View.GONE
                    }
                    
                    // Update subtitle panel
                    subtitleAdapter?.updateActivePosition(currentPosition)
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
            val currentPosition = player.currentPosition
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
    
    override fun onStop() {
        super.onStop()
        floatingService?.showFloatingWindow()
    }
    
    override fun onStart() {
        super.onStart()
        floatingService?.hideFloatingWindow()
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