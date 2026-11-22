package dev.field.lore

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Everything LORE keeps and does: the conversations, the model, the
 * knowledge packs (ZIM archives), the notes index, and the downloads.
 * The screen shows [state] and calls the functions below.
 *
 * Three workers, so nothing waits on anything it does not need:
 * - [brain] loads the model and writes answers, one at a time;
 * - [desk] does file work (opening archives, indexing notes, copies);
 * - [clock] watches the downloads once a second while there are any.
 *
 * Large files are fetched by the system's DownloadManager: it resumes after
 * a lost connection or a reboot and keeps going while the app is closed,
 * which a 40 GB archive needs.
 */
object LoreStore {

    enum class Engine { NONE, STANDBY, LOADING, READY, FAILED }

    /** A download or copy in progress. [total] is -1 until known. */
    data class Transfer(val done: Long, val total: Long, val note: String = "")

    /** An archive on the shelf. [packId] is empty for a file the user added. */
    data class Archive(val key: String, val name: String, val detail: String, val packId: String, val open: Boolean)

    /** A whole article or note, opened from a source. */
    data class Reading(val title: String, val passages: List<Passage>)

    data class State(
        val chats: List<Chat> = emptyList(),
        /** The chat on screen; 0 = a new one that has no question yet. */
        val current: Long = 0,
        val modelId: String = Catalog.MODELS.first().id,
        /** Ids of the models whose files are on the phone. */
        val installed: Set<String> = emptySet(),
        /** File names of models the user added from storage (id = `file:` + name). */
        val added: List<String> = emptyList(),
        val engine: Engine = Engine.NONE,
        /** GPU or CPU once loaded; the reason when loading failed. */
        val engineNote: String = "",
        /** Keyed `model:<id>`, `pack:<id>` or `copy`. */
        val transfers: Map<String, Transfer> = emptyMap(),
        val failures: Map<String, String> = emptyMap(),
        /** What Kiwix currently publishes for a pack, once looked up. */
        val remotes: Map<String, Remote> = emptyMap(),
        val looking: Set<String> = emptySet(),
        val shelf: List<Archive> = emptyList(),
        val notesFolder: String = "",
        val notesName: String = "",
        val notesFiles: Int = 0,
        val notesPassages: Int = 0,
        val notesNote: String = "",
        val indexing: Boolean = false,
        val useNotes: Boolean = true,
        val usePacks: Boolean = true,
        /** AUTO, GPU or CPU. */
        val backend: String = "AUTO",
        val metered: Boolean = false,
        val freeBytes: Long = 0,
        val reading: Reading? = null,
        val notice: String = "",
    ) {
        val chat: Chat? get() = chats.firstOrNull { it.id == current }
        val busy: Boolean get() = chats.any { c -> c.turns.any { it.busy } }
        val models: List<Model> get() = Catalog.MODELS + added.map { addedModel(it) }
        val model: Model get() = models.firstOrNull { it.id == modelId } ?: Catalog.MODELS.first()
        val ready: Boolean get() = modelId in installed
        val hasPacks: Boolean get() = shelf.any { it.open }
        val hasNotes: Boolean get() = notesPassages > 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var loaded = false

    private val brain = Executors.newSingleThreadExecutor()
    private val desk = Executors.newSingleThreadExecutor()
    private val clock = Executors.newSingleThreadScheduledExecutor()

    // Owned by [brain].
    @Volatile private var llm: Llm? = null
    private var llmModel = ""
    @Volatile private var stopped = false

    // Replaced as a whole by [desk]; read by [brain].
    @Volatile private var zims: Map<String, Zim> = emptyMap()
    @Volatile private var notes: NotesIndex? = null
    private var openFiles: List<Closeable> = emptyList()

    private var lastId = 0L
    private var watching = false

    // ------------------------------------------------------------- start

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("lore", Context.MODE_PRIVATE)

        // A marker left behind means the last GPU start killed the app: do not walk into it again.
        if (prefs.getBoolean(GPU_TRYING, false)) prefs.edit().putBoolean(GPU_BAD, true).putBoolean(GPU_TRYING, false).apply()

        val added = strings(ADDED).filter { File(modelsDir(), it).length() > 0 }
        val installed = (prefs.getStringSet(MODELS, emptySet()) ?: emptySet()).filter { modelFile(it).length() > 0 }.toSet()
        val modelId = prefs.getString(MODEL, null)?.takeIf { it in installed } ?: installed.firstOrNull() ?: Catalog.MODELS.first().id
        _state.value = State(
            chats = Chats.settle(readChats()),
            modelId = modelId,
            installed = installed,
            added = added,
            engine = if (modelId in installed) Engine.STANDBY else Engine.NONE,
            notesFolder = prefs.getString(NOTES, "").orEmpty(),
            notesName = prefs.getString(NOTES_NAME, "").orEmpty(),
            useNotes = prefs.getBoolean(USE_NOTES, true),
            usePacks = prefs.getBoolean(USE_PACKS, true),
            backend = prefs.getString(BACKEND, "AUTO") ?: "AUTO",
            metered = prefs.getBoolean(METERED, false),
        )
        desk.execute {
            openArchives()
            indexNotes()
        }
        if (downloads().isNotEmpty()) watch()
    }

