package com.vi.pl.videoplayerdemo.uninstall

import android.content.Intent

/** 卸载挽留入口判定：只认桌面快捷方式的 action / 显式 extra。 */
object UninstallEntry {
    const val ACTION_DELETE_ENTRY = "com.vi.pl.videoplayerdemo.delete"
    const val EXTRA_UNINSTALL_ENTRY = "extra_uninstall_entry"

    fun isFromUninstall(action: String?, extra: Boolean): Boolean =
        action == ACTION_DELETE_ENTRY || extra

    fun isLeaveLaunch(intent: Intent?): Boolean =
        intent != null && isFromUninstall(
            intent.action,
            intent.getBooleanExtra(EXTRA_UNINSTALL_ENTRY, false),
        )
}
