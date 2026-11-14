package com.safeopen.viewer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.safeopen.viewer.R
import com.safeopen.viewer.util.FileAccess

/**
 * The only screen other apps can start ("Open with…" / "Share to…"). It accepts nothing
 * but a validated file URI and hands it to the private viewer, which scans it first.
 */
class OpenFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        try {
            if (uri == null) throw FileAccess.Rejected("no uri")
            val safe = FileAccess.validate(this, uri)
            ViewerActivity.open(this, safe)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_rejected, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