    /** Coming back to the app: notes may have changed, downloads may have finished. */
    fun refresh() {
        if (!loaded) return
        desk.execute {
            _state.update { it.copy(freeBytes = packsDir().usableSpace) }
            indexNotes()
        }
        if (downloads().isNotEmpty()) watch()
    }

    // -------------------------------------------------------------- chat

    fun ask(question: String) {
        val text = question.trim()
        val s = _state.value
        if (text.isEmpty() || s.busy) return
        if (!s.ready) return notice("GET A MODEL FIRST")
        val turnId = nextId()
        val chatId = s.chat?.id ?: turnId
        stopped = false
        _state.update { it.copy(chats = Chats.ask(it.chats, chatId, turnId, text, System.currentTimeMillis()), current = chatId) }
        brain.execute { answer(chatId, turnId, text) }
    }

    /** Asks the current chat's last question again. */
    fun askAgain() {
        val s = _state.value
        val chat = s.chat ?: return
        val last = chat.turns.lastOrNull() ?: return
        if (s.busy) return
        _state.update { it.copy(chats = Chats.dropLast(it.chats, chat.id), current = if (chat.turns.size == 1) 0 else chat.id) }
        ask(last.question)
    }

    fun stop() {
        stopped = true
        llm?.stop()
    }

    fun newChat() = _state.update { it.copy(current = 0) }

    fun openChat(id: Long) = _state.update { it.copy(current = id) }

    fun deleteChat(id: Long) {
        if (_state.value.chats.firstOrNull { it.id == id }?.turns?.any { it.busy } == true) stop()
        _state.update { it.copy(chats = Chats.remove(it.chats, id), current = if (it.current == id) 0 else it.current) }
        saveChats()
    }

    fun setUseNotes(on: Boolean) {
        prefs.edit().putBoolean(USE_NOTES, on).apply()
        _state.update { it.copy(useNotes = on) }
    }

    fun setUsePacks(on: Boolean) {
        prefs.edit().putBoolean(USE_PACKS, on).apply()
        _state.update { it.copy(usePacks = on) }
    }

    private fun answer(chatId: Long, turnId: Long, question: String) {
        fun turn(change: (Turn) -> Turn) = _state.update { it.copy(chats = Chats.update(it.chats, chatId, turnId, change)) }
        val s = _state.value
        val model = s.model
        try {
            val engine = engine(model)
            val sources = if (stopped) emptyList() else search(question, model, s, engine)
            turn { it.copy(sources = sources, stage = Stage.WRITING) }
            val chat = _state.value.chats.firstOrNull { it.id == chatId }
            val history = if (chat == null) emptyList() else Ask.history(Chats.before(chat, turnId), model.historyChars)

            var shown = 0L
            fun write(history: List<Pair<String, String>>, sources: List<Source>): String =
                engine.answer(Ask.system(today()), history, Ask.prompt(question, sources)) { text ->
                    val now = SystemClock.uptimeMillis()
                    if (now - shown >= 80) {
                        shown = now
                        turn { it.copy(answer = Ask.clean(text)) }
                    }
                }

            val raw = when {
                stopped -> ""
                else -> try {
                    write(history, sources)
                } catch (e: Throwable) {
                    // Most likely too much text for the model's context: once more with the bare minimum.
                    if (stopped || (history.isEmpty() && sources.size <= 1)) throw e
                    val fewer = sources.take(1)
                    turn { it.copy(sources = fewer, answer = "") }
                    write(emptyList(), fewer)
                }
            }
            val text = Ask.clean(raw)
            turn {
                when {
                    text.isNotEmpty() -> it.copy(answer = text, stage = if (stopped) Stage.STOPPED else Stage.DONE)
                    stopped -> it.copy(stage = Stage.STOPPED)
                    else -> it.copy(stage = Stage.FAILED, note = "THE MODEL GAVE NO ANSWER")
                }
            }
        } catch (e: Throwable) {
            turn { if (stopped) it.copy(stage = Stage.STOPPED) else it.copy(stage = Stage.FAILED, note = describe(e)) }
        }
        saveChats()
    }

