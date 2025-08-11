package com.videoplayerapp.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.videoplayerapp.R

@RequiresApi(Build.VERSION_CODES.N)
class FloatingWindowTileService : TileService() {

    private var floatingPlayerService: FloatingPlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingPlayerService.LocalBinder
            floatingPlayerService = binder.getService()
            isBound = true
            updateTileState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            floatingPlayerService = null
            isBound = false
            updateTileState()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        bindToFloatingPlayerService()
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onClick() {
        super.onClick()
        
        if (!canDrawOverlay()) {
            showOverlayPermissionDialog()
            return
        }

        toggleFloatingWindow()
    }

    private fun bindToFloatingPlayerService() {
        if (!isBound) {
            val intent = Intent(this, FloatingPlayerService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun toggleFloatingWindow() {
        floatingPlayerService?.let { service ->
            if (service.isFloatingWindowShown()) {
                service.hideFloatingWindow()
            } else {
                service.showFloatingWindow()
            }
            updateTileState()
        } ?: run {
            startFloatingPlayerService()
        }
    }

    private fun startFloatingPlayerService() {
        val intent = Intent(this, FloatingPlayerService::class.java)
        startService(intent)
        bindToFloatingPlayerService()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        
        val isShown = floatingPlayerService?.isFloatingWindowShown() ?: false
        
        tile.state = if (isShown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.floating_window_tile_label)
        tile.contentDescription = if (isShown) {
            getString(R.string.floating_window_tile_active_description)
        } else {
            getString(R.string.floating_window_tile_inactive_description)
        }
        
        tile.updateTile()
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun showOverlayPermissionDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
    }
}