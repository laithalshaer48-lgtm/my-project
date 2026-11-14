package com.safeopen.viewer.security

import com.safeopen.viewer.util.Bytes
import java.io.File
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * Offline, on-device threat scanner.
 *
 * It never executes anything from the file. It identifies the real file type from
 * its bytes and then looks for the techniques attackers actually use in documents:
 * macros, auto-run actions, embedded programs, remote templates, exploit objects,
 * disguised names, zip bombs and so on. It is heuristic: it complements, and does
 * not replace, a full antivirus with signature updates.
 */
object Scanner {

    const val HEAD_BYTES = 8 * 1024
    const val MAX_CONTENT_BYTES = 48 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 20_000
    private const val MAX_ENTRY_READ = 16 * 1024 * 1024
    private const val MAX_TOTAL_ENTRY_READ = 160L * 1024 * 1024
    private const val MAX_PDF_INFLATE_TOTAL = 96L * 1024 * 1024
    private const val MAX_PDF_STREAMS = 8000

    private val EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"

    /** Offline hash list: the standard EICAR test files (lets users verify the scanner works). */
    private val KNOWN_BAD_SHA256 = setOf(
        "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f",
        "2546dcffc5ad854d4ddc64fbf056871cd5a00f2471cb7a5bfd4ac23b6e9eedad",
        "e1105070ba828007508566e28a2b8d4c65d192e9eaf3b7868382b7cae747b397"
    )

    val EXECUTABLE_EXT = setOf(
        "exe", "scr", "com", "pif", "cpl", "msi", "msp", "mst", "dll", "sys", "drv", "ocx",
        "hta", "lnk", "reg", "gadget", "application", "appx", "appxbundle", "msix", "inf",
        "scf", "url", "chm", "iso", "img", "vhd", "vhdx", "appimage", "run", "dmg", "pkg",
        "command", "xll", "wll", "sct", "ws", "settingcontent-ms", "library-ms", "one"
    )
    val WINDOWS_SCRIPT_EXT = setOf("bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "jse", "wsf", "wsh", "msh")
    val SCRIPT_EXT = setOf("js", "mjs", "sh", "bash", "zsh", "py", "pyw", "pl", "rb", "php", "applescript", "scpt", "au3", "ahk", "lua", "tcl", "jar")
    private val DECOY_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "csv",
        "jpg", "jpeg", "png", "gif", "bmp", "mp3", "mp4", "avi", "mov", "zip", "rar", "html", "htm"
    )
    private val MACRO_EXT = setOf("docm", "dotm", "xlsm", "xltm", "xlam", "xlsb", "pptm", "potm", "ppsm", "ppam", "sldm")

    private val AUTO_EXEC = listOf(
        "AutoOpen", "Auto_Open", "Document_Open", "DocumentOpen", "Workbook_Open", "AutoExec",
        "AutoClose", "Auto_Close", "Document_Close", "Workbook_Activate", "AutoNew", "Document_New",
        "Presentation_Open", "Auto_Activate"
    )
    private val MACRO_BAD_CALLS = listOf(
        "Shell", "WScript.Shell", "CreateObject", "URLDownloadToFile", "powershell", "cmd.exe",
        "MSXML2.XMLHTTP", "WinHttp", "ADODB.Stream", "Shell.Application", "CallByName",
        "kernel32", "VirtualAlloc", "RtlMoveMemory", "CreateThread", "GetObject(\"winmgmts",
        "Environ(", "SaveToFile", "mshta", "regsvr32", "rundll32", "certutil"
    )
    private val COMMAND_PATTERNS = listOf(
        "-encodedcommand", " -enc ", "frombase64string", "downloadstring", "downloadfile(",
        "invoke-expression", "iex(", "invoke-webrequest", "start-process", "net.webclient",
        "wscript.shell", "cmd.exe /c", "cmd /c", "certutil -decode", "certutil -urlcache",
        "mshta ", "regsvr32 /s", "rundll32 ", "bitsadmin /transfer", "| sh", "|sh", "| bash",
        "/dev/tcp/", "nc -e", "base64 -d |", "rm -rf /", "chmod +x", "set-mppreference",
        "add-mppreference -exclusionpath", "schtasks /create", "reg add hklm", "vssadmin delete shadows"
    )

