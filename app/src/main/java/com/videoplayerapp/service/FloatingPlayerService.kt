package com.videoplayerapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import com.videoplayerapp.MainActivity
import com.videoplayerapp.R

class FloatingPlayerService : Service() {
    
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var isFloatingWindowShown = false
    
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
    }
    
    fun showFloatingWindow() {
        if (isFloatingWindowShown || !canDrawOverlay()) {
            return
        }
        
        createFloatingWindow()
        isFloatingWindowShown = true
    }
    
    fun hideFloatingWindow() {
        if (!isFloatingWindowShown) return
        
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
    
    private fun createFloatingWindow() {
        if (!canDrawOverlay()) return
        
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_layout, null)
        
        val layoutParams = WindowManager.LayoutParams().apply {
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
            
            // Make the floating window draggable
            makeWindowDraggable(layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupFloatingWindowControls() {
        floatingView?.let { view ->
            val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)
            val btnRewind = view.findViewById<ImageButton>(R.id.btnRewind)
            val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
            
            btnPlayPause.setOnClickListener {
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
            
            btnRewind.setOnClickListener {
                player?.let { exoPlayer ->
                    val currentPosition = exoPlayer.currentPosition
                    val newPosition = maxOf(0, currentPosition - 5000) // Rewind 5 seconds
                    exoPlayer.seekTo(newPosition)
                }
            }
            
            btnClose.setOnClickListener {
                hideFloatingWindow()
                
                // Return to main activity
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
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
    
    private fun makeWindowDraggable(layoutParams: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_player_notification_title))
            .setContentText(getString(R.string.floating_player_notification_desc))
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideFloatingWindow()
    }
}