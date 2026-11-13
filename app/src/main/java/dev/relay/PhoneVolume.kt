package dev.relay

import android.content.Context
import android.media.AudioManager

/**
 * Silences the phone's media volume while relaying, and guarantees it
 * comes back: the original level is saved to disk before muting, so it
 * can be restored even if the app is killed mid-session.
 *
 * Muting doesn't affect what Sonos hears: playback capture taps the audio
 * at unity gain, before the phone's volume is applied.
 */
class PhoneVolume(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var muted = false

    fun mute() {
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // commit(), not apply(): the value must be on disk before we mute.
        prefs(context).edit().putInt(KEY_SAVED, current).commit()
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            muted = true
        } catch (_: SecurityException) {
            forgetSaved(context) // e.g. blocked by Do Not Disturb policy
        }
    }

    /** Idempotent. */
    fun restore() {
        if (!muted) return
        muted = false
        val saved = prefs(context).getInt(KEY_SAVED, -1)
        if (saved >= 0) setMusicVolume(audio, saved)
        forgetSaved(context)
    }

    companion object {
        private const val KEY_SAVED = "saved_phone_volume"

        /**
         * Call on app start. If a previous session died while the phone was
         * muted, put the volume back (unless the user already raised it).
         */
        fun recoverAfterCrash(context: Context) {
            if (RelayState.isActive) return
            val saved = prefs(context).getInt(KEY_SAVED, -1)
            if (saved < 0) return
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) setMusicVolume(audio, saved)
            forgetSaved(context)
        }

        private fun setMusicVolume(audio: AudioManager, level: Int) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            } catch (_: SecurityException) {
            }
        }

        private fun forgetSaved(context: Context) =
            prefs(context).edit().remove(KEY_SAVED).apply()

        private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    }
}
