package com.vi.pl.videoplayerdemo.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vi.pl.videoplayerdemo.HyperVideoApplication

/**
 * 通知权限申请（对齐 EfileScan PermissionHelper 的通知分支，去掉埋点）。
 *
 * 核心规则：
 * 1. 启动页只弹系统框（systemOnly），同意 / 拒绝 / 返回键都由 UMP 继续；
 * 2. **每次首页显示**只要还没授权就再试一次系统框：系统框还能弹就弹系统框，弹不出来才弹自定义框；
 * 3. 系统框是否"真的弹出来了"用 EfileScan 的 300ms 判定：
 *    返回键关闭的框也算弹过（不是拒绝），下次首页显示仍会继续尝试系统框；
 *    只有系统已经不给入口（回调 < 300ms 且没有 rationale）才转自定义框。
 */
object NotificationPermissionHelper {
    /** 系统框无回调通常 < 300ms；权限页有明显停留才认为框真的弹出过。 */
    private const val SYSTEM_DIALOG_VISIBLE_MIN_MS = 300L

    fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** 决定「要不要弹系统框」：只看 runtime 权限。 */
    fun isNotificationGranted(context: Context?): Boolean {
        if (context == null) return false
        val permissions = notificationPermissions()
        if (permissions.isEmpty()) return true
        return permissions.all { isGranted(context, it) }
    }

    /** 决定「通知功能是否可用」：runtime 权限 + 系统通知总开关。 */
    fun hasNotificationPermission(context: Context?): Boolean {
        if (context == null) return false
        if (!isNotificationGranted(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun bind(activity: ComponentActivity): Host = Host(activity)

    /** 每个 Activity 一个实例，必须在 Activity 进入 STARTED 之前创建（onCreate 里取一次）。 */
    class Host internal constructor(private val activity: ComponentActivity) {
        private var pendingRuntime: ((Map<String, Boolean>) -> Unit)? = null
        private var pendingSettingsResult: (() -> Unit)? = null
        private var permissionRequestStartedAt = 0L

        private var notificationInAppVisible = false
        private var skipNextHomeNotificationPrompt = false

        private val permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val callback = pendingRuntime
            pendingRuntime = null
            callback?.invoke(result)
        }

        private val settingsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val callback = pendingSettingsResult
            pendingSettingsResult = null
            callback?.invoke()
        }

        /**
         * @param systemOnly true = 只弹系统框（启动页），系统框弹不出来时直接回调 false。
         */
        fun requestNotification(
            systemOnly: Boolean = false,
            onFinished: (granted: Boolean) -> Unit,
        ) {
            requestSystemDialog(systemOnly) { granted, dialogShown ->
                if (shouldFallBackToInAppDialog(granted, dialogShown, systemOnly)) {
                    showHomeInAppNotificationDialog(onFinished)
                } else {
                    onFinished(granted)
                }
            }
        }

        /**
         * 每次首页显示都调用一次：没授权就再试一次系统框；系统框弹不出来才弹自定义框。
         * 返回键关掉的系统框算"弹过"，下次首页显示继续尝试，不会误判成拒绝。
         *
         * @return true 表示本轮已经发起系统框或自定义框。
         */
        fun showNotificationInAppPrompt(onFinished: (granted: Boolean) -> Unit): Boolean {
            if (isNotificationGranted(activity)) {
                skipNextHomeNotificationPrompt = false
                return false
            }
            if (notificationInAppVisible) return false
            // API 32 及以下没有 runtime 权限框，只能看系统通知总开关
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return showHomeInAppNotificationDialog(onFinished)
            }
            if (skipNextHomeNotificationPrompt) {
                // 刚处理完一次系统框（拒绝或返回键），跳过紧跟着的那次 onResume
                skipNextHomeNotificationPrompt = false
                return false
            }
            requestSystemDialog(systemOnly = true) { granted, dialogShown ->
                when {
                    granted -> onFinished(true)
                    // 系统框真的弹不出来（已不给入口）→ 转自定义框
                    !dialogShown -> showHomeInAppNotificationDialog(onFinished)
                    // 框弹过（拒绝或返回键）→ 本轮不弹自定义框，等下次首页显示继续尝试系统框
                    else -> {
                        skipNextHomeNotificationPrompt = true
                        onFinished(false)
                    }
                }
            }
            return true
        }

        private fun requestSystemDialog(
            systemOnly: Boolean,
            onResult: (granted: Boolean, dialogShown: Boolean) -> Unit,
        ) {
            val permissions = notificationPermissions()
            if (permissions.isEmpty()) {
                onResult(false, false)
                return
            }
            val dialogWillShow = permissions.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            pendingRuntime = { result ->
                val granted = permissions.all { permission ->
                    result[permission] == true || isGranted(activity, permission)
                }
                val dialogShown = systemDialogDidShow(
                    dialogWillShow = dialogWillShow,
                    elapsedSinceRequestMs = SystemClock.elapsedRealtime() - permissionRequestStartedAt,
                )
                onResult(granted, dialogShown)
            }
            permissionRequestStartedAt = SystemClock.elapsedRealtime()
            permissionLauncher.launch(permissions)
        }

        private fun showHomeInAppNotificationDialog(onFinished: (granted: Boolean) -> Unit): Boolean {
            if (!shouldShowHomeNotificationDialog(
                    notificationGranted = hasNotificationPermission(activity),
                    promptAlreadyVisible = notificationInAppVisible,
                )
            ) {
                return false
            }
            notificationInAppVisible = true
            showInAppPermissionDialog { granted ->
                notificationInAppVisible = false
                onFinished(granted)
            }
            return true
        }

        private fun showInAppPermissionDialog(onFinished: (granted: Boolean) -> Unit) {
            val dialog = NotificationPermissionDialog(activity)
            var decided = false

            fun finishDenied() {
                if (decided) return
                decided = true
                onFinished(hasNotificationPermission(activity))
            }

            dialog.onAllow = {
                // 只有点「允许」才去设置页；先摘掉 dismiss 监听，避免把跳转误判成拒绝。
                decided = true
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                pendingSettingsResult = { onFinished(hasNotificationPermission(activity)) }
                // 去系统设置页不算离开后台，避免返回时被热启动判定拦去开屏
                (activity.application as? HyperVideoApplication)
                    ?.ignoreNextBackgroundReturn("notification_permission_settings")
                settingsLauncher.launch(appSettingsIntent(activity))
            }
            dialog.setOnDismissListener { finishDenied() }
            dialog.show()
        }
    }
}

/**
 * 系统框这次是否真的弹出来了：请求前就有 rationale（之前拒绝过但还能申请），
 * 或权限页停留时间达到 [SYSTEM_DIALOG_VISIBLE_MIN_MS]（含返回键关闭）。
 */
internal fun systemDialogDidShow(
    dialogWillShow: Boolean,
    elapsedSinceRequestMs: Long,
    minVisibleMs: Long = 300L,
): Boolean = dialogWillShow || elapsedSinceRequestMs >= minVisibleMs

/** 系统框确实弹不出来时，才降级为应用内自定义框。 */
internal fun shouldFallBackToInAppDialog(
    granted: Boolean,
    dialogShown: Boolean,
    systemOnly: Boolean,
): Boolean = !granted && !systemOnly && !dialogShown

/** 首页通知引导：没授权且当前没有正在显示的引导框时才弹。 */
internal fun shouldShowHomeNotificationDialog(
    notificationGranted: Boolean,
    promptAlreadyVisible: Boolean,
): Boolean = !notificationGranted && !promptAlreadyVisible
