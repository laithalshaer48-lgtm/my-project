package com.safeopen.viewer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.safeopen.viewer.util.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen, zoomable view of a single PDF page rendered at high resolution. */
class ZoomImageActivity : BaseActivity() {

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_TITLE = "title"

        fun openPdfPage(ctx: Context, file: File, page: Int, title: String) {
            ctx.startActivity(Intent(ctx, ZoomImageActivity::class.java)
                .putExtra(EXTRA_FILE, file.absolutePath).putExtra(EXTRA_PAGE, page).putExtra(EXTRA_TITLE, title))
        }
    }

    private var doc: PdfDocument? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ZoomImageView(this)
        setContentView(view)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = intent.getIntExtra(EXTRA_PAGE, 0)
        title = "${intent.getStringExtra(EXTRA_TITLE) ?: ""} · ${page + 1}"
        val path = intent.getStringExtra(EXTRA_FILE) ?: return finish()
        val file = File(path).canonicalFile
        if (!file.path.startsWith(FileAccess.workDir(this).canonicalPath)) return finish()
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val d = PdfDocument(file).also { doc = it }
                    val w = (resources.displayMetrics.widthPixels * 2.5).toInt()
                    d.render(page, w)
                }
                view.setImageBitmap(bmp)
            } catch (e: Throwable) {
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        try { doc?.close() } catch (_: Exception) {}
    }
}
