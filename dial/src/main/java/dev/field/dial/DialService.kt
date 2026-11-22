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
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
 * It publishes a media session, which is what puts transport controls on
 * the lock screen and in the notification shade and routes headset and
 * Bluetooth buttons here: play / pause, stop, next and previous station.
 * A radio stream cannot be paused in place, so PAUSE drops the connection
 * and PLAY reconnects to the same station; the paused service stays for
 * [IDLE_MS] so a headset button can bring it back, then goes away.
 *
 * It also does what a well-behaved player owes the phone: asks for audio
 * focus (pauses for another player, goes silent for a call or a navigation
 * prompt and comes back), pauses when headphones are unplugged, and keeps
 * the CPU and Wi-Fi awake only while a stream is wanted.
 *
 * Everything runs on the main thread: commands, session callbacks,
 * MediaPlayer callbacks (delivered to the looper the player was created
 * on) and the timers.
 */
class DialService : Service() {

    companion object {
        private const val CHANNEL = "dial_play"
        private const val NOTIF_ID = 3
        private const val ACTION_TUNE = "dev.field.dial.TUNE"
        private const val ACTION_PLAY = "dev.field.dial.PLAY"
        private const val ACTION_PAUSE = "dev.field.dial.PAUSE"
        private const val ACTION_NEXT = "dev.field.dial.NEXT"
        private const val ACTION_PREV = "dev.field.dial.PREV"
        private const val ACTION_STOP = "dev.field.dial.STOP"
        private const val EXTRA_ID = "id"

        /** How long a paused service waits for PLAY before it shuts down. */
        private const val IDLE_MS = 10 * 60_000L

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
    private val idleTask = Runnable { stopPlayback() }

    private lateinit var session: MediaSession
    private var player: MediaPlayer? = null
    /** The station PLAY returns to after a pause. */
    private var lastId = -1
    private var lastName = ""
    private var paused = false

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
            AudioManager.AUDIOFOCUS_LOSS -> pausePlayback() // another player took over; PLAY brings DIAL back
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { silenced = true; applyControls() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyControls() }
            AudioManager.AUDIOFOCUS_GAIN -> { silenced = false; ducked = false; applyControls() }
        }
    }

    /** Headphones unplugged or Bluetooth gone: do not carry on through the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    /** Lock screen, notification shade, headset and Bluetooth buttons all arrive here. */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pausePlayback()
        override fun onStop() = stopPlayback()
        override fun onSkipToNext() = skip(1)
        override fun onSkipToPrevious() = skip(-1)
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
        session = MediaSession(this, "DIAL")
        session.setCallback(sessionCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TUNE -> {
                val station = state().stations.firstOrNull { it.id == intent.getIntExtra(EXTRA_ID, -1) }
                // Started as a foreground service: it must show its notification even if it then stops.
                if (!goForeground()) return START_NOT_STICKY
                if (station == null) stopPlayback() else connect(station, retry = false)
            }
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopPlayback()
            else -> if (player == null && !paused) shutDown()
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
        session.isActive = false
        session.release()
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
        handler.removeCallbacks(idleTask)
        releasePlayer()
        paused = false
        lastId = station.id
        lastName = station.name
        update { if (retry) it.reconnect(now()) else it.tune(station, now()) }
        if (!acquireSystem()) {
            // Audio focus refused (a call is active): do not talk over it.
            update { it.dropped(now()) }
            shutDown()
            return
        }
        session.isActive = true
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
                    publish()
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
        publish()
    }

    /** The stream failed or ended. Callbacks from a player that was already replaced are ignored. */
    private fun dropped(mp: MediaPlayer) {
        if (player !== mp) return
        releasePlayer()
        update { it.dropped(now()) }
        if (state().retry) {
            publish()
            handler.postDelayed(retryTask, Dial.retryDelayMs(state().attempts))
        } else {
            shutDown() // stays shown as ERR; TUNE starts over
        }
    }

    private fun reconnect() {
        val station = state().playing
        if (station == null || !state().retry) shutDown() else connect(station, retry = true)
    }

    /** PLAY from the session or the notification: back to the paused station, else the selected one. */
    private fun play() {
        val s = state()
        if (s.phase == Phase.ON || s.phase == Phase.CONNECT) return
        val station = s.stations.firstOrNull { it.id == lastId } ?: s.selected ?: s.stations.firstOrNull() ?: return
        if (goForeground()) connect(station, retry = false)
    }

    /** NEXT / PREVIOUS: the neighbouring station in the saved list, wrapping round. */
    private fun skip(by: Int) {
        val s = state()
        val from = s.playing?.id ?: if (lastId != -1) lastId else s.selId
        val station = s.neighbour(from, by) ?: return
        if (goForeground()) connect(station, retry = false)
    }

    /**
     * A live stream cannot be held: pausing lets the connection go and keeps
     * only the session and the notification, so PLAY (headset, lock screen)
     * can reconnect. Shuts down by itself after [IDLE_MS].
     */
    private fun pausePlayback() {
        if (paused) return
        if (state().phase == Phase.OFF) {
            shutDown()
            return
        }
        handler.removeCallbacks(retryTask)
        releasePlayer()
        update { it.stop(now()) }
        releaseSystem()
        paused = true
        publish()
        handler.removeCallbacks(idleTask)
        handler.postDelayed(idleTask, IDLE_MS)
    }

    private fun stopPlayback() {
        releasePlayer()
        update { it.stop(now()) }
        shutDown()
    }

    private fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        paused = false
        releaseSystem()
        session.isActive = false
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

    // ---- media session and notification ----

    private fun statusText(): String = when {
        paused -> "Paused"
        else -> when (state().phase) {
            Phase.ON -> "On air"
            Phase.CONNECT -> "Connecting"
            Phase.ERR -> "Stream down, reconnecting"
            Phase.OFF -> "Stopped"
        }
    }

    private fun stationName(): String = state().playing?.name ?: lastName.ifEmpty { "DIAL" }

    /** Pushes the current state to the session (lock screen, headset routing) and the notification. */
    private fun publish() {
        val phase = state().phase
        val playbackState = when {
            paused -> PlaybackState.STATE_PAUSED
            phase == Phase.ON -> PlaybackState.STATE_PLAYING
            phase == Phase.CONNECT || phase == Phase.ERR -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                // A live stream has no position.
                .setState(
                    playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f,
                )
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, stationName())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, statusText())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L) // no seek bar
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, requestCode, Intent(this, DialService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, intent).build()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val playPause =
            if (paused) action(R.drawable.ic_play, "Play", ACTION_PLAY, 2)
            else action(R.drawable.ic_pause, "Pause", ACTION_PAUSE, 3)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(stationName())
            .setContentText(statusText())
            .setOngoing(!paused)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // station name and controls on the lock screen
            .setContentIntent(open)
            .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV, 4))
            .addAction(playPause)
            .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT, 5))
            .addAction(action(R.drawable.ic_stop, "Stop", ACTION_STOP, 1))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
