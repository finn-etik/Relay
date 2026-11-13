package dev.relay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var speakers by mutableStateOf<List<SonosSpeaker>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var wifiLock by mutableStateOf(false)

    // Survives rotation / process death while the consent dialog is up.
    private var pendingSpeaker: SonosSpeaker? = null
    private var pendingFromTile = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                askForProjection()
            } else {
                pendingSpeaker = null
                RelayState.say("MIC PERMISSION DENIED / ANDROID REQUIRES IT FOR AUDIO CAPTURE")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val speaker = pendingSpeaker?.let(::freshest)
            val fromTile = pendingFromTile
            pendingSpeaker = null
            pendingFromTile = false
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null && speaker != null) {
                RelayService.start(this, result.resultCode, data, speaker)
                // Started from the tile: drop back to whatever the user was doing.
                if (fromTile) moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light body: force dark system-bar icons regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        RelayService.recoverPhoneVolume(this)
        wifiLock = RelayService.wifiLockEnabled(this)
        savedInstanceState?.let(::restorePending)

        setContent {
            RelayScreen(
                speakers = speakers,
                scanning = scanning,
                wifiLock = wifiLock,
                onSelect = { select(it) },
                onStop = { RelayService.stop(this) },
                onRescan = ::rescan,
                onVolume = { steps -> RelayService.nudgeVolume(this, steps) },
                onWifiLock = {
                    wifiLock = !wifiLock
                    RelayService.setWifiLock(this, wifiLock)
                }
            )
        }

        rescan()
        // Only on a genuinely new launch, not on rotation (which would
        // otherwise pop a second consent dialog).
        if (savedInstanceState == null) handleAutoStart(intent)
    }

    // singleTask: a tile tap while the app is alive arrives here, not onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStart(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSpeaker?.let {
            outState.putString(STATE_NAME, it.name)
            outState.putString(STATE_IP, it.ip)
            outState.putString(STATE_UUID, it.uuid)
            outState.putBoolean(STATE_TILE, pendingFromTile)
        }
    }

    private fun restorePending(state: Bundle) {
        val name = state.getString(STATE_NAME) ?: return
        val ip = state.getString(STATE_IP) ?: return
        pendingSpeaker = SonosSpeaker(name, ip, state.getString(STATE_UUID) ?: "")
        pendingFromTile = state.getBoolean(STATE_TILE)
    }

    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) != true) return
        intent.removeExtra(EXTRA_AUTO_START)
        if (RelayState.isActive) return
        SonosDiscovery.lastSpeaker(this)?.let { select(it, fromTile = true) }
    }

    private fun select(speaker: SonosSpeaker, fromTile: Boolean = false) {
        RelayState.say(null)
        if (RelayState.isActive) {
            // Already live: just re-point the stream. No second consent.
            RelayService.switchTo(this, speaker)
            return
        }
        pendingSpeaker = speaker
        pendingFromTile = fromTile
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askForProjection()
        } else {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    private fun askForProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** A saved speaker may have a new DHCP address; prefer the live one. */
    private fun freshest(speaker: SonosSpeaker): SonosSpeaker =
        speakers.firstOrNull { speaker.uuid.isNotEmpty() && it.uuid == speaker.uuid } ?: speaker

    private fun rescan() {
        if (scanning) return
        scanning = true
        lifecycleScope.launch {
            // Discovery blocks on sockets: never on the main thread.
            val found = withContext(Dispatchers.IO) { SonosDiscovery.discover(applicationContext) }
            speakers = found
            scanning = false
        }
    }

    companion object {
        const val EXTRA_AUTO_START = "dev.relay.AUTO_START"
        private const val STATE_NAME = "pending_name"
        private const val STATE_IP = "pending_ip"
        private const val STATE_UUID = "pending_uuid"
        private const val STATE_TILE = "pending_tile"
    }
}
