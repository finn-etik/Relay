package dev.relay

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Turns the phone's volume keys into a Sonos remote while relaying.
 *
 * A MediaSession with "remote playback" makes Android send volume-key
 * presses here instead of to the (muted) phone speaker. Every change is
 * sent to the speaker, and the level shown is the one the speaker reports
 * back, so changes made in the Sonos app never cause a jump.
 *
 * Create and use on the main thread.
 */
class SonosVolumeKeys(context: Context, private val speakerIp: () -> String?) {

    private val main = Handler(Looper.getMainLooper())
    private var released = false

    private val provider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 30) {
        override fun onAdjustVolume(direction: Int) {
            when (direction) {
                AudioManager.ADJUST_RAISE -> nudge(STEP)
                AudioManager.ADJUST_LOWER -> nudge(-STEP)
            }
        }

        override fun onSetVolumeTo(volume: Int) {
            val ip = speakerIp() ?: return
            show(volume)
            SonosControl.setVolume(ip, volume.coerceIn(0, 100))
        }
    }

    private val session = MediaSession(context, "Relay").apply {
        setPlaybackToRemote(provider)
        setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        isActive = true
    }

    /** Re-reads the speaker's volume (call after switching speakers). */
    fun refresh() {
        RelayState.volume.value = -1
        val ip = speakerIp() ?: return
        SonosControl.getVolume(ip) { level -> main.post { showIfCurrent(ip, level) } }
    }

    /** Relative change in Sonos volume units (0-100 scale). */
    fun nudge(delta: Int) {
        if (delta == 0) return
        val ip = speakerIp() ?: return
        show(provider.currentVolume + delta) // instant feedback...
        SonosControl.adjustVolume(ip, delta) { actual ->
            main.post { showIfCurrent(ip, actual) } // ...then the speaker's truth
        }
    }

    fun release() {
        released = true
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
        RelayState.volume.value = -1
    }

    private fun showIfCurrent(ip: String, level: Int) {
        if (!released && speakerIp() == ip) show(level)
    }

    private fun show(level: Int) {
        val clamped = level.coerceIn(0, 100)
        provider.setCurrentVolume(clamped)
        RelayState.volume.value = clamped
    }

    companion object {
        /** Volume units per key press. */
        const val STEP = 4
    }
}
