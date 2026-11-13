package dev.relay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RelayTile : TileService() {

    private var scope: CoroutineScope? = null

    // While the shade is open, follow the session live (a stop issued from
    // the tile completes asynchronously, so a one-shot refresh would lie).
    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = MainScope().also { s ->
            s.launch { RelayState.session.collect { updateTile() } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (RelayState.isActive) {
            RelayService.stop(applicationContext)
            return
        }
        if (isLocked) unlockAndRun { launchRelay() } else launchRelay()
    }

    /** Opens the app, which asks for capture consent and relays to the last speaker. */
    private fun launchRelay() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val session = RelayState.session.value
        tile.state = if (session != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = session?.speaker?.name
            ?: Prefs.lastSpeaker(this)?.name
            ?: "Pick a speaker in the app"
        tile.updateTile()
    }
}
