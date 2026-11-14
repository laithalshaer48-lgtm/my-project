package com.safeopen.viewer.render

import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Word (.docx / .docm / .dotx) → HTML. Macros are never executed — they are simply ignored.
 * Supports headings, bold/italic/underline/colour/size, alignment, right-to-left, lists,
 * tables (with merged columns), line/page breaks and embedded pictures.
 */
object DocxRenderer {

    fun render(file: File): String = ZipDoc(file).use { zip ->
        val docPath = findMainPart(zip)
        val xml = zip.read(docPath) ?: throw IllegalStateException("document part missing")
        val rels = zip.rels(docPath)
        val styles = readStyles(zip)
        val body = convert(xml, rels, styles, zip)
        Html.page("<div class=\"paper\">$body</div>", CSS)
    }

    private const val CSS = """
        .paper { font-family: 'Calibri', 'Arial', sans-serif; }
        table { width: 100%; }
        li { margin: 0 0 .3em; }
        .pb { border: 0; border-top: 2px dashed #c8ccd4; margin: 18px 0; }
    """

    private fun findMainPart(zip: ZipDoc): String {
        val rootRels = zip.rels("")
        rootRels.values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.let { return it.target }
        return "word/document.xml"
    }

    /** styleId → heading level (1..6) or 0 for the "Title" style; list styles are stored as -1. */
    private fun readStyles(zip: ZipDoc): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (i in 1..6) out["Heading$i"] = i
        out["Title"] = 0
        val data = zip.read("word/styles.xml") ?: return out
        val p = XmlFactory.parser(data)
        var currentId: String? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.local) {
                "style" -> currentId = p.attr("styleId")
                "name" -> {
                    val id = currentId ?: continue
                    val n = (p.attr("val") ?: "").lowercase()
                    when {
                        n == "title" -> out[id] = 0
                        n.startsWith("heading ") -> n.removePrefix("heading ").trim().toIntOrNull()?.let { if (it in 1..6) out[id] = it }
                    }
                }
                // A style that carries its own numbering makes its paragraphs list items.
                "numPr" -> currentId?.let { if (out[it] == null) out[it] = -1 }
            }
        }
        return out
    }

    private class Run {
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: String? = null; var sizeHalfPt = 0; var highlight: String? = null; var vert: String? = null
        fun reset() { bold = false; italic = false; underline = false; strike = false; color = null; sizeHalfPt = 0; highlight = null; vert = null }
    }

    private class Para {
        val sb = StringBuilder()
        var style: String? = null
        var align: String? = null
        var rtl = false
        var list = false
        var listLevel = 0
        var pageBreakBefore = false
    }

    private fun convert(xml: ByteArray, rels: Map<String, ZipDoc.Rel>, styles: Map<String, Int>, zip: ZipDoc): String {
        val p = XmlFactory.parser(xml)
        val out = ArrayList<StringBuilder>().apply { add(StringBuilder()) }  // stack: document / table / row / cell
        var para: Para? = null
        val run = Run()
        var inRun = false
        var inRunProps = false
        var inParaProps = false
        var inText = false
        var skipDepth = 0           // >0 while inside a subtree we ignore (deleted text, fallbacks, ...)
        var depth = 0
        var cellSpan = 1
        var extentCx = 0L
        var listOpen = false
        var hyperlinkDepth = -1

        fun cur() = out.last()
        fun emit(s: String) { (para?.sb ?: cur()).append(s); if (cur().length > Limits.OUTPUT_CHARS) throw TooLargeException() }

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) {
                depth++
                val name = p.name
                if (skipDepth > 0) { skipDepth++; continue }
                when (name) {
                    "w:del", "w:delText", "mc:Fallback", "w:instrText", "w:footnoteReference", "w:commentReference",
                    "w:sectPr", "w:rPrChange", "w:pPrChange", "v:shapetype" -> { skipDepth = 1; continue }
                }
                when (name) {
                    "w:p" -> {
                        para = Para()
                    }
                    "w:pPr" -> inParaProps = true
                    "w:pStyle" -> if (inParaProps) para?.style = p.attr("val")
                    "w:jc" -> if (inParaProps && !inRunProps) para?.align = when (p.attr("val")) {
                        "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; "left", "start" -> "left"; else -> null
                    }
                    "w:bidi" -> if (inParaProps && !inRunProps) para?.rtl = p.attr("val").let { it == null || it == "1" || it == "true" }
                    "w:numPr" -> if (inParaProps) para?.list = true
                    "w:ilvl" -> if (inParaProps) para?.listLevel = p.attr("val")?.toIntOrNull() ?: 0
                    "w:pageBreakBefore" -> if (inParaProps) para?.pageBreakBefore = true
                    "w:r" -> { inRun = true; run.reset() }
                    "w:rPr" -> inRunProps = true
                    "w:b" -> if (inRunProps && inRun) run.bold = isOn(p)
                    "w:i" -> if (inRunProps && inRun) run.italic = isOn(p)
                    "w:u" -> if (inRunProps && inRun) run.underline = p.attr("val") != "none"
                    "w:strike", "w:dstrike" -> if (inRunProps && inRun) run.strike = isOn(p)
                    "w:color" -> if (inRunProps && inRun) run.color = p.attr("val")?.takeIf { it.matches(HEX6) }
                    "w:sz" -> if (inRunProps && inRun) run.sizeHalfPt = p.attr("val")?.toIntOrNull() ?: 0
                    "w:highlight" -> if (inRunProps && inRun) run.highlight = HIGHLIGHTS[p.attr("val")]
                    "w:shd" -> if (inRunProps && inRun) p.attr("fill")?.takeIf { it.matches(HEX6) && it != "FFFFFF" }?.let { run.highlight = "#$it" }
                    "w:vertAlign" -> if (inRunProps && inRun) run.vert = p.attr("val")
                    "w:t" -> inText = true
                    "w:tab" -> if (inRun) emit("&emsp;")
                    "w:br", "w:cr" -> if (inRun) {
                        if (p.attr("type") == "page") emit("<hr class=\"pb\">") else emit("<br>")
                    }
                    "w:hyperlink" -> { hyperlinkDepth = depth; emit("<span class=\"link\">") }
                    "w:tbl" -> {
                        flushList(cur(), listOpen).also { listOpen = false }
                        out.add(StringBuilder("<table>"))
                    }
                    "w:tr" -> out.add(StringBuilder("<tr>"))
                    "w:tc" -> { cellSpan = 1; out.add(StringBuilder()) }
                    "w:gridSpan" -> cellSpan = p.attr("val")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                    "wp:extent" -> extentCx = p.attr("cx")?.toLongOrNull() ?: 0L
                    "a:blip", "v:imagedata" -> {
                        val id = p.attr("embed") ?: p.attr("id")
                        val target = id?.let { rels[it] }
                        if (target != null && !target.external) {
                            val uri = zip.imageUri(target.target)
                            if (uri != null) {
                                val w = if (extentCx > 0) " style=\"width:${(extentCx / 9525).coerceAtMost(2000)}px\"" else ""
                                emit("<img src=\"$uri\"$w>")
                            }
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val name = p.name
                if (skipDepth > 0) { skipDepth--; depth--; continue }
                when (name) {
                    "w:pPr" -> inParaProps = false
                    "w:rPr" -> inRunProps = false
                    "w:r" -> inRun = false
                    "w:t" -> inText = false
                    "w:hyperlink" -> if (hyperlinkDepth == depth) { emit("</span>"); hyperlinkDepth = -1 }
                    "w:p" -> {
                        val pp = para
                        para = null
                        if (pp != null) {
                            if (pp.style?.let { styles[it] } == -1) pp.list = true
                            val target = cur()
                            val inCell = out.size > 1
                            if (pp.list && !inCell) {
                                if (!listOpen) { target.append("<ul>"); listOpen = true }
                            } else if (listOpen) {
                                target.append("</ul>"); listOpen = false
                            }
                            if (pp.pageBreakBefore) target.append("<hr class=\"pb\">")
                            val level = pp.style?.let { styles[it] }?.takeIf { it >= 0 }
                            val tag = when {
                                pp.list && !inCell -> "li"
                                level == 0 -> "h1"
                                level != null -> "h${(level + 1).coerceAtMost(6)}"
                                else -> "p"
                            }
                            val attrs = StringBuilder(" dir=\"${if (pp.rtl) "rtl" else "auto"}\"")
                            val css = StringBuilder()
                            pp.align?.let { css.append("text-align:$it;") }
                            if (pp.list && pp.listLevel > 0) css.append("margin-inline-start:${pp.listLevel * 22}px;")
                            if (css.isNotEmpty()) attrs.append(" style=\"$css\"")
                            target.append("<$tag$attrs>").append(pp.sb).append("</$tag>")
                        }
                    }
                    "w:tc" -> {
                        if (out.size > 1) {
                            val cell = out.removeAt(out.size - 1)
                            val span = if (cellSpan > 1) " colspan=\"$cellSpan\"" else ""
                            cur().append("<td$span dir=\"auto\">").append(cell).append("</td>")
                        }
                    }
                    "w:tr" -> if (out.size > 1) { val row = out.removeAt(out.size - 1); cur().append(row).append("</tr>") }
                    "w:tbl" -> if (out.size > 1) { val t = out.removeAt(out.size - 1); cur().append(t).append("</table>") }
                }
                depth--
            } else if (ev == XmlPullParser.TEXT && inText && skipDepth == 0) {
                emit(styled(Html.esc(p.text ?: ""), run))
            }
        }
        while (out.size > 1) { val x = out.removeAt(out.size - 1); out.last().append(x) }
        if (listOpen) out[0].append("</ul>")
        return out[0].toString()
    }

    private fun flushList(sb: StringBuilder, open: Boolean) { if (open) sb.append("</ul>") }

    private fun isOn(p: XmlPullParser): Boolean {
        val v = p.attr("val") ?: return true
        return v != "0" && v != "false" && v != "off"
    }

    private fun styled(text: String, r: Run): String {
        if (text.isEmpty()) return text
        val css = StringBuilder()
        r.color?.let { if (it != "000000") css.append("color:#$it;") }
        if (r.sizeHalfPt in 8..400) css.append("font-size:${r.sizeHalfPt / 2.0 * 1.333}px;")
        r.highlight?.let { css.append("background:$it;") }
        var s = if (css.isNotEmpty()) "<span style=\"$css\">$text</span>" else text
        if (r.bold) s = "<b>$s</b>"
        if (r.italic) s = "<i>$s</i>"
        if (r.underline) s = "<u>$s</u>"
        if (r.strike) s = "<s>$s</s>"
        when (r.vert) { "superscript" -> s = "<sup>$s</sup>"; "subscript" -> s = "<sub>$s</sub>" }
        return s
    }

    private val HEX6 = Regex("[0-9A-Fa-f]{6}")
    private val HIGHLIGHTS = mapOf(
        "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff",
        "blue" to "#0000ff", "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080",
        "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
        "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000"
    )
}
