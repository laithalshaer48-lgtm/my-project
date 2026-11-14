package com.safeopen.viewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.safeopen.viewer.R
import com.safeopen.viewer.security.FileKind
import com.safeopen.viewer.security.Finding
import com.safeopen.viewer.security.FindingCode
import com.safeopen.viewer.security.Verdict
import java.io.ByteArrayInputStream

object Prefs {
    private const val FILE = "safeopen"
    private const val KEY_SECURE = "block_screenshots"
    private const val KEY_FOLDERS = "folders"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun blockScreenshots(ctx: Context) = p(ctx).getBoolean(KEY_SECURE, false)
    fun setBlockScreenshots(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_SECURE, v).apply()

    fun folders(ctx: Context): List<String> = p(ctx).getStringSet(KEY_FOLDERS, emptySet())!!.sorted()
    fun addFolder(ctx: Context, uri: String) = p(ctx).edit().putStringSet(KEY_FOLDERS, folders(ctx).toSet() + uri).apply()
    fun removeFolder(ctx: Context, uri: String) = p(ctx).edit().putStringSet(KEY_FOLDERS, folders(ctx).toSet() - uri).apply()
}

/** Applies the "block screenshots" preference to every screen. */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
    }

    fun applySecureFlag() {
        if (Prefs.blockScreenshots(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** WebView locked down for displaying untrusted content: no JS, no network, no file/content access. */
object SecureWeb {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = false // needs network, which this app does not have
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        w.isLongClickable = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val scheme = request.url.scheme
                return if (scheme == "data" || scheme == "about") null
                else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        w.webChromeClient = null
        return w
    }

    fun load(w: WebView, html: String) = w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

object ReportText {
    fun verdictTitle(v: Verdict) = when (v) {
        Verdict.SAFE -> R.string.verdict_safe
        Verdict.SUSPICIOUS -> R.string.verdict_suspicious
        Verdict.DANGEROUS -> R.string.verdict_dangerous
    }

    fun verdictColor(ctx: Context, v: Verdict) = ContextCompat.getColor(ctx, when (v) {
        Verdict.SAFE -> R.color.safe; Verdict.SUSPICIOUS -> R.color.warn; Verdict.DANGEROUS -> R.color.danger
    })

    fun verdictBg(ctx: Context, v: Verdict) = ContextCompat.getColor(ctx, when (v) {
        Verdict.SAFE -> R.color.safe_bg; Verdict.SUSPICIOUS -> R.color.warn_bg; Verdict.DANGEROUS -> R.color.danger_bg
    })

    fun verdictIcon(v: Verdict) = when (v) {
        Verdict.SAFE -> R.drawable.ic_shield; Verdict.SUSPICIOUS -> R.drawable.ic_warning; Verdict.DANGEROUS -> R.drawable.ic_danger
    }

    fun finding(ctx: Context, f: Finding): String {
        val res = when (f.code) {
            FindingCode.EICAR_TEST -> R.string.f_eicar
            FindingCode.KNOWN_MALWARE_HASH -> R.string.f_known_hash
            FindingCode.EXECUTABLE_WINDOWS -> R.string.f_exe_windows
            FindingCode.EXECUTABLE_LINUX -> R.string.f_exe_linux
            FindingCode.EXECUTABLE_MAC -> R.string.f_exe_mac
            FindingCode.EXECUTABLE_DEX -> R.string.f_exe_dex
            FindingCode.ANDROID_APP -> R.string.f_apk
            FindingCode.JAVA_ARCHIVE -> R.string.f_jar
            FindingCode.DANGEROUS_EXTENSION -> R.string.f_dangerous_ext
            FindingCode.SCRIPT_FILE -> R.string.f_script
            FindingCode.DOUBLE_EXTENSION -> R.string.f_double_ext
            FindingCode.RTL_OVERRIDE_NAME -> R.string.f_rtlo
            FindingCode.TYPE_MISMATCH -> R.string.f_mismatch
            FindingCode.VBA_MACROS -> R.string.f_vba
            FindingCode.MACRO_ENABLED_FORMAT -> R.string.f_macro_format
            FindingCode.XLM_MACROS -> R.string.f_xlm
            FindingCode.AUTO_EXEC_MACRO -> R.string.f_autoexec
            FindingCode.MACRO_SUSPICIOUS_CALLS -> R.string.f_macro_calls
            FindingCode.ACTIVEX -> R.string.f_activex
            FindingCode.EMBEDDED_OBJECT -> R.string.f_embedded_object
            FindingCode.EMBEDDED_EXECUTABLE -> R.string.f_embedded_exe
            FindingCode.EXTERNAL_TEMPLATE -> R.string.f_external_template
            FindingCode.FOLLINA_MSDT -> R.string.f_follina
            FindingCode.DDE_FIELD -> R.string.f_dde
            FindingCode.EXTERNAL_LINKS -> R.string.f_links
            FindingCode.ODF_MACROS -> R.string.f_odf_macros
            FindingCode.EQUATION_EDITOR_EXPLOIT -> R.string.f_equation
            FindingCode.RTF_OBJECT -> R.string.f_rtf_object
            FindingCode.RTF_AUTO_UPDATE -> R.string.f_rtf_autoupdate
            FindingCode.PDF_JAVASCRIPT -> R.string.f_pdf_js
            FindingCode.PDF_AUTO_ACTION -> R.string.f_pdf_auto
            FindingCode.PDF_LAUNCH -> R.string.f_pdf_launch
            FindingCode.PDF_EMBEDDED_FILE -> R.string.f_pdf_embedded
            FindingCode.PDF_RICH_MEDIA -> R.string.f_pdf_richmedia
            FindingCode.PDF_XFA -> R.string.f_pdf_xfa
            FindingCode.PDF_SUBMIT_FORM -> R.string.f_pdf_submit
            FindingCode.PDF_OBFUSCATED_NAMES -> R.string.f_pdf_obfuscated
            FindingCode.PDF_ENCRYPTED -> R.string.f_pdf_encrypted
            FindingCode.ZIP_BOMB -> R.string.f_zip_bomb
            FindingCode.ZIP_PATH_TRAVERSAL -> R.string.f_zip_traversal
            FindingCode.ZIP_ENCRYPTED -> R.string.f_zip_encrypted
            FindingCode.ZIP_TOO_MANY_ENTRIES -> R.string.f_zip_many
            FindingCode.ARCHIVE_UNSCANNABLE -> R.string.f_archive_unscannable
            FindingCode.NESTED_EXECUTABLE -> R.string.f_nested_exe
            FindingCode.MALFORMED -> R.string.f_malformed
            FindingCode.HTML_SCRIPT -> R.string.f_html_script
            FindingCode.HTML_PASSWORD_FORM -> R.string.f_html_password
            FindingCode.SVG_SCRIPT -> R.string.f_svg_script
            FindingCode.SUSPICIOUS_COMMANDS -> R.string.f_commands
            FindingCode.PARTIAL_SCAN -> R.string.f_partial
            FindingCode.EMPTY_FILE -> R.string.f_empty
        }
        return ctx.getString(res)
    }

    fun kindName(ctx: Context, k: FileKind): String = when (k) {
        FileKind.PDF -> "PDF"
        FileKind.DOCX, FileKind.DOC -> ctx.getString(R.string.kind_word)
        FileKind.XLSX, FileKind.XLS -> ctx.getString(R.string.kind_excel)
        FileKind.PPTX, FileKind.PPT -> ctx.getString(R.string.kind_powerpoint)
        FileKind.ODT, FileKind.ODS, FileKind.ODP -> "OpenDocument"
        FileKind.EPUB -> "EPUB"
        FileKind.RTF -> "RTF"
        FileKind.IMAGE, FileKind.SVG -> ctx.getString(R.string.kind_image)
        FileKind.VIDEO -> ctx.getString(R.string.kind_video)
        FileKind.AUDIO -> ctx.getString(R.string.kind_audio)
        FileKind.HTML -> "HTML"
        FileKind.CSV -> "CSV"
        FileKind.TEXT -> ctx.getString(R.string.kind_text)
        FileKind.ZIP, FileKind.RAR, FileKind.SEVEN_Z, FileKind.GZIP -> ctx.getString(R.string.kind_archive)
        FileKind.APK -> ctx.getString(R.string.kind_apk)
        FileKind.JAR -> "Java"
        FileKind.EXE_WINDOWS, FileKind.EXE_LINUX, FileKind.EXE_MAC, FileKind.DEX -> ctx.getString(R.string.kind_program)
        FileKind.OOXML_OTHER, FileKind.OLE_OTHER -> "Office"
        FileKind.EMPTY -> ctx.getString(R.string.kind_empty)
        FileKind.UNKNOWN -> ctx.getString(R.string.kind_unknown)
    }
}
