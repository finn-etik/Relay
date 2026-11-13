package dev.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.security.SecureRandom

/**
 * The foreground service that runs a relay session.
 *
 * A session wires together:
 *   [CapturePipeline]   playback audio -> AAC
 *   [StreamServer]      serves that AAC over HTTP on the phone
 *   [SonosControl]      tells the speaker to play the stream URL
 *   [PhoneVolume]       mutes the phone for the duration
 *   [SonosVolumeKeys]   volume keys -> speaker volume
 *   [ReconnectWatchdog] re-connects the speaker if it drops the stream
 *
 * All lifecycle methods run on the main thread.
 */
class RelayService : Service() {

    companion object {
        const val PORT = 8079
        private const val STREAM_TITLE = "Relay"
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L

        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIFICATION_ID = 11

        private const val ACTION_START = "dev.relay.START"
        private const val ACTION_STOP = "dev.relay.STOP"
        private const val ACTION_SWITCH = "dev.relay.SWITCH"
        private const val ACTION_VOLUME = "dev.relay.VOLUME"
        private const val ACTION_WIFI_LOCK = "dev.relay.WIFI_LOCK"
        private const val EXTRA_RESULT_CODE = "code"
        private const val EXTRA_CONSENT = "data"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_STEPS = "steps"
        private const val EXTRA_ENABLED = "enabled"

        /** Starts a session. [consent] is the result of the screen-capture dialog. */
        fun start(context: Context, resultCode: Int, consent: Intent, speaker: SonosSpeaker) {
            val intent = Intent(context, RelayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_CONSENT, consent)
                .putSpeaker(speaker)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }

        /** Moves a live session to another speaker; no new consent needed. */
        fun switchTo(context: Context, speaker: SonosSpeaker) =
            send(context, ACTION_SWITCH) { putSpeaker(speaker) }

        /** On-screen volume keys: +1 / -1 key presses. */
        fun nudgeVolume(context: Context, presses: Int) =
            send(context, ACTION_VOLUME) { putExtra(EXTRA_STEPS, presses) }

        fun setWifiLock(context: Context, enabled: Boolean) {
            Prefs.setWifiLock(context, enabled)
            if (RelayState.isActive) send(context, ACTION_WIFI_LOCK) { putExtra(EXTRA_ENABLED, enabled) }
        }

        private fun send(context: Context, action: String, extras: Intent.() -> Unit) {
            val intent = Intent(context, RelayService::class.java).setAction(action).apply(extras)
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                // Background-start restriction; only possible once the UI is gone.
            }
        }

        private fun Intent.putSpeaker(s: SonosSpeaker): Intent =
            putExtra(EXTRA_NAME, s.name).putExtra(EXTRA_IP, s.ip).putExtra(EXTRA_UUID, s.uuid)

