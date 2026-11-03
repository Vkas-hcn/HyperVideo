package com.vi.pl.videoplayerdemo.hellohello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 常驻 banner 被划掉后恢复仍有效的会话（对齐 AnonyBrowser NewsBannerDismissReceiver）。 */
class NewsBannerDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val showType = intent?.getIntExtra(HintUtil.CO_SHOW_TYPE, -1) ?: -1
        val notificationId = intent?.getIntExtra(HintUtil.CO_NOTIFICATION_ID, -1) ?: -1
        if (showType < 0 || notificationId < 0) return
        NewsLogger.bannerDismissed(notificationId)
        HintUtil.restorePersistentBannerIfActive(notificationId, showType)
    }
}