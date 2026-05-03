package app.jzad.openbeam

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class OpenBeamTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val next = !OpenBeamPrefs.isTileReady(this)
        OpenBeamPrefs.setTileReady(this, next)
        updateTile()

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_FROM_TILE, true)
            .putExtra(MainActivity.EXTRA_TILE_READY, next)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val ready = OpenBeamPrefs.isTileReady(this)

        tile.state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (ready) getString(R.string.tile_active_subtitle) else getString(R.string.tile_inactive_subtitle)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: android.content.Context) {
            requestListeningState(context, ComponentName(context, OpenBeamTileService::class.java))
        }
    }
}