    private data class ExpectedKinds(val kinds: Set<FileKind>)
    private val EXPECTED: Map<String, Set<FileKind>> = buildMap {
        put("pdf", setOf(FileKind.PDF))
        for (e in listOf("docx", "docm", "dotx", "dotm")) put(e, setOf(FileKind.DOCX, FileKind.OOXML_OTHER))
        for (e in listOf("xlsx", "xlsm", "xltx", "xltm")) put(e, setOf(FileKind.XLSX, FileKind.OOXML_OTHER))
        for (e in listOf("pptx", "pptm", "ppsx", "ppsm", "potx")) put(e, setOf(FileKind.PPTX, FileKind.OOXML_OTHER))
        for (e in listOf("doc", "dot")) put(e, setOf(FileKind.DOC, FileKind.OLE_OTHER, FileKind.RTF, FileKind.HTML, FileKind.TEXT))
        for (e in listOf("xls", "xlt")) put(e, setOf(FileKind.XLS, FileKind.OLE_OTHER, FileKind.HTML, FileKind.CSV, FileKind.TEXT))
        for (e in listOf("ppt", "pps", "pot")) put(e, setOf(FileKind.PPT, FileKind.OLE_OTHER))
        for (e in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")) put(e, setOf(FileKind.IMAGE))
        for (e in listOf("mp4", "m4v", "mkv", "webm", "avi", "3gp", "mov")) put(e, setOf(FileKind.VIDEO, FileKind.AUDIO))
        for (e in listOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr")) put(e, setOf(FileKind.AUDIO, FileKind.VIDEO))
        put("txt", setOf(FileKind.TEXT, FileKind.CSV, FileKind.HTML, FileKind.SVG, FileKind.EMPTY))
        put("zip", setOf(FileKind.ZIP, FileKind.APK, FileKind.JAR, FileKind.DOCX, FileKind.XLSX, FileKind.PPTX, FileKind.OOXML_OTHER, FileKind.EPUB, FileKind.ODT, FileKind.ODS, FileKind.ODP))
        put("rtf", setOf(FileKind.RTF))
        put("odt", setOf(FileKind.ODT)); put("ods", setOf(FileKind.ODS)); put("odp", setOf(FileKind.ODP))
        put("epub", setOf(FileKind.EPUB))
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * @param truncated the file is only the first part of the real file (large file in a
     *                  folder scan); deep container checks are skipped.
     */
    fun scan(file: File, displayName: String, truncated: Boolean = false): ScanReport {
        val start = System.currentTimeMillis()
        val findings = LinkedHashSet<Finding>()
        val size = file.length()
        val ext = extensionOf(displayName)
        val head = Bytes.readHead(file, HEAD_BYTES)

        checkName(displayName, ext, findings)

        val zipNames: Set<String>? by lazy { zipEntryNames(file) }
        val kind = if (size == 0L) FileKind.EMPTY else detectKind(head, ext, file) { zipNames }

        val sha = Bytes.sha256(file)
        if (sha in KNOWN_BAD_SHA256) findings += Finding(FindingCode.KNOWN_MALWARE_HASH, Severity.DANGER, sha)
        if (Bytes.contains(head, EICAR)) findings += Finding(FindingCode.EICAR_TEST, Severity.DANGER)

        when (kind) {
            FileKind.EMPTY -> findings += Finding(FindingCode.EMPTY_FILE, Severity.INFO)
            FileKind.EXE_WINDOWS -> findings += Finding(FindingCode.EXECUTABLE_WINDOWS, Severity.DANGER)
            FileKind.EXE_LINUX -> findings += Finding(FindingCode.EXECUTABLE_LINUX, Severity.DANGER)
            FileKind.EXE_MAC -> findings += Finding(FindingCode.EXECUTABLE_MAC, Severity.DANGER)
            FileKind.DEX -> findings += Finding(FindingCode.EXECUTABLE_DEX, Severity.DANGER)
            else -> Unit
        }

        // Disguised file: the name claims a document/picture but the bytes say otherwise.
        val expected = EXPECTED[ext]
        if (expected != null && kind != FileKind.EMPTY && kind !in expected) {
            val sev = if (kind.isExecutable || kind == FileKind.APK) Severity.DANGER else Severity.WARNING
            findings += Finding(FindingCode.TYPE_MISMATCH, sev, ".$ext → $kind")
        }

        if (truncated) {
            findings += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
        } else {
            try {
                when (kind) {
                    FileKind.PDF -> analyzePdf(file, findings)
                    FileKind.DOCX, FileKind.XLSX, FileKind.PPTX, FileKind.OOXML_OTHER ->
                        analyzeZip(file, kind, ext, findings)
                    FileKind.ODT, FileKind.ODS, FileKind.ODP, FileKind.EPUB, FileKind.ZIP ->
                        analyzeZip(file, kind, ext, findings)
                    FileKind.APK -> {
                        findings += Finding(FindingCode.ANDROID_APP, Severity.WARNING)
                        analyzeZip(file, kind, ext, findings)
                    }
                    FileKind.JAR -> {
                        findings += Finding(FindingCode.JAVA_ARCHIVE, Severity.WARNING)
                        analyzeZip(file, kind, ext, findings)
                    }
                    FileKind.DOC, FileKind.XLS, FileKind.PPT, FileKind.OLE_OTHER -> analyzeOle(file, findings)
                    FileKind.RTF -> analyzeRtf(file, findings)
                    FileKind.HTML, FileKind.SVG, FileKind.TEXT, FileKind.CSV -> analyzeText(file, kind, ext, findings)
                    FileKind.RAR, FileKind.SEVEN_Z, FileKind.GZIP ->
                        findings += Finding(FindingCode.ARCHIVE_UNSCANNABLE, Severity.WARNING, kind.name)
                    else -> Unit
                }
            } catch (oom: OutOfMemoryError) {
                findings += Finding(FindingCode.PARTIAL_SCAN, Severity.WARNING, "memory")
            } catch (e: Exception) {
                findings += Finding(FindingCode.MALFORMED, Severity.WARNING, e.javaClass.simpleName)
            }
        }

        return ScanReport(
            fileName = displayName,
            size = size,
            sha256 = sha,
            kind = kind,
            findings = findings.toList().sortedByDescending { it.severity.ordinal },
            durationMs = System.currentTimeMillis() - start
        )
    }

    fun extensionOf(name: String): String {
        val clean = name.trim().trimEnd('.', ' ')
        val dot = clean.lastIndexOf('.')
        return if (dot < 0 || dot == clean.length - 1) "" else clean.substring(dot + 1).lowercase(Locale.ROOT)
    }

    // ---------------------------------------------------------------------------------- names ----

    private fun checkName(name: String, ext: String, out: MutableSet<Finding>) {
        if (name.any { it == '\u202E' || it == '\u202D' || it == '\u2066' || it == '\u2067' || it == '\u2068' }) {
            out += Finding(FindingCode.RTL_OVERRIDE_NAME, Severity.DANGER)
        }
        when (ext) {
            in EXECUTABLE_EXT -> out += Finding(FindingCode.DANGEROUS_EXTENSION, Severity.DANGER, ".$ext")
            in WINDOWS_SCRIPT_EXT -> out += Finding(FindingCode.SCRIPT_FILE, Severity.DANGER, ".$ext")
            in SCRIPT_EXT -> out += Finding(FindingCode.SCRIPT_FILE, Severity.WARNING, ".$ext")
            in MACRO_EXT -> out += Finding(FindingCode.MACRO_ENABLED_FORMAT, Severity.WARNING, ".$ext")
        }
        val parts = name.trim().lowercase(Locale.ROOT).split('.')
        if (parts.size >= 3) {
            val last = parts.last().trim()
            val prev = parts[parts.size - 2].trim()
            val runnable = last in EXECUTABLE_EXT || last in WINDOWS_SCRIPT_EXT || last in SCRIPT_EXT
            if (runnable && prev in DECOY_EXT) {
                out += Finding(FindingCode.DOUBLE_EXTENSION, Severity.DANGER, ".$prev.$last")
            }
        }
    }

    // ------------------------------------------------------------------------------ type sniff ----

    private fun zipEntryNames(file: File): Set<String>? = try {
        ZipInspector.list(file, MAX_ZIP_ENTRIES).entries.mapTo(HashSet()) { it.name }
    } catch (e: Exception) {
        null
    }

    fun detectKind(head: ByteArray, ext: String, file: File?, zipNames: () -> Set<String>?): FileKind {
        fun at(vararg b: Int, offset: Int = 0) = head.size >= offset + b.size &&
            b.indices.all { (head[offset + it].toInt() and 0xFF) == b[it] }
        fun str(s: String, offset: Int = 0) = Bytes.startsWith(head, Bytes.ascii(s), offset)

        if (head.isEmpty()) return FileKind.EMPTY
        if (str("MZ")) return FileKind.EXE_WINDOWS
        if (at(0x7F, 0x45, 0x4C, 0x46)) return FileKind.EXE_LINUX
        if (at(0xFE, 0xED, 0xFA, 0xCE) || at(0xFE, 0xED, 0xFA, 0xCF) || at(0xCE, 0xFA, 0xED, 0xFE) || at(0xCF, 0xFA, 0xED, 0xFE)) return FileKind.EXE_MAC
        if (at(0xCA, 0xFE, 0xBA, 0xBE) && ext != "class") return FileKind.EXE_MAC
        if (str("dex\n")) return FileKind.DEX
        if (Bytes.indexOf(head, Bytes.ascii("%PDF-"), 0, minOf(head.size, 1024)) >= 0) return FileKind.PDF

        if (str("PK\u0003\u0004") || str("PK\u0005\u0006") || str("PK\u0007\u0008")) {
            val names = zipNames() ?: return FileKind.ZIP
            if ("[Content_Types].xml" in names) {
                return when {
                    names.any { it.startsWith("word/") } -> FileKind.DOCX
                    names.any { it.startsWith("xl/") } -> FileKind.XLSX
                    names.any { it.startsWith("ppt/") } -> FileKind.PPTX
                    else -> FileKind.OOXML_OTHER
                }
            }
            if ("mimetype" in names && file != null) {
                val mime = readZipEntryText(file, "mimetype", 200)?.trim() ?: ""
                when {
                    mime.startsWith("application/vnd.oasis.opendocument.text") -> return FileKind.ODT
                    mime.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> return FileKind.ODS
                    mime.startsWith("application/vnd.oasis.opendocument.presentation") -> return FileKind.ODP
                    mime == "application/epub+zip" -> return FileKind.EPUB
                }
            }
            if ("AndroidManifest.xml" in names && names.any { it.startsWith("classes") && it.endsWith(".dex") }) return FileKind.APK
            if ("META-INF/MANIFEST.MF" in names && names.any { it.endsWith(".class") }) return FileKind.JAR
            return FileKind.ZIP
        }
        if (at(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return when (ext) {
                "doc", "dot", "wps" -> FileKind.DOC
                "xls", "xlt", "xla" -> FileKind.XLS
                "ppt", "pps", "pot" -> FileKind.PPT
                else -> {
                    val sample = if (file != null) Bytes.readHead(file, 2 * 1024 * 1024) else head
                    when {
                        Bytes.indexOf(sample, Bytes.utf16le("WordDocument")) >= 0 -> FileKind.DOC
                        Bytes.indexOf(sample, Bytes.utf16le("Workbook")) >= 0 -> FileKind.XLS
                        Bytes.indexOf(sample, Bytes.utf16le("PowerPoint Document")) >= 0 -> FileKind.PPT
                        else -> FileKind.OLE_OTHER
                    }
                }
            }
        }
        if (str("{\\rtf")) return FileKind.RTF
        if (str("Rar!\u001A\u0007")) return FileKind.RAR
        if (at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return FileKind.SEVEN_Z
        if (at(0x1F, 0x8B)) return FileKind.GZIP

        // Images
        if (at(0x89, 0x50, 0x4E, 0x47) || at(0xFF, 0xD8, 0xFF) || str("GIF87a") || str("GIF89a")) return FileKind.IMAGE
        if (str("RIFF") && str("WEBP", 8)) return FileKind.IMAGE
        if (str("BM") && ext == "bmp") return FileKind.IMAGE
        if (at(0x00, 0x00, 0x01, 0x00) && ext == "ico") return FileKind.IMAGE
        if (str("ftyp", 4)) {
            val brand = if (head.size >= 12) String(head, 8, 4, Charsets.ISO_8859_1) else ""
            return when (brand) {
                "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis" -> FileKind.IMAGE
                "M4A ", "M4B ", "M4P " -> FileKind.AUDIO
                else -> FileKind.VIDEO
            }
        }
        // Media
        if (str("RIFF") && str("WAVE", 8)) return FileKind.AUDIO
        if (str("RIFF") && str("AVI ", 8)) return FileKind.VIDEO
        if (at(0x1A, 0x45, 0xDF, 0xA3)) return FileKind.VIDEO
        if (str("ID3") || str("OggS") || str("fLaC") || str("#!AMR")) return FileKind.AUDIO
        if (head.size > 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 &&
            ext in setOf("mp3", "aac", "m4a")) return FileKind.AUDIO

        // Text-based formats
        val utf16 = at(0xFF, 0xFE) || at(0xFE, 0xFF)
        if (utf16 || Bytes.looksLikeText(head)) {
            val lowerHead = String(head, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            return when {
                ext == "svg" || ext == "svgz" || lowerHead.contains("<svg") -> FileKind.SVG
                ext in setOf("html", "htm", "xhtml", "shtml", "mht", "mhtml") ||
                    lowerHead.contains("<!doctype html") || lowerHead.contains("<html") -> FileKind.HTML
                ext in setOf("csv", "tsv") -> FileKind.CSV
                else -> FileKind.TEXT
            }
        }
        return FileKind.UNKNOWN
    }

    private fun readZipEntryText(file: File, name: String, max: Int): String? = try {
        ZipFile(file).use { z ->
            val e = z.getEntry(name) ?: return null
            z.getInputStream(e).use { String(Bytes.readCapped(it, max), Charsets.UTF_8) }
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------------------------ ZIP ----

    private fun analyzeZip(file: File, kind: FileKind, ext: String, out: MutableSet<Finding>) {
        val listing = try {
            ZipInspector.list(file, MAX_ZIP_ENTRIES)
        } catch (e: Exception) {
            out += Finding(FindingCode.MALFORMED, Severity.WARNING, e.message ?: "zip")
            return
        }
        if (listing.truncated || listing.declaredCount > MAX_ZIP_ENTRIES) {
            out += Finding(FindingCode.ZIP_TOO_MANY_ENTRIES, Severity.WARNING, listing.declaredCount.toString())
        }

        var totalSize = 0L
        var totalComp = 0L
        var encrypted = 0
        val isOffice = kind == FileKind.DOCX || kind == FileKind.XLSX || kind == FileKind.PPTX || kind == FileKind.OOXML_OTHER
        val isOdf = kind == FileKind.ODT || kind == FileKind.ODS || kind == FileKind.ODP

        for (e in listing.entries) {
            totalSize += e.size
            totalComp += e.compressedSize
            if (e.encrypted) encrypted++
            if (ZipInspector.isTraversal(e.name)) {
                out += Finding(FindingCode.ZIP_PATH_TRAVERSAL, Severity.DANGER, e.name.take(120))
            }
            if (e.size > 50L * 1024 * 1024 && e.compressedSize > 0 && e.size / e.compressedSize > 1000) {
                out += Finding(FindingCode.ZIP_BOMB, Severity.DANGER, e.name.take(80))
            }
            if (e.isDirectory) continue
            val entryExt = extensionOf(e.name.substringAfterLast('/'))
            if (kind != FileKind.APK && kind != FileKind.JAR) {
                if (entryExt in EXECUTABLE_EXT || entryExt in WINDOWS_SCRIPT_EXT) {
                    out += Finding(FindingCode.NESTED_EXECUTABLE, Severity.DANGER, e.name.take(120))
                } else if (entryExt == "apk" || entryExt == "dex") {
                    out += Finding(FindingCode.NESTED_EXECUTABLE, Severity.WARNING, e.name.take(120))
                }
            }
            if (isOffice) {
                val n = e.name
                when {
                    n.endsWith("vbaProject.bin", ignoreCase = true) -> out += Finding(FindingCode.VBA_MACROS, Severity.DANGER, n)
                    n.startsWith("xl/macrosheets/", ignoreCase = true) -> out += Finding(FindingCode.XLM_MACROS, Severity.DANGER, n)
                    n.contains("/activeX/", ignoreCase = true) -> out += Finding(FindingCode.ACTIVEX, Severity.WARNING)
                    n.contains("/embeddings/", ignoreCase = true) && (n.endsWith(".bin", true) || n.contains("oleObject", true)) ->
                        out += Finding(FindingCode.EMBEDDED_OBJECT, Severity.WARNING, n.substringAfterLast('/'))
                }
            }
            if (isOdf) {
                val n = e.name
                val basicMacro = n.startsWith("Basic/") && n.endsWith(".xml") &&
                    n.substringAfterLast('/') !in setOf("script-lc.xml", "script-lb.xml", "dialog-lc.xml", "dialog-lb.xml")
                if (basicMacro || n.startsWith("Scripts/")) out += Finding(FindingCode.ODF_MACROS, Severity.DANGER, n)
            }
        }
        if (totalSize > 4L * 1024 * 1024 * 1024 ||
            (totalSize > 200L * 1024 * 1024 && totalComp > 0 && totalSize / totalComp > 100)) {
            out += Finding(FindingCode.ZIP_BOMB, Severity.DANGER, "${totalSize / (1024 * 1024)} MB")
        }
        if (encrypted > 0) out += Finding(FindingCode.ZIP_ENCRYPTED, Severity.WARNING, encrypted.toString())
        if (out.any { it.code == FindingCode.ZIP_BOMB }) return // do not decompress a bomb

        // Content inspection of individual entries (bounded).
        var budget = MAX_TOTAL_ENTRY_READ
        var externalLinks = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var inspected = 0
            while (entries.hasMoreElements() && inspected < MAX_ZIP_ENTRIES && budget > 0) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                inspected++
                val name = e.name
                val lname = name.lowercase(Locale.ROOT)
                val interesting = when {
                    isOffice -> lname.endsWith(".rels") || lname.endsWith("vbaproject.bin") ||
                        lname.contains("/embeddings/") || lname == "word/document.xml" ||
                        lname.startsWith("xl/externallinks/") || lname.startsWith("xl/worksheets/")
                    isOdf -> lname == "content.xml" || lname.startsWith("basic/") || lname.startsWith("scripts/")
                    kind == FileKind.EPUB -> lname.endsWith(".xhtml") || lname.endsWith(".html") || lname.endsWith(".htm")
                    else -> true
                }
                if (!interesting) continue
                val cap = if (kind == FileKind.ZIP || kind == FileKind.APK || kind == FileKind.JAR) {
                    if (e.size in 0..(1024 * 1024)) 1024 * 1024 else HEAD_BYTES
                } else MAX_ENTRY_READ
                val data = try {
                    zip.getInputStream(e).use { Bytes.readCapped(it, minOf(cap.toLong(), budget).toInt()) }
                } catch (ex: Exception) {
                    continue
                }
                budget -= data.size

                // Executables hidden inside any container
                val nestedKind = sniffExecutable(data)
                if (nestedKind != null && kind != FileKind.APK && kind != FileKind.JAR) {
                    val code = if (isOffice || isOdf) FindingCode.EMBEDDED_EXECUTABLE else FindingCode.NESTED_EXECUTABLE
                    out += Finding(code, Severity.DANGER, "$name ($nestedKind)")
                }
                if (Bytes.contains(data, EICAR)) out += Finding(FindingCode.EICAR_TEST, Severity.DANGER, name)

                if (isOffice) {
                    when {
                        lname.endsWith(".rels") -> externalLinks += checkRelationships(String(data, Charsets.UTF_8), out)
                        lname.endsWith("vbaproject.bin") -> checkMacroCode(data, out)
                        lname.contains("/embeddings/") -> checkEmbedded(name, data, out)
                        lname == "word/document.xml" -> {
                            if (Bytes.contains(data, "DDEAUTO", ignoreCase = true) ||
                                Bytes.indexOf(data, Bytes.ascii("instrText"), 0) >= 0 && ddeInInstr(data)) {
                                out += Finding(FindingCode.DDE_FIELD, Severity.DANGER)
                            }
                        }
                        lname.startsWith("xl/externallinks/") -> {
                            if (Bytes.contains(data, "ddeLink", ignoreCase = true)) out += Finding(FindingCode.DDE_FIELD, Severity.DANGER, name)
                        }
                        lname.startsWith("xl/worksheets/") -> {
                            if (Bytes.contains(data, "cmd|", ignoreCase = true) || Bytes.contains(data, "msexcel|", ignoreCase = true)) {
                                out += Finding(FindingCode.DDE_FIELD, Severity.DANGER, name)
                            }
                        }
                    }
                }
                if (isOdf && lname == "content.xml" && Bytes.contains(data, "script:event-listener")) {
                    out += Finding(FindingCode.AUTO_EXEC_MACRO, Severity.WARNING, "event-listener")
                }
                if (isOdf && (lname.startsWith("basic/") || lname.startsWith("scripts/"))) checkMacroCode(data, out)
                if (kind == FileKind.EPUB && Bytes.contains(data, "<script", ignoreCase = true)) {
                    out += Finding(FindingCode.HTML_SCRIPT, Severity.INFO, name)
                }
            }
        }
        if (externalLinks > 0) out += Finding(FindingCode.EXTERNAL_LINKS, Severity.INFO, externalLinks.toString())
        if (budget <= 0) out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
    }

    private fun sniffExecutable(data: ByteArray): FileKind? {
        if (data.size < 4) return null
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val b2 = data[2].toInt() and 0xFF
        val b3 = data[3].toInt() and 0xFF
        if (b0 == 'M'.code && b1 == 'Z'.code && data.size >= 64) {
            // Confirm a PE header to avoid false positives on text starting with "MZ".
            val peOff = (data[60].toInt() and 0xFF) or ((data[61].toInt() and 0xFF) shl 8) or
                ((data[62].toInt() and 0xFF) shl 16) or ((data[63].toInt() and 0xFF) shl 24)
            if (peOff in 0..(data.size - 4) && Bytes.startsWith(data, Bytes.ascii("PE\u0000\u0000"), peOff)) return FileKind.EXE_WINDOWS
            if (Bytes.contains(data, "This program cannot be run in DOS mode")) return FileKind.EXE_WINDOWS
        }
        if (b0 == 0x7F && b1 == 'E'.code && b2 == 'L'.code && b3 == 'F'.code) return FileKind.EXE_LINUX
        if ((b0 == 0xCF || b0 == 0xCE) && b1 == 0xFA && b2 == 0xED && b3 == 0xFE) return FileKind.EXE_MAC
        // OLE "Package" objects carry a full embedded file; look for a PE inside.
        val mz = Bytes.indexOf(data, Bytes.ascii("This program cannot be run in DOS mode"))
        if (mz >= 0) return FileKind.EXE_WINDOWS
        return null
    }

    private fun ddeInInstr(data: ByteArray): Boolean {
        // Look for " DDE " inside field instructions: <w:instrText> DDE "c:\\windows\\..." </w:instrText>
        var i = Bytes.indexOf(data, Bytes.ascii("instrText"))
        var guard = 0
        while (i >= 0 && guard++ < 20_000) {
            val close = Bytes.indexOf(data, Bytes.ascii("</w:instrText>"), i)
            val end = if (close < 0) minOf(data.size, i + 4096) else close
            val start = Bytes.indexOf(data, Bytes.ascii(">"), i)
            if (start in 0 until end) {
                val text = String(data, start + 1, end - start - 1, Charsets.UTF_8).trim()
                if (text.startsWith("DDE", ignoreCase = true)) return true
            }
            i = Bytes.indexOf(data, Bytes.ascii("instrText"), end + 1)
        }
        return false
    }

    /** Returns the number of ordinary external links; raises findings for dangerous ones. */
    private fun checkRelationships(xml: String, out: MutableSet<Finding>): Int {
        var links = 0
        val rel = Regex("<Relationship\\s[^>]*>", RegexOption.IGNORE_CASE)
        fun attr(tag: String, a: String) = Regex("\\b$a\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: ""
        for (m in rel.findAll(xml)) {
            val tag = m.value
            val target = attr(tag, "Target")
            val type = attr(tag, "Type")
            val external = attr(tag, "TargetMode").equals("External", ignoreCase = true)
            val lt = target.lowercase(Locale.ROOT)
            if (lt.startsWith("mhtml:") || lt.contains("ms-msdt:") || lt.contains("!x-usc:") || lt.contains("search-ms:") || lt.contains("ms-officecmd:")) {
                out += Finding(FindingCode.FOLLINA_MSDT, Severity.DANGER, target.take(120))
                continue
            }
            if (!external) continue
            val remote = lt.startsWith("http:") || lt.startsWith("https:") || lt.startsWith("\\\\") || lt.startsWith("file:") || lt.startsWith("//")
            val loadsContent = type.endsWith("/attachedTemplate") || type.endsWith("/oleObject") ||
                type.endsWith("/frame") || type.endsWith("/subDocument") || type.endsWith("/externalLinkPath") && lt.startsWith("\\\\")
            if (loadsContent && remote) {
                out += Finding(FindingCode.EXTERNAL_TEMPLATE, Severity.DANGER, target.take(120))
            } else {
                links++
            }
        }
        return links
    }

    private fun checkMacroCode(data: ByteArray, out: MutableSet<Finding>) {
        val auto = AUTO_EXEC.filter { Bytes.contains(data, it, ignoreCase = true) }
        if (auto.isNotEmpty()) out += Finding(FindingCode.AUTO_EXEC_MACRO, Severity.DANGER, auto.joinToString(", "))
        val bad = MACRO_BAD_CALLS.filter { Bytes.contains(data, it, ignoreCase = true) }
        if (bad.isNotEmpty()) out += Finding(FindingCode.MACRO_SUSPICIOUS_CALLS, Severity.DANGER, bad.take(6).joinToString(", "))
    }

    private fun checkEmbedded(name: String, data: ByteArray, out: MutableSet<Finding>) {
        if (Bytes.indexOf(data, Bytes.utf16le("Equation Native")) >= 0 || Bytes.contains(data, "Equation.3", ignoreCase = true)) {
            out += Finding(FindingCode.EQUATION_EDITOR_EXPLOIT, Severity.DANGER, name.substringAfterLast('/'))
        }
        if (Bytes.indexOf(data, Bytes.utf16le("Ole10Native")) >= 0) {
            val exec = (EXECUTABLE_EXT + WINDOWS_SCRIPT_EXT + setOf("js", "jar")).firstOrNull {
                Bytes.contains(data, ".$it\u0000", ignoreCase = true)
            }
            if (exec != null) out += Finding(FindingCode.EMBEDDED_EXECUTABLE, Severity.DANGER, "${name.substringAfterLast('/')} (.$exec)")
        }
    }

    // ------------------------------------------------------------------------------------ PDF ----

    private val PDF_JS = listOf("/JavaScript", "/JS")
    private fun pdfName(data: ByteArray, len: Int, name: String): Int {
        // counts "/Name" occurrences that are complete names (next char is a delimiter)
        val needle = Bytes.ascii(name)
        var n = 0
        var i = Bytes.indexOf(data, needle, 0, len)
        while (i >= 0 && n < 10_000) {
            val next = if (i + needle.size < len) data[i + needle.size].toInt().toChar() else ' '
            if (!next.isLetterOrDigit()) n++
            i = Bytes.indexOf(data, needle, i + needle.size, len)
        }
        return n
    }

    private fun analyzePdf(file: File, out: MutableSet<Finding>) {
        val len = file.length()
        if (len > MAX_CONTENT_BYTES) out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
        val raw = Bytes.readHead(file, MAX_CONTENT_BYTES)

        val corpus = ArrayList<ByteArray>()
        corpus += raw
        corpus += inflatePdfStreams(raw)
        val hexNames = decodePdfHexNames(raw)
        corpus += hexNames

        fun count(name: String) = corpus.sumOf { pdfName(it, it.size, name) }

        val js = PDF_JS.sumOf { count(it) }
        val openAction = count("/OpenAction")
        val aa = count("/AA")
        val launch = count("/Launch")
        val embedded = count("/EmbeddedFile") + count("/EmbeddedFiles")
        val richMedia = count("/RichMedia")
        val xfa = count("/XFA")
        val submit = count("/SubmitForm") + count("/ImportData")
        val encrypt = count("/Encrypt")

        if (js > 0) out += Finding(FindingCode.PDF_JAVASCRIPT, Severity.WARNING, js.toString())
        if (js > 0 && (openAction > 0 || aa > 0)) out += Finding(FindingCode.PDF_AUTO_ACTION, Severity.DANGER)
        if (launch > 0) out += Finding(FindingCode.PDF_LAUNCH, Severity.DANGER)
        if (embedded > 0) {
            val exec = (EXECUTABLE_EXT + WINDOWS_SCRIPT_EXT + setOf("js", "jar", "apk")).firstOrNull { ext ->
                corpus.any { Bytes.contains(it, ".$ext)", ignoreCase = true) }
            }
            if (exec != null) out += Finding(FindingCode.EMBEDDED_EXECUTABLE, Severity.DANGER, ".$exec")
            else out += Finding(FindingCode.PDF_EMBEDDED_FILE, Severity.WARNING)
        }
        if (richMedia > 0) out += Finding(FindingCode.PDF_RICH_MEDIA, Severity.WARNING)
        if (xfa > 0) out += Finding(FindingCode.PDF_XFA, Severity.WARNING)
        if (submit > 0) out += Finding(FindingCode.PDF_SUBMIT_FORM, Severity.INFO)
        if (encrypt > 0) out += Finding(FindingCode.PDF_ENCRYPTED, Severity.INFO)
        // Hex-escaped names are legal (e.g. font names), but hiding *active* keywords that way is not.
        val hidden = listOf("/JavaScript", "/JS", "/OpenAction", "/AA", "/Launch", "/EmbeddedFile", "/RichMedia", "/URI")
            .filter { pdfName(hexNames, hexNames.size, it) > 0 }
        if (hidden.isNotEmpty()) out += Finding(FindingCode.PDF_OBFUSCATED_NAMES, Severity.WARNING, hidden.joinToString(" "))
    }

    /** Decodes names written as /J#61vaScript (a classic trick to hide keywords). */
    private fun decodePdfHexNames(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            if (raw[i] == '/'.code.toByte()) {
                var j = i + 1
                var hasHex = false
                val name = java.io.ByteArrayOutputStream()
                name.write('/'.code)
                while (j < raw.size && j - i < 128) {
                    val c = raw[j].toInt() and 0xFF
                    if (c == '#'.code && j + 2 < raw.size && isHex(raw[j + 1]) && isHex(raw[j + 2])) {
                        name.write(Character.digit(raw[j + 1].toInt(), 16) * 16 + Character.digit(raw[j + 2].toInt(), 16))
                        j += 3
                        hasHex = true
                        continue
                    }
                    if (c <= 32 || c in "/[]<>(){}%".map { it.code }) break
                    name.write(c)
                    j++
                }
                if (hasHex) {
                    out.write(name.toByteArray())
                    out.write(' '.code)
                }
                i = maxOf(j, i + 1)
            } else i++
        }
        return out.toByteArray()
    }

    private fun isHex(b: Byte) = Character.digit(b.toInt(), 16) >= 0

    private fun inflatePdfStreams(raw: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        val streamKw = Bytes.ascii("stream")
        val endKw = Bytes.ascii("endstream")
        var total = 0L
        var i = Bytes.indexOf(raw, streamKw)
        var n = 0
        val buf = ByteArray(64 * 1024)
        while (i >= 0 && n < MAX_PDF_STREAMS && total < MAX_PDF_INFLATE_TOTAL) {
            // skip "endstream" matches
            if (i >= 3 && raw[i - 3] == 'e'.code.toByte() && raw[i - 2] == 'n'.code.toByte() && raw[i - 1] == 'd'.code.toByte()) {
                i = Bytes.indexOf(raw, streamKw, i + streamKw.size); continue
            }
            var s = i + streamKw.size
            if (s < raw.size && raw[s] == '\r'.code.toByte()) s++
            if (s < raw.size && raw[s] == '\n'.code.toByte()) s++
            val e = Bytes.indexOf(raw, endKw, s)
            if (e < 0) break
            n++
            val inflater = Inflater()
            try {
                inflater.setInput(raw, s, e - s)
                val o = java.io.ByteArrayOutputStream()
                var guard = 0
                while (!inflater.finished() && guard++ < 4096 && o.size() < 16 * 1024 * 1024) {
                    val r = inflater.inflate(buf)
                    if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    o.write(buf, 0, r)
                }
                if (o.size() > 0) {
                    result += o.toByteArray()
                    total += o.size()
                }
            } catch (ex: Exception) {
                // not a Flate stream - ignore
            } finally {
                inflater.end()
            }
            i = Bytes.indexOf(raw, streamKw, e + endKw.size)
        }
        return result
    }

    // ------------------------------------------------------------------------------------ OLE ----

    private fun analyzeOle(file: File, out: MutableSet<Finding>) {
        if (file.length() > MAX_CONTENT_BYTES) out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
        val data = Bytes.readHead(file, MAX_CONTENT_BYTES)
        val hasVba = Bytes.indexOf(data, Bytes.utf16le("_VBA_PROJECT")) >= 0 ||
            Bytes.contains(data, "Attribute VB_Name") || Bytes.indexOf(data, Bytes.utf16le("VBA")) >= 0 &&
            Bytes.indexOf(data, Bytes.utf16le("PROJECTwm")) >= 0
        if (hasVba) {
            out += Finding(FindingCode.VBA_MACROS, Severity.DANGER)
            checkMacroCode(data, out)
        }
        if (Bytes.indexOf(data, Bytes.utf16le("Macros")) >= 0 && !hasVba) {
            out += Finding(FindingCode.VBA_MACROS, Severity.WARNING, "Macros storage")
        }
        if (Bytes.indexOf(data, Bytes.utf16le("Equation Native")) >= 0 || Bytes.contains(data, "Equation.3")) {
            out += Finding(FindingCode.EQUATION_EDITOR_EXPLOIT, Severity.WARNING)
        }
        if (Bytes.indexOf(data, Bytes.utf16le("ObjectPool")) >= 0 || Bytes.indexOf(data, Bytes.utf16le("Ole10Native")) >= 0) {
            out += Finding(FindingCode.EMBEDDED_OBJECT, Severity.WARNING)
            checkEmbedded("ObjectPool", data, out)
        }
        if (sniffExecutable(data.copyOfRange(512, data.size.coerceAtLeast(512))) != null) {
            out += Finding(FindingCode.EMBEDDED_EXECUTABLE, Severity.DANGER)
        }
        if (Bytes.indexOf(data, Bytes.utf16le("EncryptedPackage")) >= 0) {
            out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO, "encrypted")
        }
    }

    // ------------------------------------------------------------------------------------ RTF ----

    private fun analyzeRtf(file: File, out: MutableSet<Finding>) {
        if (file.length() > MAX_CONTENT_BYTES) out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
        val data = Bytes.readHead(file, MAX_CONTENT_BYTES)
        val hasObj = Bytes.contains(data, "\\objdata", true) || Bytes.contains(data, "\\object", true) || Bytes.contains(data, "\\objocx", true)
        if (hasObj) out += Finding(FindingCode.RTF_OBJECT, Severity.WARNING)
        if (Bytes.contains(data, "\\objupdate", true)) out += Finding(FindingCode.RTF_AUTO_UPDATE, Severity.DANGER)
        if (hasObj && (Bytes.contains(data, "equation", true) ||
                Bytes.contains(data, "4571756174696f6e", true) /* "Equation" hex-encoded */)) {
            out += Finding(FindingCode.EQUATION_EDITOR_EXPLOIT, Severity.DANGER)
        }
        if (hasObj && (Bytes.contains(data, "4d5a90", true) || Bytes.contains(data, "546869732070726f6772616d", true))) {
            out += Finding(FindingCode.EMBEDDED_EXECUTABLE, Severity.DANGER)
        }
        if (Bytes.contains(data, "\\objclass Package", true)) out += Finding(FindingCode.EMBEDDED_OBJECT, Severity.WARNING, "Package")
    }

    // ----------------------------------------------------------------------------- text / web ----

    private fun analyzeText(file: File, kind: FileKind, ext: String, out: MutableSet<Finding>) {
        val len = file.length()
        if (len > 16L * 1024 * 1024) out += Finding(FindingCode.PARTIAL_SCAN, Severity.INFO)
        var data = Bytes.readHead(file, 16 * 1024 * 1024)
        // UTF-16 text → decode to bytes we can search
        if (data.size >= 2 && ((data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xFE)) {
            data = String(data, Charsets.UTF_16LE).toByteArray(Charsets.UTF_8)
        } else if (data.size >= 2 && ((data[0].toInt() and 0xFF) == 0xFE && (data[1].toInt() and 0xFF) == 0xFF)) {
            data = String(data, Charsets.UTF_16BE).toByteArray(Charsets.UTF_8)
        }
        if (Bytes.contains(data, EICAR)) out += Finding(FindingCode.EICAR_TEST, Severity.DANGER)

        val scriptish = Bytes.contains(data, "<script", true) || Bytes.contains(data, "javascript:", true) ||
            Regex("\\son[a-z]{3,20}\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(String(data, 0, minOf(data.size, 2 * 1024 * 1024), Charsets.ISO_8859_1))
        when (kind) {
            FileKind.SVG -> if (scriptish || Bytes.contains(data, "<foreignObject", true)) out += Finding(FindingCode.SVG_SCRIPT, Severity.WARNING)
            FileKind.HTML -> {
                if (scriptish) out += Finding(FindingCode.HTML_SCRIPT, Severity.INFO)
                val password = Bytes.contains(data, "type=\"password\"", true) ||
                    Bytes.contains(data, "type='password'", true) || Bytes.contains(data, "type=password", true)
                if (Bytes.contains(data, "<form", true) && password) {
                    out += Finding(FindingCode.HTML_PASSWORD_FORM, Severity.WARNING)
                }
                if (Bytes.contains(data, "atob(", true) && Bytes.contains(data, "document.write", true)) {
                    out += Finding(FindingCode.SUSPICIOUS_COMMANDS, Severity.WARNING, "atob + document.write")
                }
            }
            else -> Unit
        }
        val hits = COMMAND_PATTERNS.filter { Bytes.contains(data, it, ignoreCase = true) }
        if (hits.isNotEmpty()) {
            val sev = if (hits.size >= 2 || ext in WINDOWS_SCRIPT_EXT) Severity.DANGER else Severity.WARNING
            out += Finding(FindingCode.SUSPICIOUS_COMMANDS, sev, hits.take(5).joinToString(", ") { it.trim() })
        }
    }
}
