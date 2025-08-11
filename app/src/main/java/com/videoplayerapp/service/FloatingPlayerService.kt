package com.videoplayerapp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videoplayerapp.R
import kotlin.math.pow
import kotlin.math.sqrt

class FloatingPlayerService : Service() {
    
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var isFloatingWindowShown = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private val floatingWindowHandler = Handler(Looper.getMainLooper())
    private var floatingWindowUpdateRunnable: Runnable? = null
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): FloatingPlayerService = this@FloatingPlayerService
    }
    
    companion object {
        // Companion object kept for future constants if needed
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    fun setPlayer(exoPlayer: ExoPlayer?) {
        this.player = exoPlayer
        if (exoPlayer != null) {
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateFloatingWindowProgress() // 立即更新悬浮窗进度
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateFloatingWindowProgress() // 立即更新悬浮窗进度
                }
                
                override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                    // 当播放位置发生跳跃时（如用户拖动进度条），立即同步悬浮窗
                    updateFloatingWindowProgress()
                }
            })
        }
    }
    
    fun showFloatingWindow() {
        if (isFloatingWindowShown || !canDrawOverlay()) {
            return
        }
        
        createFloatingWindow()
        isFloatingWindowShown = true
        // 立即更新一次进度，确保悬浮窗显示最新状态
        updateFloatingWindowProgress()
        startFloatingWindowUpdates()
    }
    
    fun hideFloatingWindow() {
        if (!isFloatingWindowShown) return
        
        stopFloatingWindowUpdates()
        try {
            floatingView?.let { view ->
                windowManager?.removeView(view)
            }
            floatingView = null
            isFloatingWindowShown = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun isFloatingWindowShown(): Boolean {
        return isFloatingWindowShown
    }
    
    fun forceUpdateProgress() {
        updateFloatingWindowProgress()
    }
    
    private fun createFloatingWindow() {
        if (!canDrawOverlay()) return
        
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_layout, null)
        
        layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
        
        setupFloatingWindowControls()
        
        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupFloatingWindowControls() {
        floatingView?.let { view ->
            val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)
            val btnRewind = view.findViewById<ImageButton>(R.id.btnRewind)
            val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
            
            // Setup draggable buttons with click functionality
            setupDraggableButton(btnPlayPause) {
                player?.let { exoPlayer ->
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        exoPlayer.play()
                        btnPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                }
            }
            
            setupDraggableButton(btnRewind) {
                player?.let { exoPlayer ->
                    val currentPosition = exoPlayer.currentPosition
                    val newPosition = maxOf(0, currentPosition - 5000) // Rewind 5 seconds
                    exoPlayer.seekTo(newPosition)
                }
            }
            
            setupDraggableButton(btnClose) {
                hideFloatingWindow()
            }
            
            // Update play/pause button based on current player state
            updatePlayPauseButton(btnPlayPause)
        }
    }
    
    private fun updatePlayPauseButton(button: ImageButton) {
        player?.let { exoPlayer ->
            if (exoPlayer.isPlaying) {
                button.setImageResource(R.drawable.ic_pause)
            } else {
                button.setImageResource(R.drawable.ic_play)
            }
        }
    }
    
    private fun setupDraggableButton(button: View, clickAction: () -> Unit) {
        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L
            private val clickTimeThreshold = 200L // 200ms
            private val moveThreshold = 10f // 10px
            
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        layoutParams?.let { params ->
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            startTime = System.currentTimeMillis()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams?.let { params ->
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val endTime = System.currentTimeMillis()
                        val timeDiff = endTime - startTime
                        val moveDistance = sqrt(
                            (event.rawX - initialTouchX).toDouble().pow(2.0) +
                            (event.rawY - initialTouchY).toDouble().pow(2.0)
                        ).toFloat()
                        
                        // If it's a quick tap with minimal movement, treat as click
                        if (timeDiff < clickTimeThreshold && moveDistance < moveThreshold) {
                            clickAction.invoke()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }
    
    private fun startFloatingWindowUpdates() {
        stopFloatingWindowUpdates()
        floatingWindowUpdateRunnable = object : Runnable {
            override fun run() {
                updateFloatingWindowProgress()
                floatingWindowHandler.postDelayed(this, 500) // Update every 0.5 second for better sync
            }
        }
        floatingWindowHandler.post(floatingWindowUpdateRunnable!!)
    }
    
    private fun stopFloatingWindowUpdates() {
        floatingWindowUpdateRunnable?.let { 
            floatingWindowHandler.removeCallbacks(it)
            floatingWindowUpdateRunnable = null
        }
    }
    
    private fun updateFloatingWindowProgress() {
        floatingView?.let { view ->
            val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
            val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)
            val tvTimeInfo = view.findViewById<TextView>(R.id.tvTimeInfo)
            
            player?.let { exoPlayer ->
                val currentPosition = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                
                // Update progress bar
                if (duration > 0) {
                    val progressPercent = ((currentPosition.toFloat() / duration.toFloat()) * 100).toInt()
                    progressBar?.progress = progressPercent
                } else {
                    progressBar?.progress = 0
                }
                
                // Update time text
                val currentTimeStr = formatTime(currentPosition)
                val durationStr = if (duration > 0) formatTime(duration) else "00:00"
                val progressPercent = if (duration > 0) {
                    ((currentPosition.toFloat() / duration.toFloat()) * 100).toInt()
                } else 0
                tvTimeInfo?.text = "$currentTimeStr / $durationStr ($progressPercent%)"
                
                // Update play/pause button
                updatePlayPauseButton(btnPlayPause)
            } ?: run {
                // When no player is available, show default time
                tvTimeInfo?.text = "00:00 / 00:00"
                progressBar?.progress = 0
            }
        }
    }
    
    
    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
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
    
    override fun onDestroy() {
        super.onDestroy()
        stopFloatingWindowUpdates()
        hideFloatingWindow()
    }
}