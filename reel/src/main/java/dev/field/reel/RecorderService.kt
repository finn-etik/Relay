package dev.field.reel

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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.concurrent.thread

/**
 * Records one take. A foreground service of type "microphone", so recording
 * keeps going with the screen off or another app in front (the original's
 * in-activity MediaRecorder went silent in the background).
 *
 * One AudioRecord feeds both the .m4a file and, when LIVE TXT is armed,
 * the transcriber. The original ran MediaRecorder and SpeechRecognizer on
 * the microphone at the same time, which most phones don't allow.
 */
class RecorderService : Service() {

    companion object {
        private const val CHANNEL = "reel_rec"
        private const val NOTIF_ID = 7
        private const val ACTION_START = "dev.field.reel.START"
        private const val ACTION_TOGGLE_HOLD = "dev.field.reel.HOLD"
        private const val ACTION_STOP = "dev.field.reel.STOP"
        private const val EXTRA_LIVE = "live"
        private const val CHUNK = 4800 // 100 ms at 48 kHz

        fun start(context: Context, live: Boolean) = ContextCompat.startForegroundService(
            context, Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_LIVE, live)
        )

        fun toggleHold(context: Context) = send(context, ACTION_TOGGLE_HOLD)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            if (ReelStore.rec.value.phase == RecPhase.STANDBY) return
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }
    }

    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReelStore.load(applicationContext)
        Transcriber.init(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getBooleanExtra(EXTRA_LIVE, false))
            ACTION_TOGGLE_HOLD -> {
                paused = !paused
                ReelStore.rec.update { it.copy(phase = if (paused) RecPhase.HOLD else RecPhase.REC) }
                notifyState()
            }
            ACTION_STOP -> stopRequested = true
        }
        if (worker == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(live: Boolean) {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: Exception) {
            ReelStore.rec.update { RecState(error = "MIC NOT ALLOWED IN BACKGROUND") }
            stopSelf()
            return
        }
        if (worker != null) return // already recording
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ReelStore.rec.update { RecState(error = "NO MIC") }
            stopSelf()
            return
        }
        stopRequested = false
        paused = false
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reel:rec")
            .apply { acquire(4 * 60 * 60 * 1000L) } // a 4 h cap, never leaked
        val (id, file) = ReelStore.newTakeFile()
        ReelStore.rec.value = RecState(RecPhase.REC, takeId = id, live = live)
        notifyState()
        if (live) Transcriber.beginLive(id)
        worker = thread(name = "reel-rec") { record(id, file, live) }
    }

    @SuppressLint("MissingPermission") // checked in begin()
    private fun record(id: Int, file: File, live: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec: AudioRecord? = null
        var writer: M4aWriter? = null
        var error: String? = null
        try {
            val min = AudioRecord.getMinBufferSize(Audio.RECORD_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, Audio.RECORD_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CHUNK * 4),
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "NO MIC" }
            writer = M4aWriter(file)
            rec.startRecording()
            val buf = ShortArray(CHUNK)
            while (!stopRequested) {
                if (paused) {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    Thread.sleep(50)
                    continue
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec.startRecording()
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("MIC LOST")
                if (n == 0) continue
                writer.write(buf, n)
                if (live) Transcriber.feedLive(buf, n)
                val level = Audio.peak(buf, n)
                val ms = writer.durationMs
                ReelStore.rec.update { it.copy(recordedMs = ms, level = level) }
            }
        } catch (e: Exception) {
            error = e.message?.uppercase() ?: "REC FAILED"
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            val duration = writer?.durationMs ?: 0L
            try { writer?.finish() } catch (_: Exception) {}
            if (duration >= 1000 && file.length() > 0) {
                ReelStore.add(Take(id, file.name, duration, file.length(), System.currentTimeMillis()))
            } else {
                file.delete()
            }
            if (live) Transcriber.endLive()
            ReelStore.rec.value = RecState(error = error)
            wakeLock?.let { if (it.isHeld) it.release() }
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        fun action(label: String, action: String, code: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(this, code, Intent(this, RecorderService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (paused) "REEL / HOLD" else "REEL / REC")
            .setContentText("Take " + ReelStore.rec.value.takeId)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (paused) "RESUME" else "HOLD", ACTION_TOGGLE_HOLD, 1))
            .addAction(action("STOP", ACTION_STOP, 2))
            .build()
    }
}
