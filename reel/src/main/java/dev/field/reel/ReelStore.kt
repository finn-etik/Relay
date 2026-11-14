package dev.field.reel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One recorded clip and its transcript. */
data class Take(
    val id: Int,
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val transcript: String = "",
)

enum class RecPhase { STANDBY, REC, HOLD }

/** Recording state, written by RecorderService, read by the UI. */
data class RecState(
    val phase: RecPhase = RecPhase.STANDBY,
    val takeId: Int = 0,
    /** Recorded audio so far (pauses excluded). */
    val recordedMs: Long = 0L,
    /** Wall clock when the current REC stretch began (for a smooth readout). */
    val stretchStartedAt: Long = 0L,
    val level: Float = 0f,
    val live: Boolean = false,
    val error: String? = null,
) {
    fun elapsed(now: Long): Long = when (phase) {
        RecPhase.REC -> recordedMs + (now - stretchStartedAt)
        RecPhase.HOLD -> recordedMs
        RecPhase.STANDBY -> 0L
    }
}

/** Transcription engine status. */
data class AsrState(
    val busyTakeId: Int = 0,
    val loading: Boolean = false,
    /** File transcription progress 0..1, or -1 for live / idle. */
    val progress: Float = -1f,
    val segments: Int = 0,
    val error: String? = null,
)

/**
 * Takes live in app storage (files/takes/TAKE_001.m4a) with a JSON index.
 * Unlike the original, the list survives restarts.
 */
object ReelStore {

    private val _takes = MutableStateFlow<List<Take>>(emptyList())
    val takes: StateFlow<List<Take>> = _takes.asStateFlow()

    val rec = MutableStateFlow(RecState())
    val asr = MutableStateFlow(AsrState())

    private var loaded = false
    private lateinit var dir: File

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        dir = File(context.filesDir, "takes").apply { mkdirs() }
        _takes.value = read().filter { File(dir, it.fileName).exists() }
    }

    fun file(take: Take): File = File(dir, take.fileName)

    private var lastId = 0

    /** Reserves the next take number and its file. */
    @Synchronized
    fun newTakeFile(): Pair<Int, File> {
        val id = (_takes.value.maxOfOrNull { it.id } ?: 0).coerceAtLeast(lastId) + 1
        lastId = id
        return id to File(dir, String.format(Locale.ROOT, "TAKE_%03d.m4a", id))
    }

    @Synchronized
    fun add(take: Take) {
        _takes.update { listOf(take) + it }
        write()
    }

    @Synchronized
    fun delete(id: Int) {
        _takes.value.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        _takes.update { list -> list.filter { it.id != id } }
        write()
    }

    @Synchronized
    fun setTranscript(id: Int, text: String) {
        _takes.update { list -> list.map { if (it.id == id) it.copy(transcript = text) else it } }
        write()
    }

    @Synchronized
    fun appendTranscript(id: Int, text: String) {
        if (text.isBlank()) return
        _takes.update { list ->
            list.map { if (it.id == id) it.copy(transcript = (it.transcript + " " + text.trim()).trim()) else it }
        }
        write()
    }

    private fun write() {
        val arr = JSONArray()
        _takes.value.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("file", it.fileName).put("dur", it.durationMs)
                    .put("size", it.sizeBytes).put("at", it.createdAt).put("text", it.transcript)
            )
        }
        val tmp = File(dir, "takes.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir, "takes.json")) // atomic replace: no half-written index
    }

    private fun read(): List<Take> = try {
        val arr = JSONArray(File(dir, "takes.json").readText())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                Take(
                    it.getInt("id"), it.getString("file"), it.getLong("dur"),
                    it.getLong("size"), it.getLong("at"), it.optString("text", ""),
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
