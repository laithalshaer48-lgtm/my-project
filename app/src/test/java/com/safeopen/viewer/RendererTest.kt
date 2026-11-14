package com.safeopen.viewer

import com.safeopen.viewer.render.DocxRenderer
import com.safeopen.viewer.render.EpubRenderer
import com.safeopen.viewer.render.PptxRenderer
import com.safeopen.viewer.render.TextRenderer
import com.safeopen.viewer.render.XlsxRenderer
import com.safeopen.viewer.render.XmlFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class RendererTest {

    companion object {
        @JvmStatic @BeforeClass fun setup() {
            XmlFactory.provider = { KXmlParser() }
        }

        /** Rendered pages are also written out so they can be inspected in a browser. */
        val outDir = File(System.getProperty("java.io.tmpdir"), "safeopen-render").apply { mkdirs() }
    }

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI())

    private fun assertInert(html: String) {
        assertTrue(html.contains("Content-Security-Policy"))
        assertFalse("no scripts", html.contains("<script", ignoreCase = true))
        assertFalse("no remote urls", Regex("(src|href)=\"https?:").containsMatchIn(html))
    }

    @Test fun docx() {
        val html = DocxRenderer.render(fixture("sample.docx"))
        File(outDir, "docx.html").writeText(html)
        assertInert(html)
        assertTrue(html.contains("<h1"))
        assertTrue(html.contains("Quarterly Report"))
        assertTrue(html.contains("<b>"))
        assertTrue(html.contains("هذا نص عربي للتجربة"))
        assertTrue(html.contains("<li"))
        assertTrue(html.contains("R1C2"))
        assertTrue(html.contains("data:image/png;base64,"))
    }

    @Test fun xlsx() {
        val sheets = XlsxRenderer.render(fixture("sample.xlsx"))
        assertEquals(listOf("Sales", "Notes"), sheets.map { it.name })
        val html = sheets[0].html
        File(outDir, "xlsx.html").writeText(html)
        assertInert(html)
        assertTrue(html.contains("Apples"))
        assertTrue(html.contains("تفاح"))
        assertTrue(html.contains("2026-03-15"))
        assertTrue(html.contains("1.25"))
        assertTrue(html.contains("colspan=\"3\""))
        assertTrue(sheets[1].html.contains("Second sheet"))
    }

    @Test fun pptx() {
        val html = PptxRenderer.render(fixture("sample.pptx"))
        File(outDir, "pptx.html").writeText(html)
        assertInert(html)
        assertTrue(html.contains("Hello Slides"))
        assertTrue(html.contains("Subtitle here"))
        assertTrue(html.contains("Free text box"))
        assertTrue(html.contains("T4"))
        assertTrue(html.contains("data:image/png;base64,"))
        assertEquals(2, Regex("class=\"slide\"").findAll(html).count())
    }

    @Test fun csvParsing() {
        val rows = TextRenderer.parseCsv("a,b\n\"x, y\",\"q\"\"z\"\n", ',', 10, 10)
        assertEquals(listOf(listOf("a", "b"), listOf("x, y", "q\"z")), rows)
    }

    @Test fun legacyArabicText() {
        val bytes = "مرحبا".toByteArray(charset("windows-1256"))
        assertEquals("مرحبا", TextRenderer.decode(bytes))
    }

    @Test fun rtf() {
        val text = TextRenderer.rtfToText("{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Arial;}}\\f0 Hello \\b bold\\b0\\par caf\\'e9 \\u1605?}")
        assertEquals("Hello bold\ncafé م", text)
    }

    @Test fun htmlSanitizer() {
        val out = EpubRenderer.sanitize("<html><body><p onclick=\"x()\">Hi</p><script>alert(1)</script><iframe src=\"http://x\"></iframe><a href=\"javascript:alert(1)\">l</a></body></html>", "", null)
        assertFalse(out.contains("script")); assertFalse(out.contains("iframe")); assertFalse(out.contains("onclick")); assertFalse(out.contains("javascript:"))
        assertTrue(out.contains("Hi"))
    }

    @Test fun xlsxHelpers() {
        assertEquals(0, XlsxRenderer.colIndex("A1"))
        assertEquals(27, XlsxRenderer.colIndex("AB12"))
        assertEquals("AB", XlsxRenderer.colName(27))
        assertEquals("2026-03-15", XlsxRenderer.formatNumber("46096", 14, emptyMap(), false))
        assertEquals("0.3", XlsxRenderer.formatNumber("0.30000000000000004", 0, emptyMap(), false))
        assertEquals("12.5%", XlsxRenderer.formatNumber("0.125", 10, emptyMap(), false))
    }
}
