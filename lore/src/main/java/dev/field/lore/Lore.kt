package dev.field.lore

/**
 * A language model LORE can run. All are `.litertlm` files for Google's
 * LiteRT-LM runtime, published ungated under Apache 2.0, so the app can
 * fetch them without an account.
 */
data class Model(
    val id: String,
    val name: String,
    val caption: String,
    val url: String,
    /** Context window to ask the runtime for; null = whatever the file was built with. */
    val maxTokens: Int?,
    /** Characters of quoted sources per question: what the context has room for. */
    val sourceChars: Int,
    /** Characters of earlier conversation resent with each question. */
    val historyChars: Int,
) {
    val file: String get() = url.substringAfterLast('/')
}

/** A downloadable knowledge pack: the newest Kiwix archive whose name starts with [prefix]. */
data class Pack(val id: String, val name: String, val caption: String, val dir: String, val prefix: String) {
    val listing: String get() = "${Catalog.KIWIX}$dir/"
}

/**
 * A pack as currently published. [size] is the listing's own wording
 * ("43G"), or empty; [bytes] the exact length once the server was asked.
 */
data class Remote(val file: String, val url: String, val size: String, val bytes: Long = -1) {
    val sizeLabel: String get() = if (bytes > 0) Format.bytes(bytes) else size
}

object Catalog {
    const val KIWIX = "https://download.kiwix.org/zim/"
    private const val HF = "https://huggingface.co/litert-community"

    /**
     * Default first. Gemma 4 E2B (about 2 billion effective parameters) is
     * the pick: the fastest model of its quality on phones, and the one the
     * runtime is tuned for. Qwen3 0.6B is the fallback for phones short on
     * memory; its file is built with a 1280-token context, hence the small
     * budgets, and in CI it answered poorly (see docs/REVIEW.md). Gemma 4 E4B is the ceiling the brief allows (4 billion).
     */
    val MODELS = listOf(
        Model(
            "gemma-4-e2b", "GEMMA 4 E2B", "2.6 GB · RECOMMENDED",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            maxTokens = 4096, sourceChars = 5000, historyChars = 2000,
        ),
        Model(
            "qwen3-0.6b", "QWEN3 0.6B", "0.35 GB · WEAK: ONLY IF NOTHING ELSE FITS",
            "$HF/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            maxTokens = null, sourceChars = 1600, historyChars = 400,
        ),
        Model(
            "gemma-4-e4b", "GEMMA 4 E4B", "3.7 GB · MOST CAPABLE, NEEDS 12 GB RAM",
            "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            maxTokens = 4096, sourceChars = 6000, historyChars = 2400,
        ),
    )

    val PACKS = listOf(
        Pack("wikipedia", "WIKIPEDIA", "EVERY ARTICLE IN FULL, NO PICTURES", "wikipedia", "wikipedia_en_all_nopic_"),
        Pack("wikipedia-mini", "WIKIPEDIA · INTRODUCTIONS", "THE OPENING OF EVERY ARTICLE", "wikipedia", "wikipedia_en_all_mini_"),
        Pack("wikipedia-top", "WIKIPEDIA · TOP ARTICLES", "THE MOST READ ARTICLES IN FULL", "wikipedia", "wikipedia_en_top_nopic_"),
        Pack("wikibooks", "WIKIBOOKS", "OPEN TEXTBOOKS AND MANUALS", "wikibooks", "wikibooks_en_all_nopic_"),
    )

    fun model(id: String): Model = MODELS.firstOrNull { it.id == id } ?: MODELS.first()
    fun pack(id: String): Pack? = PACKS.firstOrNull { it.id == id }
}

