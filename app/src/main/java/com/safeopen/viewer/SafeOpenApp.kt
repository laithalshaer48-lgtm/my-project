package com.safeopen.viewer

import android.app.Application
import android.os.Build
import android.webkit.WebView
import com.safeopen.viewer.util.FileAccess

class SafeOpenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Leftover copies from a previous session are removed.
        FileAccess.cleanAll(this)
        // Android 14 rejects ZIP entries with "../" before we can see them. We never extract
        // using entry names, so we turn that off to *detect and report* such archives ourselves.
        if (Build.VERSION.SDK_INT >= 34) {
            try { dalvik.system.ZipPathValidator.clearCallback() } catch (_: Throwable) {}
        }
        WebView.setWebContentsDebuggingEnabled(false)
    }
}
