package com.videoplayerapp.service

import android.app.*
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
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videoplayerapp.MainActivity
import com.videoplayerapp.R
import kotlin.math.pow
import kotlin.math.sqrt

class FloatingPlayerService : Service() {
    
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var isFloatingWindowShown = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationUpdateRunnable: Runnable? = null
    private val floatingWindowHandler = Handler(Looper.getMainLooper())
    private var floatingWindowUpdateRunnable: Runnable? = null
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): FloatingPlayerService = this@FloatingPlayerService
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "floating_player_channel"
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    fun setPlayer(exoPlayer: ExoPlayer?) {
        this.player = exoPlayer
        if (exoPlayer != null) {
            startNotificationUpdates()
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateNotification()
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotification()
                }
            })
        } else {
            stopNotificationUpdates()
        }
    }
    
    fun showFloatingWindow() {
        if (isFloatingWindowShown || !canDrawOverlay()) {
            return
        }
        
        createFloatingWindow()
        isFloatingWindowShown = true
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
                floatingWindowHandler.postDelayed(this, 1000) // Update every second
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
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_player_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for floating video player"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        player?.let { exoPlayer ->
            val currentPosition = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            val isPlaying = exoPlayer.isPlaying
            
            val currentTimeStr = formatTime(currentPosition)
            val durationStr = if (duration > 0) formatTime(duration) else "--:--"
            val progressPercent = if (duration > 0) {
                ((currentPosition.toFloat() / duration.toFloat()) * 100).toInt()
            } else 0
            
            val title = if (isPlaying) "正在播放视频" else "视频已暂停"
            val text = "$currentTimeStr / $durationStr ($progressPercent%)"
            
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progressPercent, false)
                .build()
        }
        
        // Fallback notification when no player is available
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_player_notification_title))
            .setContentText(getString(R.string.floating_player_notification_desc))
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    private fun startNotificationUpdates() {
        stopNotificationUpdates()
        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                updateNotification()
                notificationHandler.postDelayed(this, 1000) // Update every second
            }
        }
        notificationHandler.post(notificationUpdateRunnable!!)
    }
    
    private fun stopNotificationUpdates() {
        notificationUpdateRunnable?.let { 
            notificationHandler.removeCallbacks(it)
            notificationUpdateRunnable = null
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
        stopNotificationUpdates()
        stopFloatingWindowUpdates()
        hideFloatingWindow()
    }
}