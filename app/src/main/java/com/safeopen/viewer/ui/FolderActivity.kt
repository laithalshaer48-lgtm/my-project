package com.safeopen.viewer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safeopen.viewer.R
import com.safeopen.viewer.security.ScanReport
import com.safeopen.viewer.security.Scanner
import com.safeopen.viewer.security.Verdict
import com.safeopen.viewer.storage.Item
import com.safeopen.viewer.storage.Loc
import com.safeopen.viewer.storage.Storage
import com.safeopen.viewer.util.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.coroutines.coroutineContext

/** Browses a folder. Tapping a file always goes through the scanner first. */
class FolderActivity : BaseActivity() {

    companion object {
        private const val EXTRA_TREE = "tree"
        private const val EXTRA_DOC = "doc"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"

        fun open(ctx: Context, loc: Loc, title: String) {
            val i = Intent(ctx, FolderActivity::class.java).putExtra(EXTRA_TITLE, title)
            when (loc) {
                is Loc.Saf -> i.putExtra(EXTRA_TREE, loc.tree.toString()).putExtra(EXTRA_DOC, loc.docId)
                is Loc.Local -> i.putExtra(EXTRA_PATH, loc.path)
            }
            ctx.startActivity(i)
        }

        const val MAX_SCAN_FILES = 5000
        const val FULL_SCAN_LIMIT = 200L * 1024 * 1024
        const val PARTIAL_BYTES = 8L * 1024 * 1024
    }

    private lateinit var loc: Loc
    private lateinit var adapter: EntryAdapter
    private lateinit var empty: TextView
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val tree = intent.getStringExtra(EXTRA_TREE)
        val path = intent.getStringExtra(EXTRA_PATH)
        loc = when {
            tree != null -> Loc.Saf(Uri.parse(tree), intent.getStringExtra(EXTRA_DOC) ?: return finish())
            path != null -> Loc.Local(path)
            else -> return finish()
        }
        val frame = android.widget.FrameLayout(this)
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = EntryAdapter(emptyList())
        rv.adapter = adapter
        empty = TextView(this).apply {
            setText(R.string.empty_folder); textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 96, 0, 0); visibility = View.GONE
        }
        frame.addView(rv)
        frame.addView(empty)
        setContentView(frame)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { Storage.list(this@FolderActivity, loc) }
            } catch (e: SecurityException) {
                toast(R.string.err_permission); emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            supportActionBar?.subtitle = getString(R.string.files_count, items.size)
            adapter.submit(items.map { it.toRow() })
        }
    }

    private fun Item.toRow(): EntryAdapter.Row {
        val sub = if (isDir) "" else listOfNotNull(
            FileAccess.humanSize(size).takeIf { it.isNotEmpty() },
            modified.takeIf { it > 0 }?.let { DateFormat.getMediumDateFormat(this@FolderActivity).format(Date(it)) }
        ).joinToString(" · ")
        return EntryAdapter.Row(name, sub, EntryAdapter.iconFor(name, isDir)) {
            if (isDir) dir?.let { open(this@FolderActivity, it, name) }
            else uri?.let { ViewerActivity.open(this@FolderActivity, it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.scan_folder).setIcon(R.drawable.ic_scan).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> { scanFolder(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------------- folder scan ----

    private class Hit(val name: String, val uri: Uri, val report: ScanReport)

    private fun scanFolder() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val status = TextView(this)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            addView(bar); addView(status)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.scanning_folder)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> scanJob?.cancel() }
            .setCancelable(false)
            .show()

        scanJob = lifecycleScope.launch {
            val hits = ArrayList<Hit>()
            var count = 0
            var errors = 0
            try {
                withContext(Dispatchers.IO) {
                    suspend fun walk(l: Loc, depth: Int) {
                        if (depth > 24 || count >= MAX_SCAN_FILES) return
                        val items = try { Storage.list(this@FolderActivity, l) } catch (e: Exception) { errors++; return }
                        for (it in items) {
                            coroutineContext.ensureActive()
                            if (count >= MAX_SCAN_FILES) return
                            if (it.isDir) { it.dir?.let { d -> walk(d, depth + 1) }; continue }
                            val uri = it.uri ?: continue
                            count++
                            withContext(Dispatchers.Main) { status.text = getString(R.string.scan_progress, count, it.name) }
                            try {
                                val r = scanOne(uri, it)
                                if (r.verdict != Verdict.SAFE) hits += Hit(it.name, uri, r)
                            } catch (e: Exception) { errors++ }
                        }
                    }
                    walk(loc, 0)
                }
            } finally {
                dialog.dismiss()
            }
            showScanResult(count, hits, errors)
        }
    }

    private fun scanOne(uri: Uri, item: Item): ScanReport {
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            return Scanner.scan(f, item.name)
        }
        val truncate = item.size > FULL_SCAN_LIMIT
        val (local, truncated) = FileAccess.copyToCache(this, uri,
            maxBytes = if (truncate) PARTIAL_BYTES else FULL_SCAN_LIMIT, allowTruncate = true)
        try {
            return Scanner.scan(local.file, item.name, truncated = truncated)
        } finally {
            local.file.delete()
        }
    }

    private fun showScanResult(total: Int, hits: List<Hit>, errors: Int) {
        val dangerous = hits.count { it.report.verdict == Verdict.DANGEROUS }
        val suspicious = hits.count { it.report.verdict == Verdict.SUSPICIOUS }
        val summary = getString(R.string.scan_summary, total, dangerous, suspicious) +
            (if (errors > 0) "\n" + getString(R.string.scan_errors, errors) else "") +
            (if (total >= MAX_SCAN_FILES) "\n" + getString(R.string.scan_limit, MAX_SCAN_FILES) else "")
        val b = AlertDialog.Builder(this)
            .setTitle(if (hits.isEmpty()) R.string.scan_clean else R.string.scan_found)
            .setPositiveButton(android.R.string.ok, null)
        if (hits.isEmpty()) {
            b.setMessage(summary)
        } else {
            val sorted = hits.sortedByDescending { it.report.verdict.ordinal }
            val labels = sorted.map { h ->
                val icon = if (h.report.verdict == Verdict.DANGEROUS) "⛔" else "⚠️"
                val reason = h.report.findings.firstOrNull()?.let { ReportText.finding(this, it) } ?: ""
                "$icon ${h.name}\n   $reason"
            }.toTypedArray()
            b.setTitle(getString(R.string.scan_found) + " — " + summary.lineSequence().first())
            b.setItems(labels) { _, which -> ViewerActivity.open(this, sorted[which].uri) }
        }
        b.show()
    }

    private fun toast(res: Int) = android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
}
