package dev.field.lore

import java.io.IOException
import java.io.InputStream

/** Random access to a file's bytes. [read] fills the whole range or throws. */
interface Bytes {
    val size: Long
    fun read(position: Long, into: ByteArray, offset: Int = 0, length: Int = into.size)
}

/** Wraps a compressed stream in its decompressor (Zstandard, on the device). */
fun interface Inflater {
    fun open(compressed: InputStream): InputStream
}

class ZimException(message: String) : IOException(message)

/**
 * Reader for ZIM archives, the offline format Kiwix publishes Wikipedia and
 * Wikibooks in (https://wiki.openzim.org/wiki/ZIM_file_format). Written
 * against the format description, so the app does not carry libzim (GPL).
 *
 * It does the two things LORE needs: find an article by its exact title
 * (binary search in the archive's title listing) and return its content.
 * Zstandard clusters only; that is every archive Kiwix has built since 2021.
 *
 * Safe to call from several threads.
 */
class Zim(private val bytes: Bytes, private val zstd: Inflater) {

    data class Entry(
        val index: Int,
        val namespace: Char,
        val path: String,
        val title: String,
        /** Null for a redirect. */
        val mime: String?,
        /** Index of the entry this one redirects to, or -1. */
        val redirect: Int,
        val cluster: Int,
        val blob: Int,
    ) {
        val isRedirect get() = redirect >= 0
    }

    val entryCount: Int
    private val clusterCount: Int
    private val pathPointers: Long
    private val legacyTitlePointers: Long
    private val clusterPointers: Long
    private val mimes: List<String>

    /** Since ZIM 6.1 all content lives in namespace C; before, articles were in A. */
    val articleNamespace: Char
    private val newScheme: Boolean

    init {
        if (bytes.size < HEADER) throw ZimException("Not a ZIM file")
        val h = ByteArray(HEADER).also { bytes.read(0, it) }
        if (u32(h, 0) != MAGIC) throw ZimException("Not a ZIM file")
        val major = u16(h, 4)
        val minor = u16(h, 6)
        if (major != 5 && major != 6) throw ZimException("Unsupported ZIM version $major")
        newScheme = major == 6 && minor >= 1
        articleNamespace = if (newScheme) 'C' else 'A'
        entryCount = int(u32(h, 24))
        clusterCount = int(u32(h, 28))
        pathPointers = u64(h, 32)
        legacyTitlePointers = u64(h, 40)
        clusterPointers = u64(h, 48)
        mimes = readMimes(u64(h, 56))
    }

    // ------------------------------------------------------------ entries

    fun entry(index: Int): Entry {
        if (index < 0 || index >= entryCount) throw ZimException("No entry $index")
        val at = u64(read(pathPointers + 8L * index, 8), 0)
        var size = 512
        while (true) {
            val chunk = read(at, minOf(size.toLong(), bytes.size - at).toInt())
            parse(index, chunk)?.let { return it }
            if (chunk.size < size || size > 1 shl 16) throw ZimException("Broken entry $index")
            size *= 4
        }
    }

    /** Null = the strings run past the end of [d]: read more. */
    private fun parse(index: Int, d: ByteArray): Entry? {
        if (d.size < 12) return null
        val mime = u16(d, 0)
        val namespace = (d[3].toInt() and 0xFF).toChar()
        val redirect = mime == REDIRECT
        val strings = if (redirect) 12 else 16
        val pathEnd = zero(d, strings) ?: return null
        val titleEnd = zero(d, pathEnd + 1) ?: return null
        val path = String(d, strings, pathEnd - strings, Charsets.UTF_8)
        val title = if (titleEnd == pathEnd + 1) path else String(d, pathEnd + 1, titleEnd - pathEnd - 1, Charsets.UTF_8)
        return if (redirect) {
            Entry(index, namespace, path, title, null, int(u32(d, 8)), -1, -1)
        } else {
            // Link targets and deleted entries (obsolete kinds) carry no content.
            val kind = if (mime >= 0xFFFD) null else mimes.getOrNull(mime)
            Entry(index, namespace, path, title, kind ?: "", -1, int(u32(d, 8)), int(u32(d, 12)))
        }
    }

    /** Follows redirects to the entry that holds content. Null if the chain is broken or loops. */
    fun resolve(entry: Entry): Entry? {
        var e = entry
        repeat(8) {
            if (!e.isRedirect) return e
            if (e.redirect >= entryCount) return null
            e = entry(e.redirect)
        }
        return null
    }

    fun byPath(namespace: Char, path: String): Entry? {
        val key = path.toByteArray(Charsets.UTF_8)
        val at = lowerBound(entryCount) { i ->
            val e = entry(i)
            val ns = e.namespace.compareTo(namespace)
            if (ns != 0) ns else compare(e.path.toByteArray(Charsets.UTF_8), key)
        }
        if (at >= entryCount) return null
        return entry(at).takeIf { it.namespace == namespace && it.path == path }
    }

