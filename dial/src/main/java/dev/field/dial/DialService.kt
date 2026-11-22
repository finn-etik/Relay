package dev.field.dial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Plays one stream. A foreground service of type "mediaPlayback", so the
 * radio keeps playing with the screen off or another app in front.
 *
 * The player is AndroidX Media3's ExoPlayer. Android's built-in MediaPlayer
 * was measured against real streams (on an emulator, Android 14): it never
 * finishes preparing Sveriges Radio's AAC streams and rejects FLAC, while
 * ExoPlayer plays every one of them and also reads the programme title the
 * stream carries. ExoPlayer handles audio focus (silent for a call or a
 * navigation prompt, then back), unplugged headphones, and keeping the CPU
 * and Wi-Fi awake while playing.
 *
 * The service publishes a media session, which puts transport controls on
 * the lock screen and in the notification shade and routes headset and
 * Bluetooth buttons here: play / pause, stop, next and previous station.
 * A radio stream cannot be paused in place, so PAUSE drops the connection
 * and PLAY reconnects to the same station; the paused service stays for
 * [IDLE_MS] so a headset button can bring it back, then goes away.
 *
 * Everything runs on the main thread (commands, session and player
 * callbacks, timers) except downloading a playlist link, which runs on
 * [io] and posts its result back.
 */
@OptIn(UnstableApi::class)
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
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var session: MediaSession
    private lateinit var player: ExoPlayer

    /** The station PLAY returns to after a pause. */
    private var lastId = -1
    private var lastName = ""
    private var paused = false

    /** Counts connects, so a playlist download that finishes late is not played over a newer station. */
    private var generation = 0
    /** A link that turned out not to be audio is tried once as a playlist. */
    private var triedAsPlaylist = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            update { it.onAir(now()) }
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A live stream has no end: reaching one means the server closed it.
            if (playbackState == Player.STATE_ENDED) dropped(null)
        }

        override fun onPlayerError(error: PlaybackException) = dropped(error)

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val title = mediaMetadata.title?.toString().orEmpty()
            if (title.trim() == state().nowPlaying) return
            update { it.title(title) }
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // ExoPlayer stopped itself: another app took the audio for good, or the headphones came out.
            // Either way DIAL pauses (PLAY brings it back) rather than holding a silent connection.
            val yielded = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            if (!playWhenReady && yielded) pausePlayback()
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

        // Old broadcaster links hop from http to https; the default refuses that.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("DIAL/1.2 (Android web radio player)")
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)
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
            else -> if (state().phase == Phase.OFF && !paused) shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        generation++
        io.shutdownNow()
        player.removeListener(playerListener)
        player.release()
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
        player.stop()
        generation++
        triedAsPlaylist = false
        paused = false
        lastId = station.id
        lastName = station.name
        update { if (retry) it.reconnect(now()) else it.tune(station, now()) }
        session.isActive = true
        if (Playlist.looksLikePlaylist(station.url)) openPlaylist(station.url) else open(station.url, hls = false)
        publish()
    }

    /** Hands a stream address to the player. */
    private fun open(url: String, hls: Boolean) {
        val item = MediaItem.Builder().setUri(url)
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
        applyControls()
    }

    /** Downloads a .pls / .m3u link off the main thread and plays the stream it names. */
    private fun openPlaylist(url: String) {
        triedAsPlaylist = true
        val wanted = generation
        io.execute {
            val text = PlaylistFetch.text(url)
            handler.post {
                if (wanted != generation) return@post // a newer station was tuned meanwhile
                val stream = text?.let { Playlist.firstStream(it) }
                when {
                    text != null && Playlist.isHls(text) -> open(url, hls = true)
                    stream != null -> open(stream, hls = false)
                    else -> dropped(null)
                }
            }
        }
    }

    /** The stream failed or ended. */
    private fun dropped(error: PlaybackException?) {
        val s = state()
        if (s.phase == Phase.OFF) return
        player.stop()
        // Not audio at all? It may be a playlist without the usual extension: look inside once.
        val notAudio = error?.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        val station = s.playing
        if (notAudio && !triedAsPlaylist && station != null) {
            openPlaylist(station.url)
            return
        }
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
        generation++
        player.stop()
        update { it.stop(now()) }
        paused = true
        publish()
        handler.removeCallbacks(idleTask)
        handler.postDelayed(idleTask, IDLE_MS)
    }

    private fun stopPlayback() {
        generation++
        player.stop()
        update { it.stop(now()) }
        shutDown()
    }

    private fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        generation++
        paused = false
        player.stop()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Volume and mute onto the player; (re)arms the sleep timer. */
    private fun applyControls() {
        val s = state()
        player.volume = s.volumeFraction
        handler.removeCallbacks(sleepTask)
        if (s.sleepAt > 0L) handler.postDelayed(sleepTask, maxOf(0L, s.sleepAt - now()))
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

    /** Second line everywhere: the programme or track if the stream names one, else the status. */
    private fun subtitle(): String = state().nowPlaying.ifEmpty { statusText() }

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
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, stationName())
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, subtitle())
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, -1L) // no seek bar
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
            .setContentText(subtitle())
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
