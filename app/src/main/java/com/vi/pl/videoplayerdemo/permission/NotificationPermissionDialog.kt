package com.vi.pl.videoplayerdemo.permission

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.vi.pl.videoplayerdemo.R

/**
 * 应用内通知权限引导框（蓝湖「自定义通知权限请求」）：
 * 卡片 24dp 圆角 + 24dp 内边距，标题 / 说明 / 红色 Allow / 文字 Deny。
 *
 * 可取消：点 Deny、返回键、点外部都会 dismiss，由调用方的 OnDismissListener 统一按「拒绝」处理；
 * 只有点 Allow 才回调 [onAllow]（跳系统设置页）。
 */
internal class NotificationPermissionDialog(context: Context) :
    Dialog(context, R.style.AppActionDialogTheme) {

    var onAllow: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_notification_permission)
        // 设计稿：卡片左右各留 16dp 边距（393 - 361 = 32）
        val metrics = context.resources.displayMetrics
        val margin = (DIALOG_HORIZONTAL_MARGIN_DP * metrics.density).toInt()
        window?.setLayout(
            metrics.widthPixels - margin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        setCancelable(true)
        findViewById<View>(R.id.notification_permission_allow).setOnClickListener { onAllow() }
        findViewById<View>(R.id.notification_permission_deny).setOnClickListener { dismiss() }
    }

    private companion object {
        const val DIALOG_HORIZONTAL_MARGIN_DP = 16
    }
}
