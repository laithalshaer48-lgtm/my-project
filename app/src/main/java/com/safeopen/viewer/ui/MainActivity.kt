package com.safeopen.viewer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safeopen.viewer.BuildConfig
import com.safeopen.viewer.R
import com.safeopen.viewer.storage.Loc
import com.safeopen.viewer.storage.Storage

class MainActivity : BaseActivity() {

    private lateinit var foldersAdapter: EntryAdapter

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ViewerActivity.open(this, uri)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Prefs.addFolder(this, uri.toString())
            } catch (_: SecurityException) {}
            refreshFolders()
            FolderActivity.open(this, Storage.rootOf(uri), Storage.treeLabel(uri))
        }
    }

    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openStorageRoot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.openFile).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.openFolder).setOnClickListener {
            try { pickFolder.launch(null) } catch (e: ActivityNotFoundException) { }
        }
        findViewById<View>(R.id.openStorage).setOnClickListener { requestStorage() }
        val rv = findViewById<RecyclerView>(R.id.folders)
        rv.layoutManager = LinearLayoutManager(this)
        foldersAdapter = EntryAdapter(emptyList())
        rv.adapter = foldersAdapter
    }

    override fun onResume() {
        super.onResume()
        applySecureFlag()
        refreshFolders()
    }

    private fun refreshFolders() {
        val persisted = contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val folders = Prefs.folders(this).filter { it in persisted }
        findViewById<TextView>(R.id.foldersTitle).visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
        foldersAdapter.submit(folders.map { s ->
            val uri = Uri.parse(s)
            val label = Storage.treeLabel(uri)
            EntryAdapter.Row(label, getString(R.string.long_press_remove), R.drawable.ic_folder,
                onLongClick = { confirmRemove(uri, label) }) {
                FolderActivity.open(this, Storage.rootOf(uri), label)
            }
        })
    }

    private fun confirmRemove(uri: Uri, label: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_folder_q, label))
            .setPositiveButton(R.string.remove) { _, _ ->
                try { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                Prefs.removeFolder(this, uri.toString())
                refreshFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (hasStorageAccess()) { openStorageRoot(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_storage)
            .setMessage(R.string.storage_explain)
            .setPositiveButton(R.string.continue_) { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openStorageRoot() {
        val root = Environment.getExternalStorageDirectory()
        FolderActivity.open(this, Loc.Local(root.path), getString(R.string.phone_storage))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.block_screenshots).setCheckable(true).isChecked = Prefs.blockScreenshots(this)
        menu.add(0, 2, 1, R.string.about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val v = !item.isChecked
                item.isChecked = v
                Prefs.setBlockScreenshots(this, v)
                applySecureFlag()
                return true
            }
            2 -> {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
