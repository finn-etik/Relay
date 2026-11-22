package dev.field.lore

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bytes held in memory. */
class MemoryBytes(private val data: ByteArray) : Bytes {
    override val size: Long get() = data.size.toLong()
    override fun read(position: Long, into: ByteArray, offset: Int, length: Int) {
        System.arraycopy(data, position.toInt(), into, offset, length)
    }
}

/**
 * Writes small ZIM archives for the tests, following the format description
 * independently of the reader: header, MIME list, path pointers, entries,
 * cluster pointers, clusters.
 */
class ZimFixture(
    private val newScheme: Boolean = true,
    /** Compresses the article cluster; null leaves it uncompressed. */
    private val compress: ((ByteArray) -> ByteArray)? = null,
    private val wideOffsets: Boolean = false,
) {
    private class Item(val ns: Char, val path: String, val title: String, val mime: Int, val body: ByteArray?, val target: String?)

    private val items = ArrayList<Item>()
    private val articleNs get() = if (newScheme) 'C' else 'A'

    fun article(path: String, title: String, html: String) = apply {
        items += Item(articleNs, path, title, 0, html.toByteArray(), null)
    }

    fun redirect(path: String, title: String, target: String) = apply {
        items += Item(articleNs, path, title, 0xFFFF, null, target)
    }

    fun metadata(name: String, value: String) = apply { items += Item('M', name, "", 1, value.toByteArray(), null) }

    fun build(): ByteArray {
        val all = ArrayList(items)
        if (newScheme) all += Item('X', "listing/titleOrdered/v1", "", 2, ByteArray(0), null)
        all.sortWith { a, b ->
            if (a.ns != b.ns) a.ns.compareTo(b.ns) else Zim.compare(a.path.toByteArray(), b.path.toByteArray())
        }
        val index = all.withIndex().associate { (i, it) -> it.ns to it.path to i }
        fun title(i: Item) = i.title.ifEmpty { i.path }
        val byTitle = all.indices.sortedWith { a, b ->
            if (all[a].ns != all[b].ns) all[a].ns.compareTo(all[b].ns)
            else Zim.compare(title(all[a]).toByteArray(), title(all[b]).toByteArray())
        }

        // Cluster 0: articles and metadata. Cluster 1: the title listing, never compressed.
        val content = all.filter { it.body != null && it.ns != 'X' }
        val blobOf = content.withIndex().associate { (i, it) -> it to i }
        val listing = ByteBuffer.allocate(4 * byTitle.size).order(ByteOrder.LITTLE_ENDIAN)
        var listed = 0
        for (i in byTitle) if (all[i].ns == articleNs) { listing.putInt(i); listed++ }
        val clusters = ArrayList<ByteArray>()
        clusters += cluster(content.map { it.body!! }, compress, wideOffsets)
        if (newScheme) clusters += cluster(listOf(listing.array().copyOf(4 * listed)), null, false)

        val mimes = "text/html\u0000text/plain\u0000application/octet-stream+zimlisting\u0000\u0000".toByteArray()
        val dirents = all.map { item ->
            val out = ByteArrayOutputStream()
            val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(item.mime.toShort()).put(0).put(item.ns.code.toByte()).putInt(0)
            if (item.target != null) {
                head.putInt(index.getValue(item.ns to item.target))
                out.write(head.array(), 0, 12)
            } else {
                if (item.ns == 'X') head.putInt(1).putInt(0) else head.putInt(0).putInt(blobOf.getValue(item))
                out.write(head.array(), 0, 16)
            }
            out.write(item.path.toByteArray()); out.write(0)
            out.write(item.title.toByteArray()); out.write(0)
            out.toByteArray()
        }

        val n = all.size
        val mimeAt = 80L
        val pathPtrAt = mimeAt + mimes.size
        val titlePtrAt = pathPtrAt + 8L * n
        val direntAt = titlePtrAt + 4L * n
        val clusterPtrAt = direntAt + dirents.sumOf { it.size }
        val clusterAt = clusterPtrAt + 8L * clusters.size
        val checksumAt = clusterAt + clusters.sumOf { it.size }

        val out = ByteBuffer.allocate((checksumAt + 16).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x044D495A).putShort(if (newScheme) 6 else 5).putShort(if (newScheme) 1 else 0)
        out.put(ByteArray(16))
        out.putInt(n).putInt(clusters.size)
        out.putLong(pathPtrAt).putLong(if (newScheme) -1L else titlePtrAt).putLong(clusterPtrAt).putLong(mimeAt)
        out.putInt(-1).putInt(-1).putLong(checksumAt)
        out.put(mimes)
        var at = direntAt
        for (d in dirents) { out.putLong(at); at += d.size }
        for (i in byTitle) out.putInt(i)
        for (d in dirents) out.put(d)
        at = clusterAt
        for (c in clusters) { out.putLong(at); at += c.size }
        for (c in clusters) out.put(c)
        return out.array()
    }

    private fun cluster(blobs: List<ByteArray>, compress: ((ByteArray) -> ByteArray)?, wide: Boolean): ByteArray {
        val width = if (wide) 8 else 4
        val data = ByteBuffer.allocate(width * (blobs.size + 1) + blobs.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        var at = width.toLong() * (blobs.size + 1)
        for (i in 0..blobs.size) {
            if (wide) data.putLong(at) else data.putInt(at.toInt())
            if (i < blobs.size) at += blobs[i].size
        }
        for (b in blobs) data.put(b)
        val info = (if (compress != null) 5 else 1) or (if (wide) 0x10 else 0)
        val body = compress?.invoke(data.array()) ?: data.array()
        // Trailing bytes after a compressed cluster stand in for the next cluster: the reader must not need the length.
        return byteArrayOf(info.toByte()) + body
    }
}
