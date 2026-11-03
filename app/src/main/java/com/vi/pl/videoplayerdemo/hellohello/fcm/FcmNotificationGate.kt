package com.vi.pl.videoplayerdemo.hellohello.fcm

/**
 * FCM 展示门禁（无 Android 依赖），与运营 Config / 频控 / 时段无关。
 * 无机型/地区策略（对齐本项目简化口径）。
 */
internal object FcmNotificationGate {
    fun blockReason(
        runtimeInitialized: Boolean,
        isForeground: Boolean,
        hasNotificationPermission: Boolean,
    ): String? {
        if (!runtimeInitialized) return "runtime_not_initialized"
        if (isForeground) return "app_foreground"
        if (!hasNotificationPermission) return "permission_denied"
        return null
    }
}
