package dev.relay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Foreground service: captures playback audio, mutes the phone, encodes
 * AAC, serves it over HTTP and tells the Sonos to pull it.
 *
 * Threads: main (lifecycle, MediaSession), relay-capture (read/encode/
 * enqueue, never touches the network), relay-watchdog (reconnects), the
 * StreamServer's per-listener writers, and SonosControl's serial queue.
 */
class RelayService : Service() {

    companion object {
        const val PORT = 8079
        const val KEY_WIFI_LOCK = "wifi_lock"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BIT_RATE = 256_000
        private const val PCM_CHUNK = 8192 // ~46 ms of 16-bit stereo
        private const val VOLUME_STEP = 4
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val STREAM_TITLE = "Relay"

        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIF_ID = 11
        private const val PREFS = "relay"
        private const val KEY_SAVED_VOLUME = "saved_phone_volume"

        private const val ACTION_START = "dev.relay.START"
        private const val ACTION_STOP = "dev.relay.STOP"
        private const val ACTION_SWITCH = "dev.relay.SWITCH"
        private const val ACTION_VOLUME = "dev.relay.VOLUME"
        private const val ACTION_WIFI_LOCK = "dev.relay.WIFI_LOCK"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_STEPS = "steps"
        private const val EXTRA_ENABLED = "enabled"

        fun start(context: Context, resultCode: Int, data: Intent, speaker: SonosSpeaker) {
            val intent = Intent(context, RelayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putSpeaker(speaker)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Re-point a live session at another speaker. No new consent needed. */
        fun switchTo(context: Context, speaker: SonosSpeaker) =
            command(context, Intent(context, RelayService::class.java).setAction(ACTION_SWITCH).putSpeaker(speaker))

        fun nudgeVolume(context: Context, steps: Int) =
            command(context, Intent(context, RelayService::class.java).setAction(ACTION_VOLUME).putExtra(EXTRA_STEPS, steps))

        fun setWifiLock(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_WIFI_LOCK, enabled).apply()
            if (RelayState.isActive) {
                command(
                    context,
                    Intent(context, RelayService::class.java).setAction(ACTION_WIFI_LOCK).putExtra(EXTRA_ENABLED, enabled)
                )
            }
        }

        fun wifiLockEnabled(context: Context) = prefs(context).getBoolean(KEY_WIFI_LOCK, false)

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }

        /**
         * If the process died mid-session (crash, force-stop, OOM) the phone
         * would stay muted forever. Called on app start: puts the volume back.
         */
        fun recoverPhoneVolume(context: Context) {
            if (RelayState.isActive) return
            val prefs = prefs(context)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            if (saved < 0) return
            val am = context.getSystemService(AudioManager::class.java)
            try {
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                }
            } catch (e: SecurityException) {
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        }

        private fun command(context: Context, intent: Intent) {
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Background-start restriction: only possible if the UI is gone.
            }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun Intent.putSpeaker(s: SonosSpeaker): Intent =
            putExtra(EXTRA_NAME, s.name).putExtra(EXTRA_IP, s.ip).putExtra(EXTRA_UUID, s.uuid)

        private fun Intent.speaker(): SonosSpeaker? {
            val name = getStringExtra(EXTRA_NAME) ?: return null
            val ip = getStringExtra(EXTRA_IP) ?: return null
            return SonosSpeaker(name, ip, getStringExtra(EXTRA_UUID) ?: "")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        // The user or system revoked capture (e.g. the status-bar chip).
        override fun onStop() {
            stopSelf()
        }
    }

    private var record: AudioRecord? = null
    private var encoder: AacEncoder? = null
    private var server: StreamServer? = null
    private var captureThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var phoneMuted = false

    @Volatile private var capturing = false
    @Volatile private var speaker: SonosSpeaker? = null
    @Volatile private var streamUrl: String? = null
    @Volatile private var token: String? = null

    /** False when another source took the speaker over: don't Stop it on exit. */
    @Volatile private var stopSpeakerOnExit = true

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Relay streaming", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
            ACTION_SWITCH -> intent.speaker()?.let { switchSpeaker(it) }
            ACTION_VOLUME -> adjust(intent.getIntExtra(EXTRA_STEPS, 0) * VOLUME_STEP)
            ACTION_WIFI_LOCK -> applyWifiLock(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        // A command that arrives with no live session must not leave an
        // idle, non-foreground service behind.
        if (!capturing && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---- Session lifecycle ----

    private fun handleStart(intent: Intent) {
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val target = intent.speaker()
        val code = intent.getIntExtra(EXTRA_CODE, 0)

        // startForegroundService() obliges startForeground() on every path,
        // and Android 14+ requires it before getMediaProjection().
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(target?.name ?: "Sonos"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            RelayState.say("CAPTURE NOT PERMITTED")
            stopSelf()
            return
        }
        if (data == null || target == null) {
            stopSelf()
            return
        }

        if (capturing || projection != null) teardown() // fresh consent replaces the session

        val proj = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            RelayState.say("CAPTURE CONSENT EXPIRED / TRY AGAIN")
            stopSelf()
            return
        }
        proj.registerCallback(projectionCallback, main)
        projection = proj

        try {
            begin(proj, target)
        } catch (e: Exception) {
            RelayState.say("START FAILED / " + (e.message ?: e.javaClass.simpleName).uppercase())
            teardown()
            stopSelf()
        }
    }

    private fun begin(proj: MediaProjection, target: SonosSpeaker) {
        val lan = Net.lan(this) ?: throw IllegalStateException("not on wi-fi")
        RelayState.say(null)
        RelayState.txBytes = 0
        stopSpeakerOnExit = true

        val secret = ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        token = secret
        val srv = StreamServer(PORT, secret)
        server = srv
        srv.start()
        streamUrl = "http://${lan.address.hostAddress}:$PORT/stream.aac?t=$secret"

        val aac = AacEncoder(SAMPLE_RATE, CHANNELS, BIT_RATE)
        encoder = aac
        aac.start()

        val rec = buildRecorder(proj)
        record = rec
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("capture refused")
        }

        speaker = target
        capturing = true
        mutePhone()
        acquireLocks()
        startCapture(rec, aac, srv)
        startMediaSession(target)
        SonosDiscovery.saveLast(this, target)
        RelayState.setSession(Session(target, System.currentTimeMillis()))
        play(target)
        startWatchdog(srv)
    }

    /** Idempotent: safe from onDestroy, failed starts and session replacement. */
    private fun teardown() {
        capturing = false
        val target = speaker
        speaker = null

        watchdogThread?.interrupt()
        watchdogThread = null

        // Stop capture first and wait for the capture thread to leave the
        // encoder, so the codec is never released under its feet.
        record?.let { try { it.stop() } catch (e: Exception) { } }
        captureThread?.let { try { it.join(1500) } catch (e: InterruptedException) { } }
        captureThread = null
        record?.let { try { it.release() } catch (e: Exception) { } }
        record = null

        encoder?.stop()
        encoder = null
        server?.stop()
        server = null

        mediaSession?.let {
            try {
                it.isActive = false
                it.release()
            } catch (e: Exception) {
            }
        }
        mediaSession = null
        volumeProvider = null

        releaseWifiLocks()
        if (target != null && stopSpeakerOnExit) SonosControl.stop(target.ip)
        stopSpeakerOnExit = true

        restorePhone()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        projection?.let { p ->
            // Unregister first so stopping our own projection can't call
            // stopSelf() on a session that replaced this one.
            try { p.unregisterCallback(projectionCallback) } catch (e: Exception) { }
            try { p.stop() } catch (e: Exception) { }
        }
        projection = null

        streamUrl = null
        token = null
        RelayState.setSession(null)
        RelayState.volume.value = -1
        RelayState.txBytes = 0
    }

    private fun switchSpeaker(target: SonosSpeaker) {
        val old = speaker ?: return
        if (!capturing || old.ip == target.ip) return
        speaker = target
        SonosControl.stop(old.ip)
        play(target)
        SonosDiscovery.saveLast(this, target)
        val started = RelayState.session.value?.startedAt ?: System.currentTimeMillis()
        RelayState.setSession(Session(target, started))
        RelayState.volume.value = -1
        refreshVolume(target)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(target.name))
    }

