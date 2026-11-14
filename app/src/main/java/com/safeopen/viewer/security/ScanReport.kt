package com.safeopen.viewer.security

enum class Severity { INFO, WARNING, DANGER }

enum class Verdict { SAFE, SUSPICIOUS, DANGEROUS }

/** What the file really is, decided from its bytes (not just its name). */
enum class FileKind {
    PDF, DOCX, XLSX, PPTX, OOXML_OTHER, ODT, ODS, ODP, EPUB,
    DOC, XLS, PPT, OLE_OTHER, RTF,
    IMAGE, SVG, VIDEO, AUDIO, HTML, CSV, TEXT,
    ZIP, APK, JAR, RAR, SEVEN_Z, GZIP,
    EXE_WINDOWS, EXE_LINUX, EXE_MAC, DEX,
    EMPTY, UNKNOWN;

    val isExecutable get() = this == EXE_WINDOWS || this == EXE_LINUX || this == EXE_MAC || this == DEX
}

/** Every finding the scanner can raise. The UI maps each code to a localized explanation. */
enum class FindingCode {
    // Signatures
    EICAR_TEST, KNOWN_MALWARE_HASH,
    // Executables / installers
    EXECUTABLE_WINDOWS, EXECUTABLE_LINUX, EXECUTABLE_MAC, EXECUTABLE_DEX, ANDROID_APP, JAVA_ARCHIVE,
    // Names
    DANGEROUS_EXTENSION, SCRIPT_FILE, DOUBLE_EXTENSION, RTL_OVERRIDE_NAME, TYPE_MISMATCH,
    // Office
    VBA_MACROS, MACRO_ENABLED_FORMAT, XLM_MACROS, AUTO_EXEC_MACRO, MACRO_SUSPICIOUS_CALLS, ACTIVEX,
    EMBEDDED_OBJECT, EMBEDDED_EXECUTABLE, EXTERNAL_TEMPLATE, FOLLINA_MSDT, DDE_FIELD, EXTERNAL_LINKS,
    ODF_MACROS, EQUATION_EDITOR_EXPLOIT, RTF_OBJECT, RTF_AUTO_UPDATE,
    // PDF
    PDF_JAVASCRIPT, PDF_AUTO_ACTION, PDF_LAUNCH, PDF_EMBEDDED_FILE, PDF_RICH_MEDIA, PDF_XFA,
    PDF_SUBMIT_FORM, PDF_OBFUSCATED_NAMES, PDF_ENCRYPTED,
    // Archives
    ZIP_BOMB, ZIP_PATH_TRAVERSAL, ZIP_ENCRYPTED, ZIP_TOO_MANY_ENTRIES, ARCHIVE_UNSCANNABLE,
    NESTED_EXECUTABLE, MALFORMED,
    // Web / text
    HTML_SCRIPT, HTML_PASSWORD_FORM, SVG_SCRIPT, SUSPICIOUS_COMMANDS,
    // Informational
    PARTIAL_SCAN, EMPTY_FILE
}

data class Finding(
    val code: FindingCode,
    val severity: Severity,
    /** Optional technical detail (entry name, matched keyword...). Never localized. */
    val detail: String = ""
)

data class ScanReport(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val kind: FileKind,
    val findings: List<Finding>,
    val durationMs: Long
) {
    val verdict: Verdict
        get() = when {
            findings.any { it.severity == Severity.DANGER } -> Verdict.DANGEROUS
            findings.any { it.severity == Severity.WARNING } -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }
}
