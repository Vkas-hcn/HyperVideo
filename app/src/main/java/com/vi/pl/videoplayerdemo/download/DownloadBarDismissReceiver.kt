package com.vi.pl.videoplayerdemo.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DownloadBarDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        Log.d(TAG, "download bar dismissed; restoring")
        DownloadBarLauncher.ensureRunning(context)
    }

    private companion object {
        const val TAG = "DownloadBarLauncher"
    }
}
