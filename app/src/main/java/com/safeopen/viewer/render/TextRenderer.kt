package com.safeopen.viewer.render

import com.safeopen.viewer.util.Bytes
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Plain text, CSV, RTF, hex view and best-effort text extraction from legacy Office files. */
object TextRenderer {

    const val MAX_TEXT = 4 * 1024 * 1024
    const val MAX_HEX = 64 * 1024

    fun decode(data: ByteArray): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte())
            return String(data, 3, data.size - 3, Charsets.UTF_8)
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, 0, trimPartialUtf8(data))).toString()
        } catch (e: Exception) {
            // Legacy single-byte text: Arabic (windows-1256) or Western (windows-1252)
            var high = 0; var arabic = 0
            for (b in data) {
                val c = b.toInt() and 0xFF
                if (c >= 0x80) { high++; if (c in 0xC1..0xDA || c in 0xDD..0xDF || c in 0xE1..0xE6 || c == 0xEC || c == 0xED) arabic++ }
            }
            val cs = if (high > 0 && arabic * 100 / high > 50) "windows-1256" else "windows-1252"
            return String(data, Charset.forName(cs))
        }
    }

    /** Ignore a UTF-8 sequence cut in half by a size cap. */
    private fun trimPartialUtf8(d: ByteArray): Int {
        var n = d.size
        var back = 0
        while (back < 3 && n - back - 1 >= 0) {
            val c = d[n - back - 1].toInt() and 0xFF
            if (c and 0xC0 == 0x80) { back++; continue }
            if (c >= 0xC0) {
                val need = when { c >= 0xF0 -> 4; c >= 0xE0 -> 3; else -> 2 }
                if (back + 1 < need) n -= back + 1
            }
            break
        }
        return n
    }

    fun text(file: File): String {
        val data = Bytes.readHead(file, MAX_TEXT)
        val sb = StringBuilder()
        if (file.length() > MAX_TEXT) sb.append(Html.note("⚠ ${MAX_TEXT / (1024 * 1024)} MB"))
        sb.append("<pre dir=\"auto\">").append(Html.esc(decode(data))).append("</pre>")
        return Html.page("<div class=\"paper\">$sb</div>")
    }

    fun csv(file: File): String {
        val text = decode(Bytes.readHead(file, MAX_TEXT))
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val delim = listOf(',', ';', '\t', '|').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = parseCsv(text, delim, 5000, 150)
        val sb = StringBuilder("<table>")
        rows.forEachIndexed { i, r ->
            sb.append("<tr>")
            for (c in r) sb.append(if (i == 0) "<th dir=\"auto\">" else "<td dir=\"auto\">").append(Html.esc(c)).append(if (i == 0) "</th>" else "</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        return Html.page(sb.toString(), "body{background:#fff} th{background:#f1f3f7;position:sticky;top:0} td,th{white-space:nowrap}")
    }

    fun parseCsv(text: String, delim: Char, maxRows: Int, maxCols: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length && rows.size < maxRows) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> { if (row.size < maxCols) row.add(cell.toString()); cell.setLength(0) }
                '\r' -> Unit
                '\n' -> { if (row.size < maxCols) row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun hex(file: File, notice: String? = null): String {
        val data = Bytes.readHead(file, MAX_HEX)
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            sb.append(String.format(java.util.Locale.US, "%08x  ", i))
            val line = StringBuilder()
            for (j in 0 until 16) {
                if (i + j < data.size) {
                    val b = data[i + j].toInt() and 0xFF
                    sb.append(String.format(java.util.Locale.US, "%02x ", b))
                    line.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(' ').append(Html.esc(line.toString())).append('\n')
            i += 16
        }
        val more = if (file.length() > MAX_HEX) Html.note("${MAX_HEX / 1024} KB / ${file.length() / 1024} KB") else ""
        val top = notice?.let { Html.note(it) } ?: ""
        return Html.page("$top$more<div class=\"paper\" dir=\"ltr\"><pre style=\"font-size:11px\">$sb</pre></div>")
    }

    /** Readable text runs (UTF-16LE and 8-bit) from a binary Office file. */
    fun extractStrings(file: File, notice: String): String {
        val data = Bytes.readHead(file, 32 * 1024 * 1024)
        val parts = LinkedHashSet<String>()
        // UTF-16LE runs (modern Word/Excel/PowerPoint store most text this way, including Arabic)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < data.size) {
            val ch = ((data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)).toChar()
            if (isReadable(ch)) sb.append(ch) else {
                if (sb.trim().length >= 4) parts += sb.toString().trim()
                sb.setLength(0)
            }
            i += 2
        }
        // 8-bit runs (older Word text)
        sb.setLength(0)
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == 0x0D || c == 0x09) sb.append(if (c == 0x0D) '\n' else c.toChar()) else {
                if (sb.trim().length >= 12 && sb.count { it == ' ' } >= 2) parts += sb.toString().trim()
                sb.setLength(0)
            }
        }
        val noise = setOf("Root Entry", "SummaryInformation", "DocumentSummaryInformation", "WordDocument", "Workbook",
            "PowerPoint Document", "Current User", "CompObj", "Times New Roman", "Arial", "Calibri", "Normal", "Default Paragraph Font")
        val text = parts.filter { it !in noise && !it.startsWith("\u0001") && it.any { c -> c.isLetter() } }.joinToString("\n\n")
        return Html.page(Html.note(notice) + "<div class=\"paper\"><pre dir=\"auto\" style=\"font-family:sans-serif;font-size:15px\">" +
            Html.esc(text.take(MAX_TEXT)) + "</pre></div>")
    }

    private fun isReadable(c: Char): Boolean =
        c in ' '..'~' || c == '\r' || c == '\n' || c == '\t' ||
            c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' ||
            c in 'À'..'ɏ' || c in 'Ѐ'..'ӿ' || c in '‐'..'‧' || c == ' '

    // ---------------------------------------------------------------------------------- RTF ----

    fun rtf(file: File): String {
        val data = Bytes.readHead(file, 32 * 1024 * 1024)
        val text = rtfToText(String(data, Charsets.ISO_8859_1))
        return Html.page("<div class=\"paper\">" + text.split('\n').joinToString("") { "<p dir=\"auto\">${Html.esc(it)}</p>" } + "</div>")
    }

    fun rtfToText(rtf: String): String {
        val out = StringBuilder()
        val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
            "footerl", "footerr", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
            "datastore", "latentstyles", "objdata", "fldinst", "filetbl", "revtbl", "pgdsctbl", "bkmkstart", "bkmkend")
        var charset = Charset.forName("windows-1252")
        val stack = ArrayList<Pair<Boolean, Int>>() // (skipping, ucSkip)
        var skipping = false
        var uc = 1
        var pendingSkip = 0
        val bytes = java.io.ByteArrayOutputStream()
        fun flushBytes() { if (bytes.size() > 0) { out.append(String(bytes.toByteArray(), charset)); bytes.reset() } }
        var i = 0
        while (i < rtf.length && out.length < MAX_TEXT) {
            val c = rtf[i]
            when (c) {
                '{' -> { flushBytes(); stack.add(skipping to uc); i++ }
                '}' -> { flushBytes(); if (stack.isNotEmpty()) { val s = stack.removeAt(stack.size - 1); skipping = s.first; uc = s.second }; i++ }
                '\\' -> {
                    if (i + 1 >= rtf.length) break
                    val n = rtf[i + 1]
                    if (n == '\'') {
                        val hex = rtf.substring(i + 2, minOf(i + 4, rtf.length))
                        if (pendingSkip > 0) pendingSkip-- else if (!skipping) hex.toIntOrNull(16)?.let { bytes.write(it) }
                        i += 4
                    } else if (n == '*') {
                        skipping = true; i += 2
                    } else if (n.isLetter()) {
                        var j = i + 1
                        while (j < rtf.length && rtf[j].isLetter()) j++
                        val word = rtf.substring(i + 1, j)
                        var k = j
                        if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) { k++; while (k < rtf.length && rtf[k].isDigit()) k++ }
                        val param = rtf.substring(j, k).toIntOrNull()
                        if (k < rtf.length && rtf[k] == ' ') k++
                        i = k
                        if (word in skipDest) { skipping = true; continue }
                        if (skipping) continue
                        when (word) {
                            "ansicpg" -> param?.let { try { charset = Charset.forName("windows-$it") } catch (_: Exception) {} }
                            "par", "line", "sect", "row" -> { flushBytes(); out.append('\n') }
                            "page" -> { flushBytes(); out.append("\n\n") }
                            "tab", "cell" -> { flushBytes(); out.append('\t') }
                            "uc" -> uc = param ?: 1
                            "u" -> { flushBytes(); param?.let { out.append((if (it < 0) it + 65536 else it).toChar()) }; pendingSkip = uc }
                            "emdash" -> out.append('—'); "endash" -> out.append('–'); "bullet" -> out.append('•')
                            "lquote" -> out.append('‘'); "rquote" -> out.append('’'); "ldblquote" -> out.append('“'); "rdblquote" -> out.append('”')
                        }
                    } else {
                        if (!skipping) { flushBytes(); if (n == '~') out.append(' ') else if (n != '-' && n != '_') out.append(n) }
                        i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else if (!skipping) { flushBytes(); out.append(c) }
                    i++
                }
            }
        }
        flushBytes()
        return out.toString().trim()
    }
}
