package dev.field.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Links between the kit's apps. Each link is an ordinary Android hand-off to
 * a named app, so it works whenever that app is installed and quietly
 * reports "not installed" when it is not. Nothing is shared but the message
 * itself: no common storage, no accounts.
 *
 * - Focus: any app can start or stop a GATE session and read its countdown
 *   (CAL entry -> FOCUS, LIST task -> FOCUS, PAGE sprints).
 * - Text: any app can hand text to PAGE (a new note) or LIST (a new task).
 *
 * The links carry no permission: the apps are sideloaded and may be signed
 * with different keys, which rules out a signature-level permission. What
 * they expose is deliberately harmless (a focus timer and its countdown).
 */
object Kit {
    const val CAL = "dev.field.cal"
    const val DIAL = "dev.field.dial"
    const val GATE = "dev.field.gate"
    const val LIST = "dev.field.list"
    const val PAGE = "dev.field.page"
    const val REEL = "dev.field.reel"
    const val TALLY = "dev.field.tally"

    /** Handled by GATE's GateLinkReceiver. */
    const val ACTION_FOCUS_START = "dev.field.gate.link.START"
    const val ACTION_FOCUS_STOP = "dev.field.gate.link.STOP"
    const val EXTRA_MINUTES = "minutes"

    /** Served by GATE's GateSessionProvider: one row while a session exists, none otherwise. */
    const val FOCUS_AUTHORITY = "dev.field.gate.session"
    val FOCUS_COLUMNS = arrayOf("running", "remaining_ms", "minutes")

    /** GATE's longest session, so callers can say what they will get. */
    const val MAX_FOCUS_MINUTES = 180

    /** A GATE session as other apps see it. [running] is false while it is on HOLD. */
    data class Focus(val running: Boolean, val remainingMs: Long, val minutes: Int)

    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Starts a GATE session of [minutes] (GATE clamps it to its limits). False = GATE is not installed. */
    fun startFocus(context: Context, minutes: Int): Boolean {
        if (!installed(context, GATE)) return false
        context.sendBroadcast(
            Intent(ACTION_FOCUS_START).setClassName(GATE, "$GATE.GateLinkReceiver").putExtra(EXTRA_MINUTES, minutes)
        )
        return true
    }

    fun stopFocus(context: Context) {
        if (!installed(context, GATE)) return
        context.sendBroadcast(Intent(ACTION_FOCUS_STOP).setClassName(GATE, "$GATE.GateLinkReceiver"))
    }

    /** GATE's current session, or null if there is none (or no GATE). Asks another process: call off the main thread. */
    fun focus(context: Context): Focus? = try {
        context.contentResolver.query(Uri.parse("content://$FOCUS_AUTHORITY/current"), null, null, null, null)?.use { c ->
            if (c.moveToFirst()) Focus(c.getInt(0) == 1, c.getLong(1), c.getInt(2)) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Hands [text] to another kit app as shared text ([subject] becomes the
     * title where the receiver has one). False = that app is not installed.
     */
    fun sendText(context: Context, pkg: String, subject: String, text: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClassName(pkg, "$pkg.MainActivity")
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text)
        )
        true
    } catch (_: Exception) {
        false
    }
}