        private fun Intent.getSpeaker(): SonosSpeaker? {
            val name = getStringExtra(EXTRA_NAME) ?: return null
            val ip = getStringExtra(EXTRA_IP) ?: return null
            return SonosSpeaker(name, ip, getStringExtra(EXTRA_UUID) ?: "")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var phoneVolume: PhoneVolume

    // Session parts; all null between sessions.
    private var projection: MediaProjection? = null
    private var server: StreamServer? = null
    private var capture: CapturePipeline? = null
    private var volumeKeys: SonosVolumeKeys? = null
    private var watchdog: ReconnectWatchdog? = null
    private var streamUrl: String? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    /** Read by background threads (watchdog, volume callbacks). */
    @Volatile private var speaker: SonosSpeaker? = null

    /** False once another source took the speaker over: don't Stop it on exit. */
    private var stopSpeakerOnExit = true

    /** The user or the system revoked capture (e.g. via the status-bar chip). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        phoneVolume = PhoneVolume(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Relay streaming", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> stopSelf()
            ACTION_SWITCH -> intent.getSpeaker()?.let { switchSpeaker(it) }
            ACTION_VOLUME -> volumeKeys?.nudge(intent.getIntExtra(EXTRA_STEPS, 0) * SonosVolumeKeys.STEP)
            ACTION_WIFI_LOCK -> setWifiLocks(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        // A command that arrives with no session running must not leave an
        // idle service behind.
        if (speaker == null && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }

    // ---- Session lifecycle ----

    private fun startSession(intent: Intent) {
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        val target = intent.getSpeaker()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        // startForegroundService() requires startForeground() on every path,
        // and Android 14+ requires it before getMediaProjection().
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(target?.name ?: "Sonos"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (_: Exception) {
            fail("CAPTURE NOT PERMITTED")
            return
        }
        if (consent == null || target == null) {
            stopSelf()
            return
        }

        endSession() // a fresh consent replaces any running session

        val proj = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent)
        } catch (_: Exception) {
            null
        }
        if (proj == null) {
            fail("CAPTURE CONSENT EXPIRED / TRY AGAIN")
            return
        }
        proj.registerCallback(projectionCallback, main)
        projection = proj

        try {
            runSession(proj, target)
        } catch (e: Exception) {
            endSession()
            fail("START FAILED / " + (e.message ?: e.javaClass.simpleName).uppercase())
        }
    }

    private fun runSession(proj: MediaProjection, target: SonosSpeaker) {
        val lan = Net.lan(this) ?: throw IllegalStateException("not on wi-fi")
        RelayState.showMessage(null)
        RelayState.txBytes = 0
        stopSpeakerOnExit = true

        val token = randomToken()
        val url = "http://${lan.address.hostAddress}:$PORT/stream.aac?t=$token"
        streamUrl = url

        val srv = StreamServer(PORT, token).also { server = it }
        srv.start()

        CapturePipeline(proj, srv, onFailure = {
            main.post { fail("AUDIO CAPTURE STOPPED") }
        }).also { capture = it }.start()

        speaker = target
        phoneVolume.mute()
        acquireLocks()
        volumeKeys = SonosVolumeKeys(this) { speaker?.ip }.also { it.refresh() }
        Prefs.saveLastSpeaker(this, target)
        RelayState.setSession(Session(target, System.currentTimeMillis()))
        play(target)

        watchdog = ReconnectWatchdog(
            server = srv,
            speaker = { speaker },
            streamUrl = url,
            token = token,
            title = STREAM_TITLE,
            onTick = { wakeLock?.acquire(WAKE_TIMEOUT_MS) }, // rolling timeout
            onTakenOver = { taken -> main.post { handOver(taken) } }
        ).also { it.start() }
    }

    /** Idempotent: used by onDestroy, failed starts and session replacement. */
    private fun endSession() {
        val target = speaker
        speaker = null

        watchdog?.stop()
        watchdog = null
        capture?.stop()
        capture = null
        server?.stop()
        server = null
        volumeKeys?.release()
        volumeKeys = null

        setWifiLocks(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        if (target != null && stopSpeakerOnExit) SonosControl.stop(target.ip)
        stopSpeakerOnExit = true
        phoneVolume.restore()

        projection?.let { p ->
            // Unregister first, so stopping our own projection can't trigger
            // stopSelf() for a session that replaces this one.
            try { p.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { p.stop() } catch (_: Exception) {}
        }
        projection = null
        streamUrl = null

        RelayState.setSession(null)
        RelayState.txBytes = 0
    }

    private fun switchSpeaker(target: SonosSpeaker) {
        val old = speaker ?: return
        if (old.ip == target.ip) return
        speaker = target
        SonosControl.stop(old.ip)
        play(target)
        Prefs.saveLastSpeaker(this, target)
        val startedAt = RelayState.session.value?.startedAt ?: System.currentTimeMillis()
        RelayState.setSession(Session(target, startedAt))
        volumeKeys?.refresh()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(target.name))
    }

    /** Another source took over the speaker in the Sonos app: let it have it. */
    private fun handOver(target: SonosSpeaker) {
        if (speaker != target) return
        stopSpeakerOnExit = false
        fail("${target.name.uppercase()} IS PLAYING ANOTHER SOURCE / RELAY STOPPED")
    }

    private fun play(target: SonosSpeaker) {
        val url = streamUrl ?: return
        SonosControl.playStream(target.ip, url, STREAM_TITLE) { ok ->
            if (!ok) main.post {
                if (speaker == target) RelayState.showMessage("${target.name.uppercase()} REFUSED THE STREAM")
            }
        }
    }

    /** Shows [message] in the UI and ends the service. */
    private fun fail(message: String) {
        RelayState.showMessage(message)
        stopSelf()
    }

    private fun randomToken(): String =
        ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- Power locks ----

    private fun acquireLocks() {
        // Non-reference-counted with a timeout the watchdog keeps extending,
        // so it can never outlive the session by more than 10 minutes.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:stream")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        setWifiLocks(Prefs.wifiLock(this))
    }

    /**
     * Optional Wi-Fi locks, off by default. LOW_LATENCY helps with the screen
     * on; HIGH_PERF keeps the radio out of power save with the screen off.
     */
    private fun setWifiLocks(enabled: Boolean) {
        for (lock in wifiLocks) {
            try { if (lock.isHeld) lock.release() } catch (_: Exception) {}
        }
        wifiLocks.clear()
        if (!enabled || speaker == null) return

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        for (mode in modes) {
            try {
                wifiLocks += wifi.createWifiLock(mode, "relay:wifi:$mode").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- Notification ----

    private fun buildNotification(speakerName: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentTitle("Relaying to $speakerName")
            .setContentText("Phone muted. Sound plays on Sonos.")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_relay), "Stop", stop
                ).build()
            )
            .build()
    }
}
