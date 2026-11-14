package com.safeopen.viewer.security

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a ZIP central directory by hand. We do this ourselves (instead of trusting
 * java.util.zip) so that we can see the raw entry names (path traversal), the
 * encryption flag and the *declared* sizes (zip bombs) of hostile archives that
 * the platform parser would reject or silently "fix".
 */
object ZipInspector {

    data class Entry(
        val name: String,
        val compressedSize: Long,
        val size: Long,
        val method: Int,
        val encrypted: Boolean
    ) {
        val isDirectory get() = name.endsWith("/") || name.endsWith("\\")
    }

    class Listing(val entries: List<Entry>, val declaredCount: Long, val truncated: Boolean)

    class NotAZipException(msg: String) : Exception(msg)

    private const val EOCD = 0x06054b50
    private const val EOCD64 = 0x06064b50
    private const val EOCD64_LOCATOR = 0x07064b50
    private const val CDH = 0x02014b50
    private const val MAX_CD_BYTES = 64L * 1024 * 1024

    fun list(file: File, maxEntries: Int): Listing {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 22) throw NotAZipException("too small")
            val tailLen = minOf(len, 22L + 65535L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            var eocdPos = -1
            for (i in tailLen - 22 downTo 0) {
                if (tb.getInt(i) == EOCD) { eocdPos = i; break }
            }
            if (eocdPos < 0) throw NotAZipException("no end of central directory")

            var count = (tb.getShort(eocdPos + 10).toInt() and 0xFFFF).toLong()
            var cdSize = tb.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
            var cdOffset = tb.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL

            // ZIP64
            if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val locPos = eocdPos - 20
                if (locPos >= 0 && tb.getInt(locPos) == EOCD64_LOCATOR) {
                    val eocd64Off = tb.getLong(locPos + 8)
                    if (eocd64Off in 0 until len - 56) {
                        val b = ByteArray(56)
                        raf.seek(eocd64Off)
                        raf.readFully(b)
                        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                        if (bb.getInt(0) == EOCD64) {
                            count = bb.getLong(32)
                            cdSize = bb.getLong(40)
                            cdOffset = bb.getLong(48)
                        }
                    }
                }
            }
            if (cdOffset < 0 || cdOffset > len) throw NotAZipException("bad central directory offset")
            val readSize = minOf(cdSize, len - cdOffset, MAX_CD_BYTES).toInt()
            val cd = ByteArray(readSize)
            raf.seek(cdOffset)
            raf.readFully(cd)
            val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

            val out = ArrayList<Entry>()
            var p = 0
            var truncated = false
            while (p + 46 <= readSize) {
                if (b.getInt(p) != CDH) break
                if (out.size >= maxEntries) { truncated = true; break }
                val flags = b.getShort(p + 8).toInt() and 0xFFFF
                val method = b.getShort(p + 10).toInt() and 0xFFFF
                var comp = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
                var size = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
                val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
                val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
                val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
                if (p + 46 + nameLen > readSize) break
                val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
                val name = String(cd, p + 46, nameLen, charset)

                // ZIP64 extended sizes
                var e = p + 46 + nameLen
                val eEnd = minOf(e + extraLen, readSize)
                while (e + 4 <= eEnd) {
                    val id = b.getShort(e).toInt() and 0xFFFF
                    val sz = b.getShort(e + 2).toInt() and 0xFFFF
                    if (id == 0x0001) {
                        var q = e + 4
                        if (size == 0xFFFFFFFFL && q + 8 <= eEnd) { size = b.getLong(q); q += 8 }
                        if (comp == 0xFFFFFFFFL && q + 8 <= eEnd) { comp = b.getLong(q) }
                    }
                    e += 4 + sz
                }
                out += Entry(name, comp, size, method, encrypted = flags and 0x1 != 0)
                p += 46 + nameLen + extraLen + commentLen
            }
            if (out.size < count && !truncated && out.size >= maxEntries) truncated = true
            return Listing(out, count, truncated)
        }
    }

    /** True for names that try to escape the extraction folder. */
    fun isTraversal(name: String): Boolean {
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return true
        if (n.length >= 2 && n[1] == ':' && n[0].isLetter()) return true
        return n.split('/').any { it == ".." }
    }
}