object Packs {
    /**
     * The newest archive for [pack] in a Kiwix directory listing (an HTML
     * index page). Archives are named `<prefix><yyyy>-<mm>.zim`, so the
     * newest is the greatest name.
     */
    fun latest(listing: String, pack: Pack): Remote? {
        val name = Regex("href=\"(" + Regex.escape(pack.prefix) + "\\d{4}-\\d{2}\\.zim)\"")
        val match = name.findAll(listing).maxByOrNull { it.groupValues[1] } ?: return null
        val file = match.groupValues[1]
        // The rest of the row: "</a>  2025-08-15 10:12   43G" (the date format varies between mirrors).
        val row = listing.substring(match.range.last, minOf(listing.length, match.range.last + 200)).substringBefore('\n')
        val size = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s|</td>|<td[^>]*>)+([\\d.]+\\s?[KMGT]i?B?)\\b").find(row)?.groupValues?.get(1).orEmpty()
        return Remote(file, pack.listing + file, size.replace(" ", ""))
    }

    /** The name a downloaded pack is stored under, whatever its date. */
    fun fileOf(pack: Pack): String = "${pack.id}.zim"
}

/** How far the answer to a question has come. */
enum class Stage { SEARCHING, WRITING, DONE, STOPPED, FAILED }

data class Turn(
    val id: Long,
    val question: String,
    val answer: String = "",
    val sources: List<Source> = emptyList(),
    val stage: Stage = Stage.SEARCHING,
    /** Why it failed, when it did. */
    val note: String = "",
) {
    val busy: Boolean get() = stage == Stage.SEARCHING || stage == Stage.WRITING
}

data class Chat(val id: Long, val title: String, val turns: List<Turn>, val updated: Long)

/** The conversations, newest first. Every change returns a new list. */
object Chats {
    const val KEEP = 100

    /** Adds [question] to chat [chatId], starting that chat if it does not exist yet. */
    fun ask(chats: List<Chat>, chatId: Long, turnId: Long, question: String, now: Long): List<Chat> {
        val turn = Turn(turnId, question.trim())
        val existing = chats.firstOrNull { it.id == chatId }
        val chat = existing?.copy(turns = existing.turns + turn, updated = now)
            ?: Chat(chatId, Ask.chatTitle(question), listOf(turn), now)
        return (listOf(chat) + chats.filter { it.id != chatId }).take(KEEP)
    }

    fun update(chats: List<Chat>, chatId: Long, turnId: Long, change: (Turn) -> Turn): List<Chat> = chats.map { chat ->
        if (chat.id != chatId) chat else chat.copy(turns = chat.turns.map { if (it.id == turnId) change(it) else it })
    }

    fun remove(chats: List<Chat>, chatId: Long): List<Chat> = chats.filter { it.id != chatId }

    /** Removes a chat's last turn (to ask it again); a chat left empty goes too. */
    fun dropLast(chats: List<Chat>, chatId: Long): List<Chat> = chats.mapNotNull { chat ->
        if (chat.id != chatId) chat else chat.copy(turns = chat.turns.dropLast(1)).takeIf { it.turns.isNotEmpty() }
    }

    /** After a restart nothing is being written any more: unfinished turns become stopped ones. */
    fun settle(chats: List<Chat>): List<Chat> = chats.map { chat ->
        if (chat.turns.none { it.busy }) chat
        else chat.copy(turns = chat.turns.map { if (it.busy) it.copy(stage = Stage.STOPPED) else it })
    }

    /** The exchanges before turn [turnId], as (question, answer) pairs for [Ask.history]. */
    fun before(chat: Chat, turnId: Long): List<Pair<String, String>> =
        chat.turns.takeWhile { it.id != turnId }.filter { it.stage == Stage.DONE || it.stage == Stage.STOPPED }.map { it.question to it.answer }
}

object Format {
    /** Sizes in decimal units (1 GB = 1000 MB), as the phone's own storage screen counts them. */
    fun bytes(n: Long): String = when {
        n >= 1_000_000_000L -> oneDecimal(n / 1e9) + " GB"
        n >= 1_000_000L -> "${n / 1_000_000L} MB"
        n >= 1_000L -> "${n / 1_000L} KB"
        else -> "$n B"
    }

    private fun oneDecimal(value: Double): String {
        val tenths = Math.round(value * 10)
        return "${tenths / 10}.${tenths % 10}"
    }

    fun percent(done: Long, total: Long): String = if (total <= 0) "…" else "${(done * 100 / total).coerceIn(0, 100)}%"

    fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()
}
