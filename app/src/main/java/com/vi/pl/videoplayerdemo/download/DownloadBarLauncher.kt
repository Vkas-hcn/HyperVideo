package com.vi.pl.videoplayerdemo.download

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

object DownloadBarLauncher {
    private const val TAG = "DownloadBarLauncher"

    fun ensureRunning(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, DownloadBarService::class.java)
            .setAction(DownloadBarService.ACTION_SHOW_BAR)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { Log.w(TAG, "ensureRunning failed", it) }
    }
}
