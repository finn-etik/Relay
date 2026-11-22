package dev.field.gate

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.field.ui.Kit

/**
 * GATE's side of the kit links (see dev.field.ui.Kit): other kit apps start
 * or stop a session here and read its countdown, so the kit has one timer.
 * Both entry points are open to any app on purpose; all they expose is a
 * focus timer.
 */
class GateLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        GateStore.load(app)
        when (intent.action) {
            Kit.ACTION_FOCUS_START -> {
                val minutes = intent.getIntExtra(Kit.EXTRA_MINUTES, 0)
                GateStore.update(app) { it.begin(System.currentTimeMillis(), if (minutes > 0) minutes else it.presetMin) }
            }
            Kit.ACTION_FOCUS_STOP -> GateStore.stop(app)
        }
    }
}

/** Read-only: one row (Kit.FOCUS_COLUMNS) while a session is running or on hold, no rows otherwise. */
class GateSessionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(Kit.FOCUS_COLUMNS)
        val app = context?.applicationContext ?: return cursor
        GateStore.load(app)
        val now = System.currentTimeMillis()
        val s = GateStore.state.value
        val remaining = s.remainingMs(now)
        when {
            s.phase == Phase.ON && remaining > 0L -> cursor.addRow(arrayOf<Any>(1, remaining, s.presetMin))
            s.phase == Phase.HOLD -> cursor.addRow(arrayOf<Any>(0, remaining, s.presetMin))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
