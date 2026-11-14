package com.safeopen.viewer.render

import java.io.File

/** EPUB e-books → one scrollable HTML page. Scripts, frames, forms and remote resources are stripped. */
object EpubRenderer {

    private const val MAX_CHAPTERS = 600

    fun render(file: File): String = ZipDoc(file).use { zip ->
        val container = zip.read("META-INF/container.xml")?.let { Node.parse(it) }
        val opfPath = container?.descendants("rootfile")?.firstOrNull()?.attr("full-path") ?: "OEBPS/content.opf"
        val opf = Node.parse(zip.read(opfPath) ?: throw IllegalStateException("opf missing"))
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        opf.descendants("item").forEach { item ->
            val id = item.attr("id"); val href = item.attr("href")
            if (id != null && href != null) manifest[id] = ZipDoc.resolve(base, decode(href))
        }
        val title = opf.descendants("title").firstOrNull()?.allText()
        val sb = StringBuilder()
        title?.let { sb.append("<h1 dir=\"auto\">").append(Html.esc(it)).append("</h1>") }
        val spine = opf.descendants("itemref").mapNotNull { manifest[it.attr("idref")] }
        for (path in spine.take(MAX_CHAPTERS)) {
            val html = zip.text(path, 8 * 1024 * 1024) ?: continue
            sb.append("<section dir=\"auto\">").append(sanitize(html, path.substringBeforeLast('/', ""), zip)).append("</section><hr>")
            if (sb.length > Limits.OUTPUT_CHARS) break
        }
        Html.page("<div class=\"paper\">$sb</div>", "section{margin-bottom:12px} hr{border:0;border-top:1px solid #ddd}")
    }

    private fun decode(s: String) = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private val BODY = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DANGEROUS_BLOCKS = Regex(
        "<(script|iframe|object|embed|form|style|link|meta|base|audio|video|source|frame|frameset|applet)\\b[^>]*>(.*?</\\1\\s*>)?",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val EVENT_ATTR = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val JS_URL = Regex("(href|src|xlink:href)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("(<img\\b[^>]*?\\ssrc\\s*=\\s*|<image\\b[^>]*?\\s(?:xlink:)?href\\s*=\\s*)([\"'])([^\"']+)\\2", RegexOption.IGNORE_CASE)

    fun sanitize(html: String, dir: String, zip: ZipDoc?): String {
        var body = BODY.find(html)?.groupValues?.get(1) ?: html
        body = DANGEROUS_BLOCKS.replace(body, "")
        body = EVENT_ATTR.replace(body, "")
        body = JS_URL.replace(body, "")
        body = IMG_SRC.replace(body) { m ->
            val src = m.groupValues[3]
            val uri = if (zip != null && !src.contains(":")) zip.imageUri(ZipDoc.resolve(dir, decode(src.substringBefore('#')))) else null
            m.groupValues[1] + m.groupValues[2] + (uri ?: "") + m.groupValues[2]
        }
        return body
    }
}