    /** The passages to quote for [question]: from the notes, and from the articles its words name. */
    private fun search(question: String, model: Model, s: State, engine: Llm): List<Source> {
        val index = notes?.takeIf { s.useNotes && it.chunks.isNotEmpty() }
        val archives = if (s.usePacks) zims else emptyMap()
        val lookup = { title: String -> find(archives, title) }

        val fromNotes = if (index == null) emptyList()
        else Ask.fromNotes(question, index, if (archives.isEmpty()) model.sourceChars else model.sourceChars * 2 / 5)

        var articles = if (archives.isEmpty()) emptyList() else Ask.articles(question, 3, lookup)
        if (articles.isEmpty() && archives.isNotEmpty() && fromNotes.isEmpty() && !stopped) {
            // Nothing in the question is an article title ("why is the sky blue"): let the model name some.
            val reply = runCatching { engine.answer(Ask.TITLE_SYSTEM, emptyList(), Ask.titlePrompt(question)) {} }.getOrDefault("")
            articles = Ask.articlesNamed(Ask.titlesFrom(Ask.clean(reply)), lookup).take(3)
        }
        return fromNotes + Ask.fromArticles(question, articles, model.sourceChars - fromNotes.sumOf { it.text.length })
    }

    private fun find(archives: Map<String, Zim>, title: String): Article? {
        for ((key, zim) in archives) {
            try {
                val entry = zim.resolve(zim.byTitle(title) ?: continue) ?: continue
                if (entry.mime?.startsWith("text/html") != true) continue
                val html = String(zim.content(entry, MAX_ARTICLE), Charsets.UTF_8)
                val name = _state.value.shelf.firstOrNull { it.key == key }?.name ?: "PACK"
                return Article(entry.title, name, "$key\t${entry.title}", Passages.of(Html.blocks(html)))
            } catch (_: Exception) {
                // A damaged or vanished archive must not break the answer: try the next one.
            }
        }
        return null
    }

    /** Opens the whole article or note a source was taken from. */
    fun read(source: Source) = desk.execute {
        val passages = try {
            if (source.origin == Origin.NOTE) {
                Passages.ofNote(readText(Uri.parse(source.ref)), size = 1200)
            } else {
                val key = source.ref.substringBefore('\t')
                val zim = zims[key] ?: throw IOException("Pack is not on the shelf any more")
                val entry = zim.byTitle(source.ref.substringAfter('\t'))?.let(zim::resolve) ?: throw IOException("Article not found")
                Passages.of(Html.blocks(String(zim.content(entry, MAX_ARTICLE), Charsets.UTF_8)), size = 1200)
            }
        } catch (e: Exception) {
            return@execute notice(describe(e))
        }
        _state.update { it.copy(reading = Reading(source.title, passages)) }
    }

    fun closeReading() = _state.update { it.copy(reading = null) }

    // ------------------------------------------------------------- model

