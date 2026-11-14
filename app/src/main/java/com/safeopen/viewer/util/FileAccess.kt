package com.safeopen.viewer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * All file input goes through here. Files are copied into the app's private cache
 * (never executed, never written anywhere else) and every URI is validated first.
 */
object FileAccess {

    const val MAX_OPEN_BYTES = 1024L * 1024 * 1024  // 1 GB

    class TooLarge(val size: Long) : IOException("file too large")
    class Rejected(msg: String) : IOException(msg)

    data class Local(val file: File, val name: String, val size: Long)

    fun workDir(ctx: Context): File = File(ctx.cacheDir, "open").apply { mkdirs() }

    fun newTempFile(ctx: Context): File = File(workDir(ctx), UUID.randomUUID().toString())

    fun cleanAll(ctx: Context) {
        workDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Only content:// URIs from other apps, and file:// URIs inside shared storage, are
     * accepted. This blocks the classic attack where another app asks a viewer to display
     * the viewer's *own* private files (file:///data/data/...).
     */
    fun validate(ctx: Context, uri: Uri): Uri {
        when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> {
                if (uri.authority == null) throw Rejected("no authority")
                if (uri.authority!!.startsWith(ctx.packageName)) throw Rejected("own provider")
                return uri
            }
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: throw Rejected("no path")
                val canonical = File(path).canonicalFile
                val shared = Environment.getExternalStorageDirectory().canonicalFile
                val privateDirs = listOfNotNull(ctx.dataDir, ctx.filesDir, ctx.cacheDir).map { it.canonicalPath }
                if (privateDirs.any { canonical.path.startsWith(it) }) throw Rejected("private path")
                if (!canonical.path.startsWith(shared.path) && !canonical.path.startsWith("/storage/")) throw Rejected("outside shared storage")
                return Uri.fromFile(canonical)
            }
            else -> throw Rejected("scheme")
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "file").name
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return sanitizeName(it) }
            }
        } catch (_: Exception) {}
        return sanitizeName(uri.lastPathSegment?.substringAfterLast('/') ?: "file")
    }

    fun size(ctx: Context, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "").length()
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) {}
        return -1
    }

    /** Names are only ever displayed, but strip path separators and control characters anyway. */
    fun sanitizeName(n: String): String =
        n.replace('/', '_').replace('\\', '_').filter { it >= ' ' || it == '‮' }.take(255).ifBlank { "file" }

    /** Copies [uri] into private cache. [maxBytes] < size → only the first part is copied (returns truncated=true). */
    fun copyToCache(ctx: Context, uri: Uri, maxBytes: Long = MAX_OPEN_BYTES, allowTruncate: Boolean = false): Pair<Local, Boolean> {
        val name = displayName(ctx, uri)
        val declared = size(ctx, uri)
        if (!allowTruncate && declared > maxBytes) throw TooLarge(declared)
        val out = newTempFile(ctx)
        var truncated = false
        val input = if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).inputStream()
        else ctx.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
        input.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val r = inp.read(buf)
                    if (r <= 0) break
                    if (total + r > maxBytes) {
                        if (!allowTruncate) { o.close(); out.delete(); throw TooLarge(total + r) }
                        o.write(buf, 0, (maxBytes - total).toInt())
                        truncated = true
                        break
                    }
                    o.write(buf, 0, r)
                    total += r
                }
            }
        }
        return Local(out, name, out.length()) to truncated
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }
}
