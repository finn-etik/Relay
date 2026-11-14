package dev.field.gate

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Holds GATE's state, persists every change, and keeps the system in step:
 * an exact alarm fires at the session end (so completion works with the app
 * closed), and an ongoing notification shows the countdown on the lock screen.
 */
object GateStore {

    private const val PREFS = "gate"
    private const val KEY = "state"
    private const val CHANNEL_RUN = "gate_run"
    private const val CHANNEL_DONE = "gate_done"
    private const val NOTIF_RUN = 1
    private const val NOTIF_DONE = 2
    const val ACTION_ALARM = "dev.field.gate.ALARM"
    const val ACTION_STOP = "dev.field.gate.STOP"

    private val _state = MutableStateFlow(GateState())
    val state: StateFlow<GateState> = _state.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _state.value = read(context)
        createChannels(context)
        // A session that ended while the process was dead completes now.
        update(context) { it.complete(System.currentTimeMillis()) }
    }

    /** Re-arms the alarm and countdown notification for a running session (after reboot). */
    @Synchronized
    fun resync(context: Context) {
        val s = _state.value
        if (s.phase == Phase.ON) sync(context, s, s)
    }

    /** Applies [change], persists, and syncs alarm + notifications to the result. */
    @Synchronized
    fun update(context: Context, change: (GateState) -> GateState) {
        val before = _state.value
        val after = change(before)
        if (after == before) return
        _state.value = after
        write(context, after)
        sync(context, before, after)
    }

    fun start(context: Context) = update(context) { it.start(System.currentTimeMillis()) }
    fun hold(context: Context) = update(context) { it.hold(System.currentTimeMillis()) }
    fun stop(context: Context) = update(context) { it.stop() }
    fun settle(context: Context) = update(context) { it.settle(System.currentTimeMillis()) }
    fun selectPreset(context: Context, min: Int) = update(context) { it.selectPreset(min) }
    fun toggleBeep(context: Context) = update(context) { it.copy(beep = !it.beep) }
    fun clearLog(context: Context) = update(context) { it.copy(log = emptyList()) }

    // ---- side effects ----

    private fun sync(context: Context, before: GateState, after: GateState) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (after.phase == Phase.ON) {
            scheduleAlarm(context, after.endAt)
            nm.notify(NOTIF_RUN, runningNotification(context, after))
        } else {
            cancelAlarm(context)
            nm.cancel(NOTIF_RUN)
        }
        if (before.phase == Phase.ON && after.phase == Phase.DONE) {
            nm.notify(NOTIF_DONE, doneNotification(context, after))
            // Only beep on time, not when the app reopens long after the end.
            val late = System.currentTimeMillis() - (after.log.firstOrNull()?.endedAt ?: 0L)
            if (after.beep && late < 60_000L) beep()
        }
        if (after.phase == Phase.ON) nm.cancel(NOTIF_DONE)
    }

    private fun scheduleAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // may be a few minutes late
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, GateReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Two short beeps on the alarm stream: audible even if media is muted. */
    private fun beep() {
        thread(name = "gate-beep", isDaemon = true) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_ALARM, 80)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Thread.sleep(250)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Thread.sleep(400)
                tg.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Session countdown", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Session complete", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the BEEP setting decides the sound
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun runningNotification(context: Context, s: GateState): Notification {
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, GateReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GATE / ${s.presetMin} MIN")
            .setContentText("Counting down")
            .setWhen(s.endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .build()
    }

    private fun doneNotification(context: Context, s: GateState): Notification =
        Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GATE / SESSION COMPLETE")
            .setContentText("${s.presetMin} min logged")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    // ---- persistence ----

    private fun write(context: Context, s: GateState) {
        val log = JSONArray()
        s.log.forEach { log.put(JSONObject().put("m", it.minutes).put("t", it.endedAt)) }
        val json = JSONObject()
            .put("phase", s.phase.name)
            .put("preset", s.presetMin)
            .put("endAt", s.endAt)
            .put("held", s.heldRemainingMs)
            .put("doneAt", s.doneAt)
            .put("beep", s.beep)
            .put("log", log)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    private fun read(context: Context): GateState = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw == null) GateState() else {
            val o = JSONObject(raw)
            val log = o.optJSONArray("log") ?: JSONArray()
            GateState(
                phase = Phase.valueOf(o.optString("phase", Phase.STANDBY.name)),
                presetMin = o.optInt("preset", 25),
                endAt = o.optLong("endAt"),
                heldRemainingMs = o.optLong("held"),
                doneAt = o.optLong("doneAt"),
                beep = o.optBoolean("beep", true),
                log = (0 until log.length()).map { i ->
                    log.getJSONObject(i).let { LogEntry(it.getInt("m"), it.getLong("t")) }
                },
            )
        }
    } catch (_: Exception) {
        GateState()
    }
}

/** Alarm at session end, and the notification's STOP action. */
class GateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        GateStore.load(app)
        when (intent.action) {
            GateStore.ACTION_ALARM -> GateStore.update(app) { it.complete(System.currentTimeMillis()) }
            GateStore.ACTION_STOP -> GateStore.stop(app)
        }
    }
}

/** Alarms are cleared by a reboot: re-arm a running session. */
class GateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        GateStore.load(app) // completes a session that expired while off
        GateStore.resync(app) // re-arms one that is still running
    }
}
