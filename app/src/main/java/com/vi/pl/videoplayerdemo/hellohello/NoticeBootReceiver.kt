package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机恢复通知链路：补排闹钟 + FCM 订阅。
 * 无前台服务/常驻栏载体（保活口径：通知链路自恢复）。
 */
class NoticeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        NewsLogger.bootRestoreStarted()
        runCatching { HintUtil.markLing() }
            .onFailure { error -> Log.w(TAG, "restore alarm failed", error) }
        runCatching { MessageRecv.subscribeTopic("开机") }
            .onFailure { error -> Log.w(TAG, "FCM subscribe failed", error) }
    }

    private companion object {
        const val TAG = "NoticeBoot"
    }
}