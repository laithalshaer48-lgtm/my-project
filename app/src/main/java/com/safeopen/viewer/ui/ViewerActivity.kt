package com.safeopen.viewer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.safeopen.viewer.R
import com.safeopen.viewer.render.DocxRenderer
import com.safeopen.viewer.render.EpubRenderer
import com.safeopen.viewer.render.Html
import com.safeopen.viewer.render.OdfRenderer
import com.safeopen.viewer.render.PptxRenderer
import com.safeopen.viewer.render.TextRenderer
import com.safeopen.viewer.render.XlsxRenderer
import com.safeopen.viewer.security.FileKind
import com.safeopen.viewer.security.ScanReport
import com.safeopen.viewer.security.Scanner
import com.safeopen.viewer.security.Severity
import com.safeopen.viewer.security.Verdict
import com.safeopen.viewer.security.ZipInspector
import com.safeopen.viewer.util.Bytes
import com.safeopen.viewer.util.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Scan first, then show. Nothing in a file is ever executed: documents are converted to
 * inert HTML (no JavaScript, no network) or drawn by Android's own PDF/image/media engines.
 */
class ViewerActivity : BaseActivity() {

    companion object {
        private const val EXTRA_TEMP = "temp"
        private const val EXTRA_NAME = "name"

        fun open(ctx: Context, uri: Uri) {
            // The URI goes in the data field so the read grant from the sending app carries over.
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

        /** Opens a temp file we extracted ourselves (ownership moves to the new screen). */
        fun openTemp(ctx: Context, file: File, name: String) {
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java).putExtra(EXTRA_TEMP, file.absolutePath).putExtra(EXTRA_NAME, name))
        }
    }

    private lateinit var content: FrameLayout
    private lateinit var progress: View
    private lateinit var progressText: TextView
    private lateinit var banner: LinearLayout
    private lateinit var tabs: TabLayout

    private var sourceUri: Uri? = null
    private var local: File? = null
    private var name: String = ""
    private var report: ScanReport? = null
    private var pdf: PdfDocument? = null
    private var pdfAdapter: PdfPageAdapter? = null
    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        banner = findViewById(R.id.banner)
        tabs = findViewById(R.id.tabs)
        banner.setOnClickListener { report?.let { showReport(it) } }

        val temp = intent.getStringExtra(EXTRA_TEMP)
        sourceUri = intent.data

        lifecycleScope.launch {
            try {
                val (file, displayName) = withContext(Dispatchers.IO) {
                    if (temp != null) {
                        val f = File(temp).canonicalFile
                        // Only our own private work folder is accepted for temp files.
                        if (!f.path.startsWith(FileAccess.workDir(this@ViewerActivity).canonicalPath)) throw FileAccess.Rejected("temp")
                        f to (intent.getStringExtra(EXTRA_NAME) ?: f.name)
                    } else {
                        val uri = FileAccess.validate(this@ViewerActivity, sourceUri ?: throw FileAccess.Rejected("no uri"))
                        val (l, _) = FileAccess.copyToCache(this@ViewerActivity, uri)
                        l.file to l.name
                    }
                }
                local = file
                name = displayName
                title = displayName
                progressText.setText(R.string.scanning)
                val r = withContext(Dispatchers.Default) { Scanner.scan(file, displayName) }
                report = r
                showBanner(r)
                invalidateOptionsMenu()
                when (r.verdict) {
                    Verdict.SAFE -> render(r.kind)
                    Verdict.SUSPICIOUS -> showGate(r)
                    Verdict.DANGEROUS -> showGate(r)
                }
            } catch (e: FileAccess.TooLarge) {
                showMessage(getString(R.string.err_too_large, FileAccess.humanSize(e.size)))
            } catch (e: FileAccess.Rejected) {
                showMessage(getString(R.string.err_rejected))
            } catch (e: SecurityException) {
                showMessage(getString(R.string.err_permission))
            } catch (e: Exception) {
                showMessage(getString(R.string.err_open, e.javaClass.simpleName))
            }
        }
    }

    // ------------------------------------------------------------------------------- report ----

    private fun showBanner(r: ScanReport) {
        banner.isVisible = true
        banner.setBackgroundColor(ReportText.verdictBg(this, r.verdict))
        val icon = findViewById<ImageView>(R.id.bannerIcon)
        icon.setImageResource(ReportText.verdictIcon(r.verdict))
        icon.setColorFilter(ReportText.verdictColor(this, r.verdict))
        val text = findViewById<TextView>(R.id.bannerText)
        text.setText(ReportText.verdictTitle(r.verdict))
        text.setTextColor(ReportText.verdictColor(this, r.verdict))
    }

    private fun reportView(r: ScanReport): View {
        val pad = dp(16)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad) }
        fun line(label: String, value: String, mono: Boolean = false) {
            box.addView(TextView(this).apply {
                text = label; textSize = 12f; alpha = 0.7f; setPadding(0, dp(8), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = value; textSize = 14f; setTextIsSelectable(true)
                if (mono) typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        line(getString(R.string.r_name), r.fileName)
        line(getString(R.string.r_type), ReportText.kindName(this, r.kind))
        line(getString(R.string.r_size), FileAccess.humanSize(r.size))
        line("SHA-256", r.sha256, mono = true)
        box.addView(TextView(this).apply {
            setText(R.string.r_findings); textSize = 12f; alpha = 0.7f; setPadding(0, dp(14), 0, dp(4))
        })
        if (r.findings.isEmpty()) {
            box.addView(TextView(this).apply { setText(R.string.r_no_findings); textSize = 14f })
        }
        for (f in r.findings) {
            val color = ContextCompat.getColor(this, when (f.severity) {
                Severity.DANGER -> R.color.danger; Severity.WARNING -> R.color.warn; Severity.INFO -> R.color.brand
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(ImageView(this).apply {
                setImageResource(when (f.severity) { Severity.DANGER -> R.drawable.ic_danger; Severity.WARNING -> R.drawable.ic_warning; Severity.INFO -> R.drawable.ic_info })
                setColorFilter(color)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10); topMargin = dp(2) }
            })
            row.addView(TextView(this).apply {
                text = ReportText.finding(this@ViewerActivity, f) + if (f.detail.isNotBlank()) "\n${f.detail}" else ""
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            box.addView(row)
        }
        box.addView(TextView(this).apply {
            setText(R.string.r_disclaimer); textSize = 12f; alpha = 0.7f; setPadding(0, dp(16), 0, 0)
        })
        return box
    }

    private fun showReport(r: ScanReport) {
        AlertDialog.Builder(this)
            .setTitle(ReportText.verdictTitle(r.verdict))
            .setView(ScrollView(this).apply { addView(reportView(r)) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGate(r: ScanReport) {
        progress.isVisible = false
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(ImageView(this).apply {
            setImageResource(ReportText.verdictIcon(r.verdict))
            setColorFilter(ReportText.verdictColor(this@ViewerActivity, r.verdict))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        })
        col.addView(TextView(this).apply {
            setText(ReportText.verdictTitle(r.verdict)); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(ReportText.verdictColor(this@ViewerActivity, r.verdict)); setPadding(0, dp(10), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            setText(if (r.verdict == Verdict.DANGEROUS) R.string.gate_danger_text else R.string.gate_warn_text)
            textSize = 15f; gravity = Gravity.CENTER
        })
        col.addView(reportView(r).apply { setPadding(0, dp(8), 0, dp(8)) })
        if (r.verdict == Verdict.SUSPICIOUS) {
            col.addView(MaterialButton(this).apply {
                setText(R.string.open_protected)
                setOnClickListener { render(r.kind) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.view_raw)
            setOnClickListener { renderRaw() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContent(ScrollView(this).apply { addView(col) })
    }

    // ------------------------------------------------------------------------------- render ----

    private fun setContent(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showMessage(msg: String) {
        progress.isVisible = false
        setContent(TextView(this).apply {
            text = msg; gravity = Gravity.CENTER; textSize = 16f; setPadding(dp(24), dp(24), dp(24), dp(24))
        })
    }

    private fun showLoading() {
        content.removeAllViews()
        progressText.setText(R.string.rendering)
        content.addView(progress)
        progress.isVisible = true
    }

    private fun showHtml(producer: () -> String) {
        showLoading()
        lifecycleScope.launch {
            try {
                val html = withContext(Dispatchers.Default) { producer() }
                val w = SecureWeb.create(this@ViewerActivity)
                setContent(w)
                SecureWeb.load(w, html)
            } catch (e: OutOfMemoryError) {
                renderFallback(getString(R.string.err_render))
            } catch (e: Exception) {
                renderFallback(getString(R.string.err_render))
            }
        }
    }

    private fun renderFallback(msg: String) {
        val f = local ?: return
        showHtml { TextRenderer.hex(f, msg) }
    }

    private fun renderRaw() {
        val f = local ?: return
        val kind = report?.kind
        val textual = kind == FileKind.TEXT || kind == FileKind.HTML || kind == FileKind.SVG || kind == FileKind.CSV ||
            kind == FileKind.RTF || Bytes.looksLikeText(Bytes.readHead(f, 4096))
        showHtml { if (textual) TextRenderer.text(f) else TextRenderer.hex(f) }
    }

    private fun render(kind: FileKind) {
        val f = local ?: return
        tabs.isVisible = false
        when (kind) {
            FileKind.PDF -> renderPdf(f)
            FileKind.DOCX -> showHtml { DocxRenderer.render(f) }
            FileKind.XLSX -> renderSheets(f)
            FileKind.PPTX -> showHtml { PptxRenderer.render(f) }
            FileKind.ODT, FileKind.ODS, FileKind.ODP -> showHtml { OdfRenderer.render(f) }
            FileKind.EPUB -> showHtml { EpubRenderer.render(f) }
            FileKind.OOXML_OTHER -> showHtml { DocxRenderer.render(f) }
            FileKind.DOC, FileKind.XLS, FileKind.PPT, FileKind.OLE_OTHER -> {
                val notice = getString(R.string.legacy_notice)
                showHtml { TextRenderer.extractStrings(f, notice) }
            }
            FileKind.RTF -> showHtml { TextRenderer.rtf(f) }
            FileKind.CSV -> showHtml { TextRenderer.csv(f) }
            FileKind.TEXT -> showHtml { TextRenderer.text(f) }
            FileKind.HTML -> {
                val notice = getString(R.string.html_notice)
                showHtml {
                val src = TextRenderer.decode(Bytes.readHead(f, TextRenderer.MAX_TEXT))
                Html.page(Html.note(notice) + "<div class=\"paper\">" + EpubRenderer.sanitize(src, "", null) + "</div>")
                }
            }
            FileKind.SVG -> showHtml {
                val data = Bytes.readHead(f, 16 * 1024 * 1024)
                // As an <img>, SVG scripts and links can never run.
                Html.page("<div style=\"text-align:center\"><img style=\"max-width:100%;background:#fff\" src=\"data:image/svg+xml;base64,${Html.base64(data)}\"></div>")
            }
            FileKind.IMAGE -> renderImage(f)
            FileKind.VIDEO, FileKind.AUDIO -> renderMedia(f, kind)
            FileKind.ZIP, FileKind.APK, FileKind.JAR -> renderArchive(f)
            FileKind.EMPTY -> showMessage(getString(R.string.empty_file))
            else -> {
                val notice = getString(R.string.unknown_notice)
                showHtml { TextRenderer.hex(f, notice) }
            }
        }
    }

    private fun renderPdf(f: File) {
        showLoading()
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { PdfDocument(f) }
                pdf = doc
                val rv = RecyclerView(this@ViewerActivity)
                rv.layoutManager = LinearLayoutManager(this@ViewerActivity)
                rv.setBackgroundColor(0xFF3B3F47.toInt())
                rv.setPadding(dp(6), 0, dp(6), 0)
                rv.clipToPadding = false
                val adapter = PdfPageAdapter(doc) { page -> ZoomImageActivity.openPdfPage(this@ViewerActivity, f, page, name) }
                pdfAdapter = adapter
                rv.adapter = adapter
                setContent(rv)
                supportActionBar?.subtitle = getString(R.string.pages, doc.pageCount)
            } catch (e: SecurityException) {
                showMessage(getString(R.string.err_pdf_password))
            } catch (e: Exception) {
                renderFallback(getString(R.string.err_render))
            }
        }
    }

    private fun renderSheets(f: File) {
        showLoading()
        lifecycleScope.launch {
            try {
                val sheets = withContext(Dispatchers.Default) { XlsxRenderer.render(f) }
                if (sheets.isEmpty()) { showMessage(getString(R.string.empty_file)); return@launch }
                val w = SecureWeb.create(this@ViewerActivity)
                setContent(w)
                tabs.removeAllTabs()
                tabs.clearOnTabSelectedListeners()
                sheets.forEach { tabs.addTab(tabs.newTab().setText(it.name)) }
                tabs.isVisible = sheets.size > 1
                tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) { SecureWeb.load(w, sheets[tab.position].html) }
                    override fun onTabUnselected(tab: TabLayout.Tab) {}
                    override fun onTabReselected(tab: TabLayout.Tab) {}
                })
                SecureWeb.load(w, sheets[0].html)
            } catch (e: Throwable) {
                renderFallback(getString(R.string.err_render))
            }
        }
    }

    private fun renderImage(f: File) {
        showLoading()
        lifecycleScope.launch {
            try {
                val drawable = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val src = ImageDecoder.createSource(f)
                        ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                            val max = 4096
                            val s = maxOf(info.size.width, info.size.height)
                            if (s > max) decoder.setTargetSampleSize((s + max - 1) / max)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.path, o)
                        var sample = 1
                        while (maxOf(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
                        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IllegalStateException("decode")
                        android.graphics.drawable.BitmapDrawable(resources, bmp)
                    }
                }
                val iv = ZoomImageView(this@ViewerActivity)
                iv.setBackgroundColor(0xFF000000.toInt())
                iv.setImageDrawable(drawable)
                if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.start()
                setContent(iv)
            } catch (e: Throwable) {
                renderFallback(getString(R.string.err_render))
            }
        }
    }

    private fun renderMedia(f: File, kind: FileKind) {
        progress.isVisible = false
        val frame = FrameLayout(this)
        frame.setBackgroundColor(0xFF000000.toInt())
        val v = VideoView(this)
        video = v
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (kind == FileKind.AUDIO) {
            frame.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_media); setColorFilter(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }
        val mc = MediaController(this)
        mc.setAnchorView(frame)
        v.setMediaController(mc)
        v.setOnErrorListener { _, _, _ -> renderFallback(getString(R.string.err_media)); true }
        v.setOnPreparedListener { mc.show(0) }
        v.setVideoURI(Uri.fromFile(f))
        setContent(frame)
        frame.setOnClickListener { mc.show() }
        v.start()
    }

    private fun renderArchive(f: File) {
        showLoading()
        lifecycleScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) { ZipInspector.list(f, 20_000) }
                val entries = listing.entries.filter { !it.isDirectory }
                val rv = RecyclerView(this@ViewerActivity)
                rv.layoutManager = LinearLayoutManager(this@ViewerActivity)
                rv.adapter = EntryAdapter(entries.map { e ->
                    EntryAdapter.Row(e.name, buildString {
                        append(FileAccess.humanSize(e.size))
                        if (e.encrypted) append(" · 🔒")
                    }, R.drawable.ic_file) {
                        if (report?.verdict == Verdict.DANGEROUS) return@Row
                        if (e.encrypted) { toast(R.string.encrypted_entry); return@Row }
                        extractAndOpen(f, e)
                    }
                })
                setContent(rv)
                supportActionBar?.subtitle = getString(R.string.files_count, entries.size)
            } catch (e: Exception) {
                renderFallback(getString(R.string.err_render))
            }
        }
    }

    private fun extractAndOpen(zipFile: File, e: ZipInspector.Entry) {
        if (ZipInspector.isTraversal(e.name)) { toast(R.string.err_rejected); return }
        if (e.size > FileAccess.MAX_OPEN_BYTES) { toast(R.string.err_rejected); return }
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    val target = FileAccess.newTempFile(this@ViewerActivity)  // random name: entry names are never used as paths
                    ZipFile(zipFile).use { z ->
                        val entry = z.getEntry(e.name) ?: throw IllegalStateException("entry")
                        z.getInputStream(entry).use { input ->
                            target.outputStream().use { o ->
                                val buf = ByteArray(128 * 1024)
                                var total = 0L
                                while (true) {
                                    val r = input.read(buf)
                                    if (r <= 0) break
                                    total += r
                                    if (total > FileAccess.MAX_OPEN_BYTES) throw FileAccess.TooLarge(total)
                                    o.write(buf, 0, r)
                                }
                            }
                        }
                    }
                    target
                }
                openTemp(this@ViewerActivity, out, e.name.substringAfterLast('/'))
            } catch (ex: Exception) {
                toast(R.string.err_render)
            }
        }
    }

    // --------------------------------------------------------------------------------- menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val r = report ?: return true
        menu.add(0, 1, 0, R.string.security_report).setIcon(R.drawable.ic_shield)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, R.string.view_raw)
        if (sourceUri != null && r.verdict != Verdict.DANGEROUS) menu.add(0, 3, 2, R.string.open_with)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> { report?.let { showReport(it) }; return true }
            2 -> { tabs.isVisible = false; renderRaw(); return true }
            3 -> {
                val uri = sourceUri ?: return true
                AlertDialog.Builder(this)
                    .setMessage(R.string.open_with_warning)
                    .setPositiveButton(R.string.continue_) { _, _ ->
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { startActivity(Intent.createChooser(i, getString(R.string.open_with))) } catch (e: ActivityNotFoundException) { toast(R.string.no_app) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfAdapter?.shutdown()
        try { pdf?.close() } catch (_: Exception) {}
        video?.stopPlayback()
        if (isFinishing) local?.delete()
        for (i in 0 until content.childCount) (content.getChildAt(i) as? WebView)?.destroy()
        content.removeAllViews()
    }

    private fun toast(res: Int) = android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
