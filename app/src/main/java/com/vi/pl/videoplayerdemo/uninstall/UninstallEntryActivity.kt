package com.vi.pl.videoplayerdemo.uninstall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vi.pl.videoplayerdemo.ui.GuideActivity

/**
 * 桌面 Uninstall 快捷方式的中转页：只活一帧，补齐 NEW_TASK|CLEAR_TASK 后转启动页。
 * 静态快捷方式的 intent 不能带 flag，启动页需要成为新的 task root。
 */
class UninstallEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = Intent(this, GuideActivity::class.java).apply {
            action = UninstallEntry.ACTION_DELETE_ENTRY
            putExtra(UninstallEntry.EXTRA_UNINSTALL_ENTRY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(next)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