    private fun play(target: SonosSpeaker) {
        val url = streamUrl ?: return
        SonosControl.playStream(target.ip, url, STREAM_TITLE) { ok ->
            if (!ok) {
                main.post {
                    if (capturing && speaker == target) {
                        RelayState.say("${target.name.uppercase()} REFUSED THE STREAM")
                    }
                }
            }
        }
    }

    // ---- Audio ----

    @SuppressLint("MissingPermission") // Checked right here; the UI requests it first.
    private fun buildRecorder(proj: MediaProjection): AudioRecord {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("microphone permission missing")
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, PCM_CHUNK * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("capture unavailable")
        }
        return rec
    }

    private fun startCapture(rec: AudioRecord, aac: AacEncoder, srv: StreamServer) {
        captureThread = thread(name = "relay-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcm = ByteArray(PCM_CHUNK)
            try {
                while (capturing) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n < 0) break
                    // Encode only while a speaker is listening.
                    if (n == 0 || !srv.hasClients()) continue
                    val chunk = aac.encode(pcm, n)
                    if (chunk.isNotEmpty()) {
                        srv.broadcast(chunk)
                        RelayState.txBytes += chunk.size
                    }
                }
            } catch (t: Throwable) {
                // Codec or recorder failed; handled below.
            }
            if (capturing) {
                main.post {
                    RelayState.say("AUDIO CAPTURE STOPPED")
                    stopSelf()
                }
            }
        }
    }

    private fun mutePhone() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // commit(), not apply(): this must survive a crash a moment later.
        prefs(this).edit().putInt(KEY_SAVED_VOLUME, current).commit()
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            phoneMuted = true
        } catch (e: SecurityException) {
            prefs(this).edit().remove(KEY_SAVED_VOLUME).apply()
        }
    }

    private fun restorePhone() {
        if (!phoneMuted) return
        phoneMuted = false
        val saved = prefs(this).getInt(KEY_SAVED_VOLUME, -1)
        if (saved >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            } catch (e: SecurityException) {
            }
        }
        prefs(this).edit().remove(KEY_SAVED_VOLUME).apply()
    }

    // ---- Volume keys -> Sonos ----

    private fun startMediaSession(target: SonosSpeaker) {
        val provider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 30) {
            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    AudioManager.ADJUST_RAISE -> adjust(VOLUME_STEP)
                    AudioManager.ADJUST_LOWER -> adjust(-VOLUME_STEP)
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                val ip = speaker?.ip ?: return
                val v = volume.coerceIn(0, 100)
                setCurrentVolume(v)
                RelayState.volume.value = v
                SonosControl.setVolume(ip, v)
            }
        }
        volumeProvider = provider
        mediaSession = MediaSession(this, "Relay").apply {
            setPlaybackToRemote(provider)
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            isActive = true
        }
        refreshVolume(target)
    }

    private fun refreshVolume(target: SonosSpeaker) {
        SonosControl.getVolume(target.ip) { v -> main.post { showVolume(target.ip, v) } }
    }

    private fun showVolume(ip: String, v: Int) {
        if (!capturing || speaker?.ip != ip) return
        volumeProvider?.setCurrentVolume(v.coerceIn(0, 100))
        RelayState.volume.value = v
    }

    /** Uses SetRelativeVolume, then shows the speaker's real resulting level, so
     *  changes made in the Sonos app never cause a jump. */
    private fun adjust(delta: Int) {
        if (delta == 0) return
        val ip = speaker?.ip ?: return
        volumeProvider?.let { p ->
            val optimistic = (p.currentVolume + delta).coerceIn(0, 100)
            p.setCurrentVolume(optimistic)
            RelayState.volume.value = optimistic
        }
        SonosControl.adjustVolume(ip, delta) { actual -> main.post { showVolume(ip, actual) } }
    }

    // ---- Reconnect watchdog ----

    /**
     * Every 5 s: if no speaker has pulled the stream for 12 s, ask the
     * speaker what it is doing. Unreachable: wait (rebooting, asleep).
     * Holding our stream or nothing, and not paused by the user: re-issue
     * Play. Playing something else: the user picked another source in the
     * Sonos app, so hand the speaker over and end the session instead of
     * fighting them for it.
     */
    private fun startWatchdog(srv: StreamServer) {
        watchdogThread = thread(name = "relay-watchdog", isDaemon = true) {
            var missingSince = 0L
            try {
                while (capturing) {
                    Thread.sleep(5000)
                    wakeLock?.acquire(WAKE_TIMEOUT_MS) // rolling timeout, never leaks
                    if (srv.hasClients()) {
                        missingSince = 0L
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (missingSince == 0L) {
                        missingSince = now
                        continue
                    }
                    if (now - missingSince < 12_000) continue
                    missingSince = now

                    val target = speaker ?: break
                    val url = streamUrl ?: break
                    val secret = token ?: break
                    val uri = SonosControl.currentUri(target.ip) ?: continue
                    if (!capturing || speaker != target) continue
                    if (uri.isNotEmpty() && !uri.contains(secret)) {
                        main.post {
                            if (capturing && speaker == target) {
                                stopSpeakerOnExit = false
                                RelayState.say("${target.name.uppercase()} IS PLAYING ANOTHER SOURCE / RELAY STOPPED")
                                stopSelf()
                            }
                        }
                        break
                    }
                    if (SonosControl.transportState(target.ip) != "PAUSED_PLAYBACK") {
                        SonosControl.playStreamBlocking(target.ip, url, STREAM_TITLE)
                    }
                }
            } catch (e: InterruptedException) {
                // Session ended.
            }
        }
    }

    // ---- Locks ----

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:stream")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        applyWifiLock(wifiLockEnabled(this))
    }

    private fun applyWifiLock(enabled: Boolean) {
        releaseWifiLocks()
        if (!enabled || !capturing) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // LOW_LATENCY covers screen-on use; HIGH_PERF keeps the radio out of
        // power save with the screen off, which is how Relay mostly runs.
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        for (mode in modes) {
            try {
                wifiLocks += wifi.createWifiLock(mode, "relay:wifi:$mode").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun releaseWifiLocks() {
        for (lock in wifiLocks) {
            try { if (lock.isHeld) lock.release() } catch (e: Exception) { }
        }
        wifiLocks.clear()
    }

    // ---- Notification ----

    private fun buildNotification(name: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentTitle("Relaying to $name")
            .setContentText("Phone muted. Sound plays on Sonos.")
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_relay), "Stop", stopIntent
                ).build()
            )
            .build()
    }
}
