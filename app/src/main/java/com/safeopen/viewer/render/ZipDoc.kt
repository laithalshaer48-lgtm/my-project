package com.safeopen.viewer.render

import com.safeopen.viewer.util.Bytes
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Creates hardened XML pull parsers (no DTD processing → no XXE / entity expansion). */
object XmlFactory {
    @Volatile
    var provider: () -> XmlPullParser = { android.util.Xml.newPullParser() }

    fun parser(data: ByteArray): XmlPullParser {
        val p = provider()
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {}
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) } catch (_: Exception) {}
        p.setInput(ByteArrayInputStream(data), null)
        return p
    }
}

/** Limits that keep hostile documents from exhausting memory. */
object Limits {
    const val XML_PART = 48 * 1024 * 1024
    const val IMAGE = 12 * 1024 * 1024
    const val IMAGES_TOTAL = 40L * 1024 * 1024
    const val OUTPUT_CHARS = 24 * 1024 * 1024
}

class TooLargeException : Exception("output limit")

/** Read-only access to the parts of a ZIP-based document, with size caps. */
class ZipDoc(file: File) : Closeable {
    private val zip = ZipFile(file)
    private var imageBudget = Limits.IMAGES_TOTAL

    fun has(path: String) = zip.getEntry(path) != null

    fun names(): List<String> = zip.entries().asSequence().map { it.name }.toList()

    fun read(path: String, max: Int = Limits.XML_PART): ByteArray? {
        val e = zip.getEntry(path.removePrefix("/")) ?: return null
        if (e.isDirectory) return null
        return zip.getInputStream(e).use { Bytes.readCapped(it, max) }
    }

    fun text(path: String, max: Int = Limits.XML_PART): String? = read(path, max)?.toString(Charsets.UTF_8)

    /** Returns a data: URI for an image part, or null if missing / unsupported / over budget. */
    fun imageUri(path: String): String? {
        if (imageBudget <= 0) return null
        val data = read(path, Limits.IMAGE) ?: return null
        imageBudget -= data.size
        return Html.dataUri(path, data)
    }

    /** Relationship id → resolved part path, for an OOXML part (e.g. "word/document.xml"). */
    fun rels(partPath: String): Map<String, Rel> {
        val dir = partPath.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + partPath.substringAfterLast('/') + ".rels"
        val data = read(relsPath, 4 * 1024 * 1024) ?: return emptyMap()
        val out = HashMap<String, Rel>()
        val p = XmlFactory.parser(data)
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && p.name.endsWith("Relationship")) {
                val id = p.getAttributeValue(null, "Id") ?: continue
                val target = p.getAttributeValue(null, "Target") ?: continue
                val external = p.getAttributeValue(null, "TargetMode") == "External"
                val type = p.getAttributeValue(null, "Type") ?: ""
                out[id] = Rel(if (external) target else resolve(dir, target), type, external)
            }
        }
        return out
    }

    data class Rel(val target: String, val type: String, val external: Boolean)

    override fun close() = zip.close()

    companion object {
        /** Resolves a relative part name like "../media/image1.png" against a directory. */
        fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}

/** Convenience: attribute by local name, ignoring the namespace prefix. */
fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (n == localName || n.endsWith(":$localName")) return getAttributeValue(i)
    }
    return null
}

val XmlPullParser.local: String get() = name.substringAfter(':')