    private fun engine(model: Model): Llm {
        llm?.let { if (llmModel == model.id) return it }
        unload()
        _state.update { it.copy(engine = Engine.LOADING, engineNote = "") }
        val path = modelFile(model.id).path
        val cache = File(app.cacheDir, "litert").apply { mkdirs() }.path
        val choice = _state.value.backend
        var failure: Throwable? = null
        var started: Llm? = null

        // Some files are built for one context size only: if asking for ours fails, take theirs.
        fun start(gpu: Boolean): Llm = try {
            Llm.load(path, cache, model.maxTokens, gpu)
        } catch (e: Throwable) {
            if (model.maxTokens == null) throw e
            Llm.load(path, cache, null, gpu)
        }

        if (choice == "GPU" || (choice == "AUTO" && !prefs.getBoolean(GPU_BAD, false))) {
            // commit(): the marker must be on disk before a driver gets the chance to take the process down.
            prefs.edit().putBoolean(GPU_TRYING, true).commit()
            try {
                started = start(gpu = true)
            } catch (e: Throwable) {
                failure = e
                if (choice == "AUTO") prefs.edit().putBoolean(GPU_BAD, true).apply()
            }
            prefs.edit().putBoolean(GPU_TRYING, false).commit()
        }
        if (started == null && choice != "GPU") {
            try {
                started = start(gpu = false)
            } catch (e: Throwable) {
                failure = e
            }
        }
        val ready = started
        if (ready == null) {
            val why = failure ?: IllegalStateException("Model did not load")
            _state.update { it.copy(engine = Engine.FAILED, engineNote = describe(why)) }
            throw why
        }
        llm = ready
        llmModel = model.id
        _state.update { it.copy(engine = Engine.READY, engineNote = if (ready.gpu) "GPU" else "CPU") }
        return ready
    }

    /** On [brain]. */
    private fun unload() {
        llm?.close()
        llm = null
        llmModel = ""
    }

    private fun standby() {
        stop()
        brain.execute {
            unload()
            _state.update { it.copy(engine = if (it.ready) Engine.STANDBY else Engine.NONE, engineNote = "") }
        }
    }

    fun useModel(id: String) {
        if (id !in _state.value.installed) return
        prefs.edit().putString(MODEL, id).apply()
        _state.update { it.copy(modelId = id) }
        standby()
    }

    fun setBackend(backend: String) {
        prefs.edit().putString(BACKEND, backend).putBoolean(GPU_BAD, false).apply()
        _state.update { it.copy(backend = backend) }
        standby()
    }

    fun getModel(id: String) {
        val model = Catalog.MODELS.firstOrNull { it.id == id } ?: return
        download("model:$id", model.url, "models", model.file, model.name)
    }

    fun deleteModel(id: String) {
        val file = modelFile(id)
        val installed = _state.value.installed - id
        prefs.edit().putStringSet(MODELS, installed).apply()
        val added = _state.value.added.filter { "file:$it" != id }
        putStrings(ADDED, added)
        _state.update { s ->
            val next = if (s.modelId == id) installed.firstOrNull() ?: Catalog.MODELS.first().id else s.modelId
            s.copy(installed = installed, added = added, modelId = next)
        }
        stop()
        brain.execute {
            unload()
            file.delete()
            _state.update { it.copy(engine = if (it.ready) Engine.STANDBY else Engine.NONE, engineNote = "", freeBytes = packsDir().usableSpace) }
        }
    }

