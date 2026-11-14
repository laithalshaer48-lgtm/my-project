package com.safeopen.viewer.render

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Excel (.xlsx / .xlsm) → one HTML table per sheet. Formulas are shown with their
 * last calculated value (nothing is recalculated or executed). Macros are ignored.
 */
object XlsxRenderer {

    const val MAX_ROWS = 5000
    const val MAX_COLS = 120
    const val MAX_CELLS = 400_000

    data class Sheet(val name: String, val html: String)

    private const val CSS = """
        body { padding: 0; background: #fff; }
        table { border-collapse: separate; border-spacing: 0; margin: 0; font-size: 14px; }
        td, th { border: 0; border-right: 1px solid #dde1e8; border-bottom: 1px solid #dde1e8;
                 padding: 3px 6px; white-space: nowrap; max-width: 420px; overflow: hidden; text-overflow: ellipsis; }
        th { background: #f1f3f7; color: #555; font-weight: normal; position: sticky; top: 0; z-index: 2; text-align: center; }
        td.rh { background: #f1f3f7; color: #555; position: sticky; left: 0; z-index: 1; text-align: center; }
        th.corner { left: 0; z-index: 3; }
        td.n { text-align: right; }
        td.b { font-weight: bold; }
        .note { margin: 8px; }
    """

    fun render(file: File): List<Sheet> = ZipDoc(file).use { zip ->
        val wbPath = zip.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "xl/workbook.xml"
        val wbRels = zip.rels(wbPath)
        val shared = readSharedStrings(zip, wbRels)
        val styles = readStyles(zip, wbRels)
        val (sheets, date1904) = readWorkbook(zip.read(wbPath) ?: throw IllegalStateException("workbook missing"))
        var cellBudget = MAX_CELLS
        sheets.mapNotNull { (name, rid) ->
            val rel = wbRels[rid] ?: return@mapNotNull null
            if (rel.external) return@mapNotNull null
            val data = zip.read(rel.target) ?: return@mapNotNull null
            if (rel.type.endsWith("/chartsheet")) return@mapNotNull Sheet(name, Html.page(Html.note("📊 Chart sheet")))
            val res = renderSheet(data, shared, styles, date1904, cellBudget)
            cellBudget -= res.second
            Sheet(name, res.first)
        }
    }

