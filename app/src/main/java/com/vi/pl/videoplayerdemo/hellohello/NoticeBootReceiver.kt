package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher

class NoticeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        DownloadBarLauncher.ensureRunning(context)
        HintUtil.markLing()
        HintUtil.startJob(context)
    }
}
