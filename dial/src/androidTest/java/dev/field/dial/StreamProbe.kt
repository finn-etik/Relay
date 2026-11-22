package dev.field.dial

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StreamProbe {
    private val tag = "DIALPROBE"
    private val urls = listOf(
        "https://live1.sr.se/p1-aac-320",
        "https://edge1.sr.se/p3-aac-320",
        "https://live1.sr.se/p1-aac-128",
        "https://live1.sr.se/p1-aac-32",
        "https://live1.sr.se/p1-mp3-96",
        "https://www.sverigesradio.se/topsy/direkt/srapi/132.mp3",
        "http://sverigesradio.se/topsy/direkt/srapi/132-hi-aac-http",
        "https://live1.sr.se/p2-flac",
        "https://ice1.somafm.com/groovesalad-128-mp3",
        "https://stream.radioparadise.com/mp3-128",
    )

    @Test fun frameworkMediaPlayer() {
        for (u in urls) Log.i(tag, "FRAMEWORK $u -> ${framework(u)}")
    }

    @Test fun media3() {
        for (u in urls) Log.i(tag, "MEDIA3 $u -> ${exo(u)}")
    }

    private fun framework(url: String): String {
        val thread = HandlerThread("probe").apply { start() }
        val h = Handler(thread.looper)
        val out = StringBuffer()
        val settled = CountDownLatch(1)
        var mp: MediaPlayer? = null
        h.post {
            val m = MediaPlayer()
            mp = m
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            m.setOnPreparedListener { out.append("prepared; "); it.start(); settled.countDown() }
            m.setOnErrorListener { _, what, extra -> out.append("ERROR what=$what extra=$extra; "); settled.countDown(); true }
            m.setOnCompletionListener { out.append("COMPLETED; ") }
            m.setOnInfoListener { _, what, extra -> out.append("info $what/$extra; "); false }
            try { m.setDataSource(url); m.prepareAsync() } catch (e: Exception) { out.append("EXC $e; "); settled.countDown() }
        }
        if (!settled.await(25, TimeUnit.SECONDS)) out.append("TIMEOUT preparing; ")
        Thread.sleep(7000)
        val done = CountDownLatch(1)
        h.post {
            try { out.append("pos=${mp?.currentPosition} playing=${mp?.isPlaying}") } catch (e: Exception) { out.append("state EXC $e") }
            try { mp?.release() } catch (_: Exception) {}
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        thread.quitSafely()
        return out.toString()
    }

    private fun exo(url: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thread = HandlerThread("probe").apply { start() }
        val h = Handler(thread.looper)
        val out = StringBuffer()
        var player: ExoPlayer? = null
        h.post {
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("DIAL/1.2")
            val p = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
                .build()
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { out.append("state=$state; ") }
                override fun onPlayerError(error: PlaybackException) { out.append("ERROR ${error.errorCodeName} ${error.cause}; ") }
                override fun onMediaMetadataChanged(m: MediaMetadata) { out.append("meta title=${m.title} station=${m.station}; ") }
            })
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
        Thread.sleep(12000)
        val done = CountDownLatch(1)
        h.post {
            out.append("pos=${player?.currentPosition} playing=${player?.isPlaying} fmt=${player?.audioFormat?.sampleMimeType}/${player?.audioFormat?.sampleRate}")
            player?.release()
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        thread.quitSafely()
        return out.toString()
    }
}