    private fun readWorkbook(data: ByteArray): Pair<List<Pair<String, String>>, Boolean> {
        val p = XmlFactory.parser(data)
        val out = ArrayList<Pair<String, String>>()
        var date1904 = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.local) {
                "sheet" -> {
                    val name = p.attr("name") ?: "Sheet"
                    val rid = p.getAttributeValue(null, "r:id") ?: p.attr("id") ?: continue
                    if (p.attr("state") == "veryHidden") continue
                    out += name to rid
                }
                "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
            }
        }
        return out to date1904
    }

    private fun readSharedStrings(zip: ZipDoc, rels: Map<String, ZipDoc.Rel>): List<String> {
        val path = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "xl/sharedStrings.xml"
        val data = zip.read(path) ?: return emptyList()
        val out = ArrayList<String>()
        val p = XmlFactory.parser(data)
        var sb: StringBuilder? = null
        var inT = false
        var inPhonetic = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inPhonetic = true
                }
                XmlPullParser.END_TAG -> when (p.local) {
                    "si" -> { out += sb?.toString() ?: ""; sb = null }
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                }
                XmlPullParser.TEXT -> if (inT && !inPhonetic) sb?.append(p.text)
            }
        }
        return out
    }

    private class Styles(val xfFormat: List<Int>, val customFormats: Map<Int, String>, val xfBold: List<Boolean>)

    private fun readStyles(zip: ZipDoc, rels: Map<String, ZipDoc.Rel>): Styles {
        val path = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target ?: "xl/styles.xml"
        val data = zip.read(path) ?: return Styles(emptyList(), emptyMap(), emptyList())
        val p = XmlFactory.parser(data)
        val custom = HashMap<Int, String>()
        val xf = ArrayList<Int>()
        val xfBold = ArrayList<Boolean>()
        val fontBold = ArrayList<Boolean>()
        var inCellXfs = false
        var inFonts = false
        var currentFontBold = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) {
                when (p.local) {
                    "numFmt" -> {
                        val id = p.attr("numFmtId")?.toIntOrNull()
                        val code = p.attr("formatCode")
                        if (id != null && code != null) custom[id] = code
                    }
                    "fonts" -> inFonts = true
                    "font" -> if (inFonts) currentFontBold = false
                    "b" -> if (inFonts) currentFontBold = p.attr("val").let { it == null || it == "1" || it == "true" }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) {
                        xf += p.attr("numFmtId")?.toIntOrNull() ?: 0
                        val fontId = p.attr("fontId")?.toIntOrNull() ?: 0
                        xfBold += fontBold.getOrElse(fontId) { false }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.local) {
                    "cellXfs" -> inCellXfs = false
                    "fonts" -> inFonts = false
                    "font" -> if (inFonts) fontBold += currentFontBold
                }
            }
        }
        return Styles(xf, custom, xfBold)
    }

    private fun isDateFormat(id: Int, custom: Map<Int, String>): Boolean {
        if (id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58) return true
        val code = custom[id] ?: return false
        val cleaned = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\."), "").lowercase()
        return cleaned.contains('y') || cleaned.contains('d') || (cleaned.contains('m') && cleaned.contains('h')) ||
            cleaned.contains("mmm") || cleaned.contains('s') && cleaned.contains(':')
    }

    private fun isPercent(id: Int, custom: Map<Int, String>) = id == 9 || id == 10 || custom[id]?.contains('%') == true

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.US)

    fun formatNumber(raw: String, fmtId: Int, custom: Map<Int, String>, date1904: Boolean): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (isDateFormat(fmtId, custom) && d >= 0 && d < 2958466) {
            val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
            val days = Math.floor(d).toLong()
            val frac = d - days
            return if (frac < 1e-9) base.plusDays(days).format(DATE)
            else if (d < 1) {
                val secs = Math.round(frac * 86400)
                String.format(java.util.Locale.US, "%02d:%02d", secs / 3600, (secs % 3600) / 60)
            } else LocalDateTime.of(base.plusDays(days), java.time.LocalTime.MIDNIGHT)
                .plusSeconds(Math.round(frac * 86400)).format(DATETIME)
        }
        if (isPercent(fmtId, custom)) return general(d * 100) + "%"
        if (fmtId == 3 || fmtId == 4 || custom[fmtId]?.contains("#,##") == true) {
            val decimals = if (fmtId == 4) 2 else custom[fmtId]?.substringAfter('.', "")?.takeWhile { it == '0' }?.length ?: 0
            return String.format(java.util.Locale.US, "%,.${decimals}f", d)
        }
        if (fmtId == 2) return String.format(java.util.Locale.US, "%.2f", d)
        return general(d)
    }

    private fun general(d: Double): String {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    fun colIndex(ref: String): Int {
        var c = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') c = c * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') c = c * 26 + (ch - 'a' + 1) else break
        }
        return c - 1
    }

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun rowIndex(ref: String): Int = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: -1

    private class Cell(val text: String, val numeric: Boolean, val bold: Boolean)

    /** Returns (html, cellsUsed). */
    private fun renderSheet(data: ByteArray, shared: List<String>, styles: Styles, date1904: Boolean, budget: Int): Pair<String, Int> {
        val rows = java.util.TreeMap<Int, HashMap<Int, Cell>>()
        val merges = ArrayList<IntArray>() // r1,c1,r2,c2
        val p = XmlFactory.parser(data)
        var rowIdx = -1
        var colIdx = -1
        var type: String? = null
        var styleIdx = 0
        var value: StringBuilder? = null
        var inV = false
        var inIs = false
        var inT = false
        var truncatedRows = false
        var truncatedCols = false
        var cells = 0
        var rtl = false

        loop@ while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "sheetView" -> rtl = p.attr("rightToLeft").let { it == "1" || it == "true" }
                    "row" -> {
                        rowIdx = p.attr("r")?.toIntOrNull()?.minus(1) ?: (rowIdx + 1)
                        colIdx = -1
                        if (rowIdx >= MAX_ROWS) { truncatedRows = true; break@loop }
                    }
                    "c" -> {
                        val r = p.attr("r")
                        colIdx = if (r != null) colIndex(r) else colIdx + 1
                        if (r != null) rowIndex(r).takeIf { it >= 0 }?.let { rowIdx = it }
                        type = p.attr("t")
                        styleIdx = p.attr("s")?.toIntOrNull() ?: 0
                        value = StringBuilder()
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inT = true
                    "mergeCell" -> p.attr("ref")?.let { ref ->
                        val parts = ref.split(':')
                        if (parts.size == 2) merges += intArrayOf(rowIndex(parts[0]), colIndex(parts[0]), rowIndex(parts[1]), colIndex(parts[1]))
                    }
                }
                XmlPullParser.END_TAG -> when (p.local) {
                    "v" -> inV = false
                    "is" -> inIs = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = value?.toString() ?: ""
                        value = null
                        if (raw.isEmpty() || rowIdx < 0 || colIdx < 0) continue@loop
                        if (colIdx >= MAX_COLS) { truncatedCols = true; continue@loop }
                        if (cells >= budget) { truncatedRows = true; break@loop }
                        val fmt = styles.xfFormat.getOrElse(styleIdx) { 0 }
                        val bold = styles.xfBold.getOrElse(styleIdx) { false }
                        val cell = when (type) {
                            "s" -> Cell(shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }, false, bold)
                            "inlineStr", "str" -> Cell(raw, false, bold)
                            "b" -> Cell(if (raw.trim() == "1") "TRUE" else "FALSE", false, bold)
                            "e" -> Cell(raw, false, bold)
                            else -> Cell(formatNumber(raw.trim(), fmt, styles.customFormats, date1904), true, bold)
                        }
                        rows.getOrPut(rowIdx) { HashMap() }[colIdx] = cell
                        cells++
                    }
                }
                XmlPullParser.TEXT -> if ((inV || inT) && value != null) value!!.append(p.text)
            }
        }

        if (rows.isEmpty()) return Html.page(Html.note("∅")) to 0
        val maxRow = rows.lastKey()
        val maxCol = rows.values.maxOf { r -> r.keys.maxOrNull() ?: 0 }.coerceAtMost(MAX_COLS - 1)

        // merged regions: top-left gets span, others are hidden
        val spanAt = HashMap<Long, IntArray>()
        val hidden = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000 + c
        for (m in merges) {
            val (r1, c1, r2, c2) = m.toList()
            if (r1 < 0 || c1 < 0 || r2 < r1 || c2 < c1 || (r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 100_000) continue
            spanAt[key(r1, c1)] = intArrayOf(r2 - r1 + 1, c2 - c1 + 1)
            for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) hidden += key(r, c)
        }

        val sb = StringBuilder()
        if (truncatedRows || truncatedCols) sb.append(Html.note("⚠ $MAX_ROWS × $MAX_COLS"))
        sb.append("<table dir=\"${if (rtl) "rtl" else "ltr"}\"><thead><tr><th class=\"corner\"></th>")
        for (c in 0..maxCol) sb.append("<th>").append(colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        var lastRow = -1
        for (r in 0..maxRow) {
            val row = rows[r]
            // collapse long runs of empty rows
            if (row == null && r - lastRow > 3 && spanAt.keys.none { it / 100_000 == r.toLong() }) continue
            lastRow = r
            sb.append("<tr><td class=\"rh\">").append(r + 1).append("</td>")
            for (c in 0..maxCol) {
                val k = key(r, c)
                if (k in hidden) continue
                val cell = row?.get(c)
                val span = spanAt[k]
                sb.append("<td")
                val cls = buildList { if (cell?.numeric == true) add("n"); if (cell?.bold == true) add("b") }
                if (cls.isNotEmpty()) sb.append(" class=\"").append(cls.joinToString(" ")).append('"')
                if (span != null) {
                    if (span[0] > 1) sb.append(" rowspan=\"").append(span[0]).append('"')
                    if (span[1] > 1) sb.append(" colspan=\"").append(span[1]).append('"')
                }
                if (cell != null && !cell.numeric) sb.append(" dir=\"auto\"")
                sb.append('>')
                if (cell != null) sb.append(Html.esc(cell.text))
                sb.append("</td>")
            }
            sb.append("</tr>")
            if (sb.length > Limits.OUTPUT_CHARS) { sb.append("</tbody></table>"); return Html.page(sb.toString(), CSS) to cells }
        }
        sb.append("</tbody></table>")
        return Html.page(sb.toString(), CSS) to cells
    }
}
