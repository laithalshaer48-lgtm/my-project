package com.safeopen.viewer.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Small, allocation-friendly byte helpers used by the scanner and renderers. */
object Bytes {

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    fun utf16le(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)

    fun startsWith(data: ByteArray, prefix: ByteArray, offset: Int = 0): Boolean {
        if (data.size < offset + prefix.size) return false
        for (i in prefix.indices) if (data[offset + i] != prefix[i]) return false
        return true
    }

    private fun lower(b: Byte): Int {
        val c = b.toInt() and 0xFF
        return if (c in 'A'.code..'Z'.code) c + 32 else c
    }

    /** Index of [needle] in [hay] within [from, to); optionally ASCII case-insensitive. */
    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0, to: Int = hay.size, ignoreCase: Boolean = false): Int {
        if (needle.isEmpty()) return from
        val end = minOf(to, hay.size) - needle.size
        val first = if (ignoreCase) lower(needle[0]) else needle[0].toInt() and 0xFF
        var i = maxOf(0, from)
        while (i <= end) {
            val h = if (ignoreCase) lower(hay[i]) else hay[i].toInt() and 0xFF
            if (h == first) {
                var j = 1
                while (j < needle.size) {
                    val a = if (ignoreCase) lower(hay[i + j]) else hay[i + j].toInt() and 0xFF
                    val b = if (ignoreCase) lower(needle[j]) else needle[j].toInt() and 0xFF
                    if (a != b) break
                    j++
                }
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    fun contains(hay: ByteArray, needle: String, ignoreCase: Boolean = false, len: Int = hay.size): Boolean =
        indexOf(hay, ascii(needle), 0, len, ignoreCase) >= 0

    fun count(hay: ByteArray, needle: ByteArray, len: Int = hay.size, ignoreCase: Boolean = false, limit: Int = 10_000): Int {
        var n = 0
        var i = indexOf(hay, needle, 0, len, ignoreCase)
        while (i >= 0 && n < limit) {
            n++
            i = indexOf(hay, needle, i + needle.size, len, ignoreCase)
        }
        return n
    }

    /** Reads at most [max] bytes from the start of [file]. */
    fun readHead(file: File, max: Int): ByteArray {
        val n = minOf(file.length(), max.toLong()).toInt()
        val buf = ByteArray(n)
        file.inputStream().use { readFully(it, buf, n) }
        return buf
    }

    /** Reads up to [max] bytes of a stream; returns the bytes actually read. */
    fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val r = input.read(buf, 0, minOf(buf.size, max - total))
            if (r <= 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    fun readFully(input: InputStream, buf: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if the sample looks like human-readable text (UTF-8 / single-byte encodings). */
    fun looksLikeText(sample: ByteArray, len: Int = sample.size): Boolean {
        if (len == 0) return true
        var control = 0
        for (i in 0 until len) {
            val c = sample[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x09 || (c in 0x0E..0x1F && c != 0x1B)) control++
        }
        return control * 100 / len < 2
    }
}
