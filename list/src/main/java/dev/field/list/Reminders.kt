package dev.field.list

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Task reminders. One alarm is kept armed for the next reminder; when it
 * fires (or on boot, or whenever the lists are read) the files are read,
 * every reminder that has come due is announced once, and the alarm is set
 * for the next one.
 *
 * In a shared space the partner can add a reminder while this phone's LIST
 * is closed, so with a folder space connected the alarm also wakes at least
 * every [RESCAN_MS] to look.
 */
object Reminders {
    private const val CHANNEL = "list_remind"
    private const val PREFS = "list_reminders"
    private const val ANNOUNCED = "announced"
    private const val ACTION = "dev.field.list.REMIND"
    private const val RESCAN_MS = 60 * 60_000L
    private const val KEEP_ANNOUNCED = 300

    /** Announces what is due and arms the alarm for what comes next. Any thread. */
    @Synchronized
    fun run(context: Context, lists: List<LoadedList>) {
        val app = context.applicationContext
        val now = LocalDateTime.now()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = LinkedHashSet(prefs.getString(ANNOUNCED, "").orEmpty().split('\n').filter { it.isNotEmpty() })

        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        var changed = false
        for (loaded in lists) {
            for (task in Tasks.dueReminders(loaded.list.tasks, now, announced)) {
                val key = Tasks.reminderKey(task)
                nm.notify(key.hashCode(), notification(app, task, loaded.ref))
                announced.add(key)
                changed = true
            }
        }
        if (changed) {
            // Newest last; drop the oldest so the set cannot grow without end.
            val kept = announced.toList().takeLast(KEEP_ANNOUNCED)
            prefs.edit().putString(ANNOUNCED, kept.joinToString("\n")).apply()
        }

        val next = lists.mapNotNull { Tasks.nextReminder(it.list.tasks, now) }.minOrNull()
            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val shared = ListStore.state.value.spaces.any { it.tree != null }
        val rescan = if (shared) System.currentTimeMillis() + RESCAN_MS else null
        arm(app, listOfNotNull(next, rescan).minOrNull(), exact = next != null && (rescan == null || next <= rescan))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(context: Context, at: Long?, exact: Boolean) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        if (at == null) {
            am.cancel(pi)
            return
        }
        val allowed = exact && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        try {
            if (allowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // a look-in, or no exact-alarm right: may be minutes late
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun notification(context: Context, task: Task, ref: ListRef): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(task.title)
            .setContentText("LIST / " + ref.name)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
}

/** The reminder alarm and device start-up (alarms do not survive a restart). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync() // reading the list files must not block the main thread
        Thread {
            try {
                ListStore.load(app)
                ListStore.refreshNow(app) // ends in Reminders.run
            } finally {
                pending.finish()
            }
        }.start()
    }
}