    // ------------------------------------------------------------- titles

    private class Titles(val count: Int, val indexAt: (Int) -> Int)

    /**
     * The title-ordered list of entries. New archives ship one of articles
     * only (`X/listing/titleOrdered/v1`); old ones list every entry.
     */
    private val titles: Titles by lazy {
        val listing = if (newScheme) byPath('X', "listing/titleOrdered/v1") ?: byPath('X', "listing/titleOrdered/v0") else null
        val blob = listing?.takeIf { !it.isRedirect }?.let { locate(it) }
        when {
            blob != null && blob.data == null ->
                Titles((blob.length / 4).toInt()) { i -> int(u32(read(blob.position + 4L * i, 4), 0)) }
            blob?.data != null ->
                Titles((blob.length / 4).toInt()) { i -> int(u32(blob.data, blob.offset + 4 * i)) }
            legacyTitlePointers != NONE && legacyTitlePointers < bytes.size ->
                Titles(entryCount) { i -> int(u32(read(legacyTitlePointers + 4L * i, 4), 0)) }
            else -> Titles(0) { 0 }
        }
    }

    /** Number of entries that can be found by title (articles, in new archives). */
    val titleCount: Int get() = titles.count

    private fun titleLowerBound(key: ByteArray): Int = lowerBound(titles.count) { i ->
        val e = entry(titles.indexAt(i))
        val ns = e.namespace.compareTo(articleNamespace)
        if (ns != 0) ns else compare(e.title.toByteArray(Charsets.UTF_8), key)
    }

    /** The article with exactly this title (case-sensitive, as stored), or null. */
    fun byTitle(title: String): Entry? {
        val at = titleLowerBound(title.toByteArray(Charsets.UTF_8))
        if (at >= titles.count) return null
        return entry(titles.indexAt(at)).takeIf { it.namespace == articleNamespace && it.title == title }
    }

    /** Up to [limit] articles whose title starts with [prefix], in title order. */
    fun titlesFrom(prefix: String, limit: Int): List<Entry> {
        val out = ArrayList<Entry>()
        var i = titleLowerBound(prefix.toByteArray(Charsets.UTF_8))
        while (i < titles.count && out.size < limit) {
            val e = entry(titles.indexAt(i++))
            if (e.namespace != articleNamespace || !e.title.startsWith(prefix)) break
            out += e
        }
        return out
    }

    /** A metadata value (`Title`, `Date`, `Language`, ...), or null. */
    fun metadata(name: String): String? {
        val e = (if (newScheme) byPath('M', name) else byPath('M', name) ?: byPath('-', name)) ?: return null
        val target = resolve(e) ?: return null
        return runCatching { String(content(target, 1 shl 16), Charsets.UTF_8).trim() }.getOrNull()
    }

    // ------------------------------------------------------------ content

    /** Where a blob is: directly in the file ([data] null) or inside decompressed cluster [data]. */
    private class Blob(val position: Long, val length: Long, val data: ByteArray?, val offset: Int)

    private var cachedCluster = -1
    private var cachedData: ByteArray? = null

    /** An entry's content. Throws if it is longer than [limit] bytes. */
    fun content(entry: Entry, limit: Int = 32 shl 20): ByteArray {
        val b = locate(entry)
        if (b.length > limit) throw ZimException("Entry too large")
        return if (b.data != null) b.data.copyOfRange(b.offset, b.offset + b.length.toInt())
        else read(b.position, b.length.toInt())
    }

    private fun locate(entry: Entry): Blob {
        if (entry.isRedirect || entry.cluster < 0 || entry.cluster >= clusterCount) throw ZimException("No content")
        val at = u64(read(clusterPointers + 8L * entry.cluster, 8), 0)
        val info = read(at, 1)[0].toInt()
        val width = if (info and 0x10 != 0) 8 else 4
        return when (val compression = info and 0x0F) {
            0, 1 -> {
                val base = at + 1
                val first = offset(read(base, width), 0, width)
                checkBlob(entry, first, width)
                val pair = read(base + width.toLong() * entry.blob, width * 2)
                val from = offset(pair, 0, width)
                val to = offset(pair, width, width)
                if (to < from || base + to > bytes.size) throw ZimException("Broken cluster")
                Blob(base + from, to - from, null, 0)
            }
            5 -> {
                val data = cluster(entry.cluster, at + 1, width)
                val first = offset(data, 0, width)
                checkBlob(entry, first, width)
                val from = offset(data, width * entry.blob, width)
                val to = offset(data, width * (entry.blob + 1), width)
                if (to < from || to > data.size) throw ZimException("Broken cluster")
                Blob(0, to - from, data, from.toInt())
            }
            else -> throw ZimException(if (compression == 4) "This archive uses XZ compression (built before 2021); get a newer one" else "Unknown compression $compression")
        }
    }

