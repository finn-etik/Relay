package dev.field.reel

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * NVIDIA Parakeet TDT 0.6B v3 (CC-BY-4.0), int8 ONNX export by the
 * sherpa-onnx project: multilingual (25 European languages, detected
 * automatically), fully on-device once downloaded.
 *
 * The file list, sizes, SHA-256 hashes and pinned revision come from
 * assets/parakeet.json, generated at build time from the Hugging Face API.
 */
object ParakeetModel {

    sealed interface Status {
        data object NotInstalled : Status
        data class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean) : Status
        data object Verifying : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    data class FileSpec(val name: String, val size: Long, val sha256: String)

    private data class Manifest(val repo: String, val revision: String, val files: List<FileSpec>) {
        val totalBytes: Long get() = files.sumOf { it.size }
        fun url(f: FileSpec) = "https://huggingface.co/$repo/resolve/$revision/${f.name}"
    }

    private const val PREFS = "parakeet"
    private const val DIR = "parakeet-v3"
    private const val READY_MARKER = ".verified"

    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var manifest: Manifest? = null

    /** Approximate download size, for the UI before any download starts. */
    fun totalBytes(context: Context): Long = manifest(context)?.totalBytes ?: 0L

    fun dir(context: Context): File? = context.getExternalFilesDir(DIR)

    fun isReady(context: Context): Boolean = dir(context)?.let { File(it, READY_MARKER).exists() } == true

    fun encoder(context: Context) = File(dir(context), "encoder.int8.onnx").absolutePath
    fun decoder(context: Context) = File(dir(context), "decoder.int8.onnx").absolutePath
    fun joiner(context: Context) = File(dir(context), "joiner.int8.onnx").absolutePath
    fun tokens(context: Context) = File(dir(context), "tokens.txt").absolutePath

    /** Call on app start and periodically while the UI is visible. */
    @Synchronized
    fun refresh(context: Context) {
        if (isReady(context)) {
            _status.value = Status.Ready
            return
        }
        val ids = downloadIds(context)
        if (ids.isEmpty()) {
            if (_status.value !is Status.Failed) _status.value = Status.NotInstalled
            return
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        var done = 0L
        var total = 0L
        var waiting = false
        var allOk = true
        var failed: String? = null
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            val seen = c.count
            if (seen < ids.size) failed = "DOWNLOAD REMOVED"
            while (c.moveToNext()) {
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                total += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                if (st != DownloadManager.STATUS_SUCCESSFUL) allOk = false
                if (st == DownloadManager.STATUS_FAILED) failed = "DOWNLOAD FAILED ($reason)"
                if (st == DownloadManager.STATUS_PAUSED &&
                    (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK)
                ) waiting = true
            }
        }
        val expected = manifest(context)?.totalBytes ?: 0L
        when {
            failed != null -> {
                cancel(context)
                _status.value = Status.Failed(failed!!)
            }
            allOk -> {
                setDownloadIds(context, emptyList())
                verifyInBackground(context)
            }
            else -> _status.value = Status.Downloading(done, maxOf(total, expected), waiting)
        }
    }

    /** Starts (or restarts) the download. Over Wi-Fi only unless [allowMobile]. */
    @Synchronized
    fun download(context: Context, allowMobile: Boolean) {
        val m = manifest(context) ?: run {
            _status.value = Status.Failed("NO MODEL MANIFEST IN THIS BUILD")
            return
        }
        cancel(context)
        val target = dir(context) ?: run {
            _status.value = Status.Failed("STORAGE UNAVAILABLE")
            return
        }
        target.listFiles()?.forEach { it.delete() } // no stale partials: DownloadManager would rename
        val dm = context.getSystemService(DownloadManager::class.java)
        val ids = m.files.map { f ->
            dm.enqueue(
                DownloadManager.Request(Uri.parse(m.url(f)))
                    .setTitle("REEL / PARAKEET V3 / ${f.name}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(allowMobile)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, DIR, f.name)
            )
        }
        setDownloadIds(context, ids)
        _status.value = Status.Downloading(0, m.totalBytes, false)
    }

    @Synchronized
    fun cancel(context: Context) {
        val ids = downloadIds(context)
        if (ids.isNotEmpty()) context.getSystemService(DownloadManager::class.java).remove(*ids.toLongArray())
        setDownloadIds(context, emptyList())
        if (!isReady(context)) _status.value = Status.NotInstalled
    }

    @Synchronized
    fun delete(context: Context) {
        cancel(context)
        dir(context)?.listFiles()?.forEach { it.delete() }
        _status.value = Status.NotInstalled
    }

    private fun verifyInBackground(context: Context) {
        _status.value = Status.Verifying
        kotlin.concurrent.thread(name = "parakeet-verify", isDaemon = true) {
            val result = try {
                verify(context)
            } catch (e: Exception) {
                e.message ?: "VERIFY FAILED"
            }
            synchronized(this) {
                if (result == null) {
                    File(dir(context), READY_MARKER).writeText("ok")
                    _status.value = Status.Ready
                } else {
                    dir(context)?.listFiles()?.forEach { it.delete() }
                    _status.value = Status.Failed(result)
                }
            }
        }
    }

    /** Null when every file matches the manifest, else the problem. */
    private fun verify(context: Context): String? {
        val m = manifest(context) ?: return "NO MODEL MANIFEST"
        val dir = dir(context) ?: return "STORAGE UNAVAILABLE"
        for (f in m.files) {
            val file = File(dir, f.name)
            if (!file.exists()) return "MISSING ${f.name.uppercase()}"
            if (f.size > 0 && file.length() != f.size) return "SIZE MISMATCH ${f.name.uppercase()}"
            if (f.sha256.isNotEmpty() && sha256(file) != f.sha256.lowercase()) return "CHECKSUM MISMATCH ${f.name.uppercase()}"
        }
        return null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun manifest(context: Context): Manifest? {
        manifest?.let { return it }
        return try {
            val o = JSONObject(context.assets.open("parakeet.json").bufferedReader().readText())
            val files = o.getJSONArray("files")
            Manifest(
                repo = o.getString("repo"),
                revision = o.optString("revision", "main"),
                files = (0 until files.length()).map { i ->
                    files.getJSONObject(i).let { FileSpec(it.getString("name"), it.optLong("size", 0), it.optString("sha256", "")) }
                },
            ).also { manifest = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadIds(context: Context): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ids", "")!!
            .split(',').mapNotNull { it.toLongOrNull() }

    private fun setDownloadIds(context: Context, ids: List<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ids", ids.joinToString(",")).apply()
    }
}
