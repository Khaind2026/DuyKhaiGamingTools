package com.duykhai.gaming

import android.content.Intent
import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A PCM DeX Quick Settings entry. Explicit user action only, and never
 * attempts to escalate Root permissions in a background process.
 */
class PcmDexTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (PcmDexMaintainService.active) {
            stopService(Intent(this, PcmDexMaintainService::class.java))
        } else if (RootAccess.status() || CommandRunner.ready()) {
            val hz = getSharedPreferences("pcm_dex", MODE_PRIVATE)
                .getInt("target_hz", 120)
            try {
                startForegroundService(Intent(this, PcmDexMaintainService::class.java)
                    .setAction(PcmDexMaintainService.ACTION_START)
                    .putExtra("target_hz", hz))
            } catch (_: Exception) { }
        } else {
            val intent = Intent(this, PcmGamingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(this, 71, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            label = "PCM DeX"
            state = if (PcmDexMaintainService.active) Tile.STATE_ACTIVE
                else Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                subtitle = if (PcmDexMaintainService.active) "Đang duy trì HDMI"
                    else "Nhấn để bật DeX"
            }
            updateTile()
        }
    }
}