    /** Copies a `.litertlm` file the user picked into the app (the runtime needs a real path). */
    fun addModel(uri: Uri) = desk.execute {
        val name = displayName(uri).ifEmpty { "model.litertlm" }
        if (!name.endsWith(".litertlm")) return@execute notice("NOT A .LITERTLM MODEL FILE")
        val target = File(modelsDir(), name)
        try {
            val total = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var done = 0L
                    var shown = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - shown >= 32 shl 20) {
                            shown = done
                            transfer("copy", Transfer(done, total, name))
                        }
                    }
                }
            } ?: throw IOException("Cannot open the file")
        } catch (e: Exception) {
            target.delete()
            _state.update { it.copy(transfers = it.transfers - "copy") }
            return@execute notice(describe(e))
        }
        val id = "file:$name"
        val added = (_state.value.added + name).distinct()
        putStrings(ADDED, added)
        val installed = _state.value.installed + id
        prefs.edit().putStringSet(MODELS, installed).apply()
        _state.update { it.copy(transfers = it.transfers - "copy", added = added, installed = installed) }
        if (!_state.value.ready) useModel(id)
    }

    // ------------------------------------------------------------- packs

    /** Looks up what Kiwix publishes for a pack right now. Needs the network; nothing else in LORE asks for it unprompted. */
    fun findPack(id: String) {
        val pack = Catalog.pack(id) ?: return
        _state.update { it.copy(looking = it.looking + id, failures = it.failures - "pack:$id") }
        desk.execute {
            val remote = try {
                Packs.latest(fetch(pack.listing), pack)
            } catch (e: Exception) {
                null
            }
            _state.update {
                it.copy(
                    looking = it.looking - id,
                    remotes = if (remote == null) it.remotes else it.remotes + (id to remote),
                    failures = if (remote == null) it.failures + ("pack:$id" to "NOT FOUND · CHECK THE CONNECTION") else it.failures,
                    freeBytes = packsDir().usableSpace,
                )
            }
        }
    }

    fun getPack(id: String) {
        val pack = Catalog.pack(id) ?: return
        val remote = _state.value.remotes[id] ?: return
        download("pack:$id", remote.url, "packs", Packs.fileOf(pack), pack.name)
    }

    fun deletePack(id: String) {
        val pack = Catalog.pack(id) ?: return
        prefs.edit().putStringSet(PACKS, (prefs.getStringSet(PACKS, emptySet()) ?: emptySet()) - id).apply()
        desk.execute {
            openArchives()
            File(packsDir(), Packs.fileOf(pack)).delete()
            _state.update { it.copy(freeBytes = packsDir().usableSpace) }
        }
    }

    /** Puts a ZIM file the user already has on the shelf. It is read where it is, not copied. */
    fun addArchive(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        putStrings(ARCHIVES, (strings(ARCHIVES) + uri.toString()).distinct())
        desk.execute { openArchives() }
    }

    fun removeArchive(key: String) {
        putStrings(ARCHIVES, strings(ARCHIVES) - key)
        runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(key), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        desk.execute { openArchives() }
    }

    /** On [desk]: (re)opens every archive that should be on the shelf. */
    private fun openArchives() {
        val opened = LinkedHashMap<String, Zim>()
        val files = ArrayList<Closeable>()
        val rows = ArrayList<Archive>()
        val inflate = Inflater { ZstdInputStream(it) }

        fun open(key: String, name: String, packId: String, channel: () -> FileChannel) {
            try {
                val ch = channel().also { files += it }
                val zim = Zim(ChannelBytes(ch), inflate)
                val title = if (packId.isEmpty()) zim.metadata("Title")?.takeIf { it.isNotBlank() }?.uppercase() ?: name else name
                val date = zim.metadata("Date")?.take(7).orEmpty()
                val detail = listOf("${Format.count(zim.titleCount)} ARTICLES", Format.bytes(ch.size()), date).filter { it.isNotEmpty() }.joinToString(" · ")
                opened[key] = zim
                rows += Archive(key, title, detail, packId, open = true)
            } catch (e: Exception) {
                rows += Archive(key, name, "CANNOT OPEN · " + describe(e), packId, open = false)
            }
        }

        val packs = prefs.getStringSet(PACKS, emptySet()) ?: emptySet()
        for (pack in Catalog.PACKS) if (pack.id in packs) {
            open(pack.id, pack.name, pack.id) { FileInputStream(File(packsDir(), Packs.fileOf(pack))).channel }
        }
        for (uri in strings(ARCHIVES)) {
            val parsed = Uri.parse(uri)
            open(uri, displayName(parsed).uppercase().ifEmpty { "ARCHIVE" }, "") {
                val pfd = app.contentResolver.openFileDescriptor(parsed, "r") ?: throw IOException("File is gone")
                files += pfd
                FileInputStream(pfd.fileDescriptor).channel
            }
        }

        val old = openFiles
        zims = opened
        openFiles = files
        // A lookup still running on the old files fails cleanly and is skipped (see [find]).
        old.forEach { runCatching { it.close() } }
        _state.update { it.copy(shelf = rows, freeBytes = packsDir().usableSpace) }
    }

    private class ChannelBytes(private val channel: FileChannel) : Bytes {
        override val size: Long = channel.size()
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int) {
            val buffer = ByteBuffer.wrap(into, offset, length)
            var at = position
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                if (n < 0) throw IOException("Unexpected end of file")
                at += n
            }
        }
    }

    // ------------------------------------------------------------- notes

    fun setNotesFolder(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }.getOrDefault("")
            .ifEmpty { "FOLDER" }.uppercase()
        prefs.edit().putString(NOTES, uri.toString()).putString(NOTES_NAME, name).apply()
        _state.update { it.copy(notesFolder = uri.toString(), notesName = name) }
        desk.execute { indexNotes() }
    }

    fun forgetNotes() {
        val uri = _state.value.notesFolder
        if (uri.isNotEmpty()) runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().remove(NOTES).remove(NOTES_NAME).apply()
        notesCache().delete()
        notes = null
        _state.update { it.copy(notesFolder = "", notesName = "", notesFiles = 0, notesPassages = 0, notesNote = "") }
    }

    private class Doc(val id: String, val name: String, val modified: Long)
    private class Cached(val modified: Long, val passages: List<Passage>)

    /**
     * On [desk]: brings the index in line with the folder. Only files that
     * changed since last time are read again; the rest come from the cache.
     */
    private fun indexNotes() {
        val folder = _state.value.notesFolder
        if (folder.isEmpty()) return
        _state.update { it.copy(indexing = true) }
        try {
            val tree = Uri.parse(folder)
            val docs = listNotes(tree)
            val cache = readNotesCache()
            val fresh = LinkedHashMap<String, Cached>()
            val chunks = ArrayList<NoteChunk>()
            for (doc in docs) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
                val known = cache[doc.id]?.takeIf { it.modified == doc.modified && doc.modified != 0L }
                val passages = known?.passages ?: runCatching { Passages.ofNote(readText(uri)) }.getOrDefault(emptyList())
                fresh[doc.id] = Cached(doc.modified, passages)
                for (p in passages) chunks += NoteChunk(doc.name, uri.toString(), p)
            }
            writeNotesCache(fresh)
            notes = NotesIndex(chunks)
            _state.update { it.copy(indexing = false, notesFiles = docs.size, notesPassages = chunks.size, notesNote = "") }
        } catch (e: Exception) {
            _state.update { it.copy(indexing = false, notesNote = "CANNOT READ THE FOLDER") }
        }
    }

    /** Text files in the folder and its subfolders (Markdown or plain), hidden ones left out. */
    private fun listNotes(tree: Uri): List<Doc> {
        val out = ArrayList<Doc>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += DocumentsContract.getTreeDocumentId(tree) to 0
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (queue.isNotEmpty() && out.size < MAX_NOTES) {
            val (parent, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            app.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 5) queue += c.getString(0) to depth + 1
                    } else if (NOTE_TYPES.any { name.endsWith(it, ignoreCase = true) } && c.getLong(4) <= MAX_NOTE_BYTES) {
                        out += Doc(c.getString(0), name, c.getLong(3))
                    }
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun readText(uri: Uri): String =
        app.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: throw IOException("Cannot open the note")

    private fun notesCache() = File(app.filesDir, "notes-index.json")

    private fun readNotesCache(): Map<String, Cached> = try {
        val json = JSONObject(notesCache().readText())
        if (json.optString("folder") != _state.value.notesFolder) emptyMap()
        else {
            val files = json.getJSONObject("files")
            files.keys().asSequence().associateWith { id ->
                val f = files.getJSONObject(id)
                val p = f.getJSONArray("p")
                Cached(f.getLong("m"), (0 until p.length()).map { i -> p.getJSONArray(i).let { Passage(it.getString(0), it.getString(1)) } })
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeNotesCache(cache: Map<String, Cached>) {
        val files = JSONObject()
        for ((id, c) in cache) {
            val p = JSONArray()
            for (passage in c.passages) p.put(JSONArray().put(passage.heading).put(passage.text))
            files.put(id, JSONObject().put("m", c.modified).put("p", p))
        }
        runCatching { notesCache().writeText(JSONObject().put("folder", _state.value.notesFolder).put("files", files).toString()) }
    }

    // --------------------------------------------------------- downloads

    fun setMetered(on: Boolean) {
        prefs.edit().putBoolean(METERED, on).apply()
        _state.update { it.copy(metered = on) }
    }

    private fun manager() = app.getSystemService(DownloadManager::class.java)

    private fun downloads(): Map<String, Long> = try {
        val json = JSONObject(prefs.getString(DOWNLOADS, "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.getLong(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    @Synchronized
    private fun putDownload(key: String, id: Long?) {
        val json = JSONObject()
        for ((k, v) in downloads()) if (k != key) json.put(k, v)
        if (id != null) json.put(key, id)
        prefs.edit().putString(DOWNLOADS, json.toString()).apply()
    }

    private fun download(key: String, url: String, dir: String, file: String, title: String) {
        if (key in downloads()) return
        val folder = app.getExternalFilesDir(dir) ?: return fail(key, "NO STORAGE TO SAVE TO")
        File(folder, file).delete() // the system would otherwise save beside it under another name
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("LORE · $title")
                .setDestinationInExternalFilesDir(app, dir, file)
                .setAllowedOverMetered(_state.value.metered)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            putDownload(key, manager().enqueue(request))
        } catch (e: Exception) {
            return fail(key, describe(e))
        }
        _state.update { it.copy(transfers = it.transfers + (key to Transfer(0, -1, "STARTING")), failures = it.failures - key) }
        watch()
    }

    /** Cancels a download in progress and removes what was fetched so far. */
    fun cancel(key: String) {
        val id = downloads()[key] ?: return
        putDownload(key, null)
        runCatching { manager().remove(id) }
        _state.update { it.copy(transfers = it.transfers - key) }
    }

    private fun fail(key: String, why: String) =
        _state.update { it.copy(transfers = it.transfers - key, failures = it.failures + (key to why)) }

    private fun transfer(key: String, value: Transfer) = _state.update { it.copy(transfers = it.transfers + (key to value)) }

    @Synchronized
    private fun watch() {
        if (watching) return
        watching = true
        clock.schedule(Runnable { tick() }, 0, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        try {
            for ((key, id) in downloads()) check(key, id)
        } catch (_: Exception) {
            // The download service is briefly unavailable: look again next second.
        }
        synchronized(this) {
            if (downloads().isEmpty()) watching = false else clock.schedule(Runnable { tick() }, 1, TimeUnit.SECONDS)
        }
    }

    private fun check(key: String, id: Long) {
        manager().query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) {
                putDownload(key, null)
                return fail(key, "DOWNLOAD WAS REMOVED")
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    putDownload(key, null) // forget it, but do not remove(): that would delete the file
                    finished(key)
                }
                DownloadManager.STATUS_FAILED -> {
                    putDownload(key, null)
                    runCatching { manager().remove(id) }
                    fail(key, failure(reason))
                }
                DownloadManager.STATUS_PAUSED -> transfer(
                    key,
                    Transfer(
                        done, total,
                        when (reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "WAITING FOR WI-FI"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "WAITING FOR NETWORK"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "RETRYING"
                            else -> "PAUSED"
                        },
                    ),
                )
                else -> transfer(key, Transfer(done, total))
            }
        }
    }

    private fun failure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "NOT ENOUGH STORAGE"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "NO STORAGE TO SAVE TO"
        DownloadManager.ERROR_CANNOT_RESUME -> "CONNECTION LOST AND THE SERVER CANNOT RESUME"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "NETWORK ERROR"
        404 -> "FILE NOT FOUND ON THE SERVER"
        in 400..599 -> "SERVER SAID $reason"
        else -> "DOWNLOAD FAILED ($reason)"
    }

    private fun finished(key: String) {
        val id = key.substringAfter(':')
        if (key.startsWith("model:")) {
            val installed = _state.value.installed + id
            prefs.edit().putStringSet(MODELS, installed).apply()
            _state.update { it.copy(transfers = it.transfers - key, installed = installed) }
            if (!_state.value.ready) useModel(id)
        } else {
            prefs.edit().putStringSet(PACKS, (prefs.getStringSet(PACKS, emptySet()) ?: emptySet()) + id).apply()
            _state.update { it.copy(transfers = it.transfers - key) }
            desk.execute { openArchives() }
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "LORE (Field kit)")
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1 shl 16)
                while (out.size() < MAX_LISTING) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------ helpers

    private fun modelsDir(): File = (app.getExternalFilesDir("models") ?: File(app.filesDir, "models")).apply { mkdirs() }
    private fun packsDir(): File = (app.getExternalFilesDir("packs") ?: File(app.filesDir, "packs")).apply { mkdirs() }

    private fun modelFile(id: String): File =
        File(modelsDir(), if (id.startsWith("file:")) id.removePrefix("file:") else Catalog.model(id).file)

    /** A model the user added: its limits are unknown, so the budgets are cautious. */
    private fun addedModel(file: String) = Model(
        "file:$file", file.removeSuffix(".litertlm").uppercase(), "ADDED FROM A FILE", "file:///$file",
        maxTokens = null, sourceChars = 2400, historyChars = 800,
    )

    private fun displayName(uri: Uri): String = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun strings(key: String): List<String> = try {
        val json = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until json.length()).map(json::getString)
    } catch (_: Exception) {
        emptyList()
    }

    private fun putStrings(key: String, values: List<String>) =
        prefs.edit().putString(key, JSONArray(values).toString()).apply()

    @Synchronized
    private fun nextId(): Long {
        lastId = maxOf(System.currentTimeMillis(), lastId + 1)
        return lastId
    }

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))

    private fun describe(e: Throwable): String =
        (e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(140).uppercase()

    private fun notice(text: String) {
        _state.update { it.copy(notice = text) }
        clock.schedule(Runnable { _state.update { if (it.notice == text) it.copy(notice = "") else it } }, 4, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------ conversations

    private fun chatsFile() = File(app.filesDir, "chats.json")

    private fun saveChats() {
        val chats = _state.value.chats
        desk.execute {
            val all = JSONArray()
            for (chat in chats) {
                val turns = JSONArray()
                for (t in chat.turns) {
                    val sources = JSONArray()
                    for (s in t.sources) {
                        sources.put(JSONObject().put("o", s.origin.name).put("t", s.title).put("s", s.section).put("x", s.text).put("r", s.ref))
                    }
                    turns.put(
                        JSONObject().put("id", t.id).put("q", t.question).put("a", t.answer)
                            .put("stage", t.stage.name).put("note", t.note).put("sources", sources)
                    )
                }
                all.put(JSONObject().put("id", chat.id).put("title", chat.title).put("updated", chat.updated).put("turns", turns))
            }
            // Written beside and renamed, so a crash mid-write cannot lose the conversations.
            runCatching {
                val temp = File(app.filesDir, "chats.json.new")
                temp.writeText(all.toString())
                temp.renameTo(chatsFile())
            }
        }
    }

    private fun readChats(): List<Chat> = try {
        val all = JSONArray(chatsFile().readText())
        (0 until all.length()).map { i ->
            val c = all.getJSONObject(i)
            val turns = c.getJSONArray("turns")
            Chat(
                c.getLong("id"), c.getString("title"),
                (0 until turns.length()).map { j ->
                    val t = turns.getJSONObject(j)
                    val sources = t.getJSONArray("sources")
                    Turn(
                        t.getLong("id"), t.getString("q"), t.getString("a"),
                        (0 until sources.length()).map { k ->
                            val s = sources.getJSONObject(k)
                            Source(Origin.valueOf(s.getString("o")), s.getString("t"), s.getString("s"), s.getString("x"), s.getString("r"))
                        },
                        Stage.valueOf(t.getString("stage")), t.optString("note"),
                    )
                },
                c.getLong("updated"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private const val MODEL = "model"
    private const val MODELS = "models"
    private const val ADDED = "added"
    private const val PACKS = "packs"
    private const val ARCHIVES = "archives"
    private const val NOTES = "notes"
    private const val NOTES_NAME = "notes_name"
    private const val USE_NOTES = "use_notes"
    private const val USE_PACKS = "use_packs"
    private const val BACKEND = "backend"
    private const val METERED = "metered"
    private const val DOWNLOADS = "downloads"
    private const val GPU_TRYING = "gpu_trying"
    private const val GPU_BAD = "gpu_bad"

    private const val MAX_ARTICLE = 8 shl 20
    private const val MAX_LISTING = 16 shl 20
    private const val MAX_NOTES = 3000
    private const val MAX_NOTE_BYTES = 512L shl 10
    private val NOTE_TYPES = listOf(".md", ".txt", ".markdown")
}
