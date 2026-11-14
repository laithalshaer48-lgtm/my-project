package com.safeopen.viewer.render

import org.xmlpull.v1.XmlPullParser
import java.io.File

/** OpenDocument (.odt / .ods / .odp — LibreOffice) → HTML. Macros are ignored. */
object OdfRenderer {

    private const val CSS = """
        .slide { background:#fff; border-radius:4px; box-shadow:0 1px 4px rgba(0,0,0,.25); padding:16px; margin:12px 0; }
        .label { color:#666; font-size:13px; }
        table { font-size: 14px; }
        h3.sheet { margin-top: 18px; }
    """

    private class Style(val bold: Boolean, val italic: Boolean, val underline: Boolean, val color: String?)

    fun render(file: File): String = ZipDoc(file).use { zip ->
        val content = zip.read("content.xml") ?: throw IllegalStateException("content.xml missing")
        val styles = HashMap<String, Style>()
        zip.read("styles.xml")?.let { collectStyles(it, styles) }
        collectStyles(content, styles)
        Html.page(convert(content, styles, zip), CSS)
    }

    private fun collectStyles(data: ByteArray, out: MutableMap<String, Style>) {
        val p = XmlFactory.parser(data)
        var name: String? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "style:style" -> name = p.attr("name")
                "style:text-properties" -> {
                    val n = name ?: continue
                    out[n] = Style(
                        bold = p.getAttributeValue(null, "fo:font-weight") == "bold",
                        italic = p.getAttributeValue(null, "fo:font-style") == "italic",
                        underline = p.getAttributeValue(null, "style:text-underline-style").let { it != null && it != "none" },
                        color = p.getAttributeValue(null, "fo:color")?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
                    )
                }
            }
        }
    }

    private fun convert(xml: ByteArray, styles: Map<String, Style>, zip: ZipDoc): String {
        val p = XmlFactory.parser(xml)
        val sb = StringBuilder()
        val spanClose = ArrayList<String>()
        var inParagraph = 0
        var slideNo = 0
        var rowRepeat: Int
        var emptyRowRun = 0
        val rowBuf = StringBuilder()
        var inRow = false
        var rowHasContent = false
        var cellRepeat = 1
        var cellStart = 0
        var skip = 0

        fun target() = if (inRow) rowBuf else sb

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (sb.length > Limits.OUTPUT_CHARS) throw TooLargeException()
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (skip > 0) { skip++; continue }
                    when (p.name) {
                        "office:scripts", "office:forms", "text:tracked-changes", "office:annotation",
                        "draw:object", "presentation:notes", "text:note-citation" -> skip = 1
                        "text:h" -> {
                            val lvl = p.attr("outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 2
                            target().append("<h$lvl dir=\"auto\">"); inParagraph++
                            spanClose.add("</h$lvl>")
                        }
                        "text:p" -> {
                            target().append(if (inRow) "<div dir=\"auto\">" else "<p dir=\"auto\">"); inParagraph++
                            spanClose.add(if (inRow) "</div>" else "</p>")
                        }
                        "text:span" -> {
                            val st = p.attr("style-name")?.let { styles[it] }
                            val open = StringBuilder()
                            val close = StringBuilder()
                            if (st != null) {
                                st.color?.let { open.append("<span style=\"color:$it\">"); close.insert(0, "</span>") }
                                if (st.bold) { open.append("<b>"); close.insert(0, "</b>") }
                                if (st.italic) { open.append("<i>"); close.insert(0, "</i>") }
                                if (st.underline) { open.append("<u>"); close.insert(0, "</u>") }
                            }
                            target().append(open); spanClose.add(close.toString())
                        }
                        "text:a" -> { target().append("<span class=\"link\">"); spanClose.add("</span>") }
                        "text:s" -> target().append("&nbsp;".repeat(p.attr("c")?.toIntOrNull()?.coerceIn(1, 100) ?: 1))
                        "text:tab" -> target().append("&emsp;")
                        "text:line-break" -> target().append("<br>")
                        "text:list" -> { target().append("<ul>"); spanClose.add("</ul>") }
                        "text:list-item" -> { target().append("<li>"); spanClose.add("</li>") }
                        "table:table" -> {
                            val name = p.attr("name")
                            if (name != null && slideNo == 0) sb.append("<h3 class=\"sheet\" dir=\"auto\">").append(Html.esc(name)).append("</h3>")
                            sb.append("<table>"); spanClose.add("</table>")
                            emptyRowRun = 0
                        }
                        "table:table-row" -> {
                            rowRepeat = p.attr("number-rows-repeated")?.toIntOrNull() ?: 1
                            inRow = true; rowHasContent = false; rowBuf.setLength(0); rowBuf.append("<tr>")
                            spanClose.add("ROW:$rowRepeat")
                        }
                        "table:table-cell" -> {
                            cellRepeat = p.attr("number-columns-repeated")?.toIntOrNull() ?: 1
                            val span = p.attr("number-columns-spanned")?.toIntOrNull()?.takeIf { it > 1 }
                            val rspan = p.attr("number-rows-spanned")?.toIntOrNull()?.takeIf { it > 1 }
                            cellStart = rowBuf.length
                            rowBuf.append("<td")
                            span?.let { rowBuf.append(" colspan=\"$it\"") }
                            rspan?.let { rowBuf.append(" rowspan=\"$it\"") }
                            rowBuf.append(">")
                            spanClose.add("CELL")
                        }
                        "table:covered-table-cell" -> Unit
                        "draw:page" -> {
                            slideNo++
                            sb.append("<div class=\"label\">").append(slideNo).append("</div><div class=\"slide\">")
                            spanClose.add("</div>")
                        }
                        "draw:image" -> {
                            val href = p.attr("href")
                            if (href != null && !href.contains(":")) zip.imageUri(href.removePrefix("./"))?.let {
                                target().append("<img src=\"$it\">")
                            }
                        }
                        else -> spanClose.add("")
                    }
                    if (skip == 0 && p.name in NO_CLOSE) Unit
                }
                XmlPullParser.END_TAG -> {
                    if (skip > 0) { skip--; continue }
                    if (p.name in NO_CLOSE) continue
                    val close = if (spanClose.isEmpty()) "" else spanClose.removeAt(spanClose.size - 1)
                    when {
                        close.startsWith("ROW:") -> {
                            rowBuf.append("</tr>")
                            inRow = false
                            val rep = close.removePrefix("ROW:").toIntOrNull() ?: 1
                            if (rowHasContent) {
                                emptyRowRun = 0
                                repeat(rep.coerceAtMost(50)) { sb.append(rowBuf) }
                            } else if (emptyRowRun < 2) {
                                emptyRowRun++
                                sb.append(rowBuf)
                            }
                        }
                        close == "CELL" -> {
                            rowBuf.append("</td>")
                            val cellHtml = rowBuf.substring(cellStart)
                            val empty = cellHtml.matches(Regex("<td[^>]*></td>"))
                            if (!empty) rowHasContent = true
                            // Repeated empty cells (often thousands) collapse to one
                            val reps = if (empty) 1 else cellRepeat.coerceAtMost(50)
                            repeat(reps - 1) { rowBuf.append(cellHtml) }
                        }
                        else -> {
                            target().append(close)
                            if (p.name == "text:p" || p.name == "text:h") inParagraph--
                        }
                    }
                }
                XmlPullParser.TEXT -> if (skip == 0 && (inParagraph > 0)) target().append(Html.esc(p.text ?: ""))
            }
        }
        return "<div class=\"paper\">$sb</div>"
    }

    private val NO_CLOSE = setOf("text:s", "text:tab", "text:line-break", "draw:image", "table:covered-table-cell")
}