    private fun checkBlob(entry: Entry, firstOffset: Long, width: Int) {
        val blobs = firstOffset / width - 1
        if (entry.blob < 0 || entry.blob >= blobs) throw ZimException("No blob ${entry.blob}")
    }

    /**
     * A decompressed cluster. The last one is kept: neighbouring articles
     * share clusters.
     *
     * The archive does not say where a compressed cluster ends, and the next
     * cluster follows it directly. So this reads exactly as many bytes as
     * the cluster's own offset table says it holds and never asks the
     * decompressor for more (it would try to decode the next cluster's bytes
     * as a second frame).
     */
    @Synchronized
    private fun cluster(number: Int, dataAt: Long, width: Int): ByteArray {
        cachedData?.let { if (cachedCluster == number) return it }
        cachedData = null // free the old one before allocating the next
        val data = zstd.open(Stream(dataAt)).use { input ->
            val head = ByteArray(width).also { fill(input, it, 0, width) }
            val table = offset(head, 0, width)
            if (table < width || table % width != 0L || table > MAX_CLUSTER) throw ZimException("Broken cluster")
            val offsets = head.copyOf(table.toInt()).also { fill(input, it, width, table.toInt() - width) }
            val total = offset(offsets, table.toInt() - width, width)
            if (total < table || total > MAX_CLUSTER) throw ZimException("Cluster too large")
            offsets.copyOf(total.toInt()).also { fill(input, it, table.toInt(), (total - table).toInt()) }
        }
        cachedCluster = number
        cachedData = data
        return data
    }

    private fun fill(input: InputStream, into: ByteArray, offset: Int, length: Int) {
        var done = 0
        var idle = 0
        while (done < length) {
            val n = input.read(into, offset + done, length - done)
            if (n < 0 || (n == 0 && ++idle > 100)) throw ZimException("Cluster ends early")
            done += n
        }
    }

    /** The file from [position] on, as a stream (a decompressor stops at the end of its frame). */
    private inner class Stream(private var position: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun available(): Int = minOf(bytes.size - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len.toLong(), bytes.size - position).toInt()
            if (n <= 0) return if (len == 0) 0 else -1
            bytes.read(position, b, off, n)
            position += n
            return n
        }
    }

    // ------------------------------------------------------------ helpers

    private fun readMimes(at: Long): List<String> {
        if (at <= 0 || at >= bytes.size) throw ZimException("Broken header")
        val data = read(at, minOf(8192L, bytes.size - at).toInt())
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val end = zero(data, from) ?: break
            if (end == from) break
            out += String(data, from, end - from, Charsets.UTF_8)
            from = end + 1
        }
        return out
    }

    private fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) throw ZimException("Read past the end of the file")
        return ByteArray(length).also { bytes.read(position, it) }
    }

    private fun offset(d: ByteArray, at: Int, width: Int): Long {
        if (at < 0 || at + width > d.size) throw ZimException("Broken cluster")
        return if (width == 8) u64(d, at) else u32(d, at)
    }

    private fun int(value: Long): Int = if (value > Int.MAX_VALUE) throw ZimException("Archive too large") else value.toInt()

    companion object {
        private const val HEADER = 80
        private const val MAGIC = 0x044D495AL
        private const val REDIRECT = 0xFFFF
        private const val NONE = -1L // 0xFFFF_FFFF_FFFF_FFFF: "no title pointer list"
        private const val MAX_CLUSTER = 256 shl 20

        internal fun u16(d: ByteArray, at: Int): Int = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

        internal fun u32(d: ByteArray, at: Int): Long =
            (d[at].toLong() and 0xFF) or ((d[at + 1].toLong() and 0xFF) shl 8) or
                ((d[at + 2].toLong() and 0xFF) shl 16) or ((d[at + 3].toLong() and 0xFF) shl 24)

        internal fun u64(d: ByteArray, at: Int): Long = u32(d, at) or (u32(d, at + 4) shl 32)

        private fun zero(d: ByteArray, from: Int): Int? {
            for (i in from until d.size) if (d[i].toInt() == 0) return i
            return null
        }

        /** Byte order, bytes unsigned: the order ZIM sorts paths and titles in. */
        internal fun compare(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            return a.size - b.size
        }

        /** First index in 0 until [count] whose [order] (entry compared to the key) is not negative. */
        private inline fun lowerBound(count: Int, order: (Int) -> Int): Int {
            var low = 0
            var high = count
            while (low < high) {
                val mid = (low + high) ushr 1
                if (order(mid) < 0) low = mid + 1 else high = mid
            }
            return low
        }
    }
}
