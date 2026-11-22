package dev.field.dial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Plays one stream. A foreground service of type "mediaPlayback", so the
 * radio keeps playing with the screen off or another app in front (in DIAL
 * 1.1 the player lived in the screen and Android was free to stop it).
 *
 * It also does what a well-behaved player owes the phone: asks for audio
 * focus (stops for another player, goes silent for a call or a navigation
 * prompt and comes back), stops when headphones are unplugged, keeps the
 * CPU and Wi-Fi awake only while a stream is wanted, and shows a
 * notification with STOP.
 *
 * Everything runs on the main thread: commands, MediaPlayer callbacks
 * (delivered to the looper the player was created on) and the timers.
 */
class DialService : Service() {

    companion object {
        private const val CHANNEL = "dial_play"
        private const val NOTIF_ID = 3
        private const val ACTION_TUNE = "dev.field.dial.TUNE"
        private const val ACTION_STOP = "dev.field.dial.STOP"
        private const val EXTRA_ID = "id"

        @Volatile private var running = false

        fun tune(context: Context, stationId: Int) = ContextCompat.startForegroundService(
            context, Intent(context, DialService::class.java).setAction(ACTION_TUNE).putExtra(EXTRA_ID, stationId)
        )

        /** Stops playback. If the service is not up (stream down, RETRY off), only the state needs clearing. */
        fun stop(context: Context) {
            if (running) context.startService(Intent(context, DialService::class.java).setAction(ACTION_STOP))
            else DialStore.update(context.applicationContext) { it.stop(System.currentTimeMillis()) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val retryTask = Runnable { reconnect() }
    private val sleepTask = Runnable { stopPlayback() }

    private var player: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var silenced = false // focus lost for a moment (call, prompt)
    private var ducked = false
    private var noisyRegistered = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback() // another player took over
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { silenced = true; applyControls() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyControls() }
            AudioManager.AUDIOFOCUS_GAIN -> { silenced = false; ducked = false; applyControls() }
        }
    }

    /** Headphones unplugged or Bluetooth gone: do not carry on through the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stopPlayback()
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun state() = DialStore.state.value
    private fun update(change: (DialState) -> DialState) = DialStore.update(applicationContext, change)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        DialStore.load(applicationContext)
        DialStore.onControls = { applyControls() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TUNE -> {
                val station = state().stations.firstOrNull { it.id == intent.getIntExtra(EXTRA_ID, -1) }
                // Started as a foreground service: it must show its notification even if it then stops.
                if (!goForeground()) return START_NOT_STICKY
                if (station == null) stopPlayback() else connect(station, retry = false)
            }
            ACTION_STOP -> stopPlayback()
            else -> if (player == null) shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        releaseSystem()
        // Killed while a stream was wanted: close the books so the clock does not run on.
        val phase = state().phase
        if (phase == Phase.ON || phase == Phase.CONNECT) update { it.stop(now()) }
        DialStore.onControls = null
        running = false
        super.onDestroy()
    }

    // ---- playback ----

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        true
    } catch (_: Exception) {
        // Not allowed to start from the background: nothing to play into.
        update { it.dropped(now()) }
        stopSelf()
        false
    }

    private fun connect(station: Station, retry: Boolean) {
        handler.removeCallbacks(retryTask)
        releasePlayer()
        update { if (retry) it.reconnect(now()) else it.tune(station, now()) }
        if (!acquireSystem()) {
            // Audio focus refused (a call is active): do not talk over it.
            update { it.dropped(now()) }
            shutDown()
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(station.url)
            mp.setOnPreparedListener { ready ->
                if (player === ready) {
                    applyControls()
                    ready.start()
                    update { it.onAir(now()) }
                    notifyState()
                }
            }
            mp.setOnErrorListener { failed, _, _ ->
                dropped(failed)
                true
            }
            // A live stream has no end: reaching one means the server closed it.
            mp.setOnCompletionListener { ended -> dropped(ended) }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            player = mp
            dropped(mp)
            return
        }
        notifyState()
    }

    /** The stream failed or ended. Callbacks from a player that was already replaced are ignored. */
    private fun dropped(mp: MediaPlayer) {
        if (player !== mp) return
        releasePlayer()
        update { it.dropped(now()) }
        if (state().retry) {
            notifyState()
            handler.postDelayed(retryTask, Dial.retryDelayMs(state().attempts))
        } else {
            shutDown() // stays shown as ERR; TUNE starts over
        }
    }

    private fun reconnect() {
        val station = state().playing
        if (station == null || !state().retry) shutDown() else connect(station, retry = true)
    }

    private fun stopPlayback() {
        releasePlayer()
        update { it.stop(now()) }
        shutDown()
    }

    private fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        releaseSystem()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    /** Volume, mute and focus state onto the player; (re)arms the sleep timer. */
    private fun applyControls() {
        val s = state()
        val v = when {
            silenced -> 0f
            ducked -> s.volumeFraction * 0.25f
            else -> s.volumeFraction
        }
        try { player?.setVolume(v, v) } catch (_: Exception) {}
        handler.removeCallbacks(sleepTask)
        if (s.sleepAt > 0L) handler.postDelayed(sleepTask, maxOf(0L, s.sleepAt - now()))
    }

    // ---- audio focus, noisy receiver, Wi-Fi lock ----

    /** False if audio focus was refused. */
    private fun acquireSystem(): Boolean {
        if (focusRequest == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            val result = getSystemService(AudioManager::class.java).requestAudioFocus(request)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            focusRequest = request
            silenced = false
            ducked = false
        }
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        if (wifiLock == null) {
            try {
                @Suppress("DEPRECATION") // the non-deprecated modes only hold while the app is on screen
                val lock = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dial:stream")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            } catch (_: Exception) {
                // No Wi-Fi lock: the stream may stutter with the screen off, but it plays.
            }
        }
        return true
    }

    private fun releaseSystem() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        if (noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
        wifiLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        wifiLock = null
    }

    // ---- notification ----

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val s = state()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DialService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("DIAL / " + (s.playing?.name ?: "STANDBY"))
            .setContentText(
                when (s.phase) {
                    Phase.ON -> "On air"
                    Phase.CONNECT -> "Connecting"
                    Phase.ERR -> "Stream down, reconnecting"
                    Phase.OFF -> "Stopped"
                }
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .build()
    }
}
