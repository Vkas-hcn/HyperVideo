package com.vi.pl.videoplayerdemo.hellohello

import android.util.Log

/**
 * 通知日志（logcat TAG = [NewsNotify]，与广告日志 AdLogger 同形态）。
 *
 * 统一中文输出，覆盖：触发来源、发送/阻挡（含阻挡原因）、频控、内容、常驻 banner、
 * 闹钟、FCM。格式为「通知·来源」事件,原因/细节，便于按 TAG 过滤整条链路。
 */
object NewsLogger {
    private const val TAG = "NewsNotify"
    var enabled = true

    // ---------------- 触发 ----------------

    fun triggerReceived(source: Int, extra: String = "") =
        i("【通知】收到触发,来源:" + sourceName(source) + extra)

    fun triggerWorkFinished(source: Int) =
        d("【通知】触发处理完成,来源:" + sourceName(source))

    fun runtimeInitialized() = i("【通知】运行时已初始化")

    fun initSkipped() = d("【通知】初始化跳过:已初始化")

    fun receiverRegistered(name: String) = d("【通知】广播已注册:" + name)

    // ---------------- 发送链路 ----------------

    fun sendStarted(source: Int) =
        i("【通知·" + sourceName(source) + "】开始发送")

    fun sendBlocked(source: Int, reason: String, detail: String = "") =
        w("【通知·" + sourceName(source) + "】发送被阻止,原因:" + reasonText(reason) + detail)

    fun sendEligibility(source: Int, allowed: Boolean, hour: Int, rangeStart: Int, rangeEnd: Int, sent: Long, limit: Int) =
        d(
            "【通知·" + sourceName(source) + "】时段判定,允许=" + allowed + ",当前小时=" + hour +
                ",允许时段=" + rangeStart + ".." + rangeEnd + ",今日已发=" + sent + "/" + limit,
        )

    fun sendAborted(source: Int) =
        w("【通知·" + sourceName(source) + "】发送中止,原因:内容获取后资格发生变化")

    fun sendRecorded(source: Int, count: Long) =
        d("【通知·" + sourceName(source) + "】发送计数更新:今日 " + count + " 次")

    fun asyncSendFailed(source: Int, error: Throwable) =
        e("【通知·" + sourceName(source) + "】异步发送异常:" + error)

    fun asyncSendFinished(source: Int) =
        d("【通知·" + sourceName(source) + "】异步发送完成")

    fun contentPicked(id: String) = d("【通知】内容已选取,文案ID:" + id)

    fun contentMissing() = w("【通知】无可用通知内容,本次跳过")

    fun notifySubmitted(source: Int, id: Int, channel: String, sticky: Boolean) =
        i(
            "【通知·" + sourceName(source) + "】已提交,通知ID=" + id + ",渠道=" + channel +
                ",常驻=" + sticky,
        )

    fun notificationCancelled(source: Int, id: Int) =
        d("【通知·" + sourceName(source) + "】已取消,通知ID=" + id)

    // ---------------- 常驻 banner ----------------

    fun bannerStarted(id: String, triggers: Int) =
        d("【通知】常驻banner会话开始,文案ID=" + id + ",刷新次数=" + triggers)

    fun bannerSkipped(reason: String) =
        w("【通知】常驻banner未启用,原因:" + reasonText(reason))

    fun bannerInterrupted(reason: String) =
        w("【通知】常驻banner会话中断,原因:" + reasonText(reason))

    fun bannerFinished(id: String) = d("【通知】常驻banner会话结束,文案ID=" + id)

    fun bannerRestored(id: Int) = d("【通知】常驻banner划掉后已恢复,通知ID=" + id)

    fun bannerDismissed(id: Int) = d("【通知】常驻banner被划掉,通知ID=" + id)

    // ---------------- 闹钟 ----------------

    fun alarmScheduled(triggerAt: Long, intervalMinutes: Int) =
        i("【通知】闹钟已预约,触发时间=" + triggerAt + ",间隔分钟=" + intervalMinutes)

    fun alarmSkipped(reason: String) =
        d("【通知】闹钟预约跳过,原因:" + reasonText(reason))

    fun alarmReceived() = i("【通知】收到闹钟广播")

    fun bootRestoreStarted() = i("【通知】开机恢复:补排闹钟+FCM订阅")

    fun jobHandled() = d("【通知】保活任务执行:补排闹钟")

    // ---------------- FCM ----------------

    fun fcmMessageArrived(keys: List<String>, hasNotificationNode: Boolean) =
        i("【FCM】消息到达,数据键=" + keys + ",含通知节点=" + hasNotificationNode)

    fun fcmPayloadParsed(fields: Int, keys: List<String>) =
        d("【FCM】载荷已解析,字段数=" + fields + ",键名=" + keys)

    fun fcmDropped(reason: String) = w("【FCM】消息丢弃,原因:" + reasonText(reason))

    fun fcmStaged(fields: Int) = i("【FCM】消息已暂存,原因:运行时未初始化,字段数=" + fields)

    fun fcmProcessingStart(source: String, delayMs: Long) =
        i("【FCM】开始处理,来源=" + source + ",延迟毫秒=" + delayMs)

    fun fcmBlocked(reason: String, detail: String = "") =
        w("【FCM】未展示,原因:" + reasonText(reason) + detail)

    fun fcmShown(id: String) = i("【FCM】展示成功,内容ID=" + id)

    fun fcmSubmitFailed(id: String) = w("【FCM】展示失败,原因:通知提交失败,内容ID=" + id)

    fun fcmPermissionCancelled(id: String) =
        w("【FCM】提交取消,原因:未授予通知权限,内容ID=" + id)

    fun fcmTopicSubscribe(started: Boolean, success: Boolean, source: String) {
        val state = if (started) (if (success) "成功" else "失败") else "发起失败"
        val message = "【FCM】主题订阅" + state + ",主题=hypervideo,来源=" + source
        if (success) d(message) else w(message)
    }

    fun fcmTokenRefreshed() = i("【FCM】令牌已刷新")

    // ---------------- 文案映射 ----------------

    private fun sourceName(source: Int): String = when (source) {
        HintUtil.CO_S_TYPE_UNLOCK -> "解锁"
        HintUtil.CO_S_TYPE_INTERVAL -> "轮询"
        HintUtil.CO_S_TYPE_ALARM -> "闹钟"
        else -> "未知(" + source + ")"
    }

    private fun reasonText(reason: String): String = when (reason) {
        "no_permission" -> "未授予通知权限"
        "notifications_disabled" -> "系统通知总开关关闭"
        "switch_off" -> "配置总开关关闭(co_on=0)"
        "app_foreground" -> "应用在前台"
        "cooldown" -> "冷却时间未到"
        "daily_limit" -> "已达当日发送上限"
        "outside_window" -> "不在允许发送时段"
        "no_content" -> "无可用通知内容"
        "banner_active" -> "已有常驻banner会话"
        "banner_disabled" -> "banner_notify未开启"
        "device_locked" -> "设备处于锁屏"
        "permission_or_switch_changed" -> "权限或通知开关发生变化"
        "session_lost" -> "会话已被其他流程占用"
        "runtime_not_initialized" -> "通知运行时未初始化"
        "permission_denied" -> "未授予通知权限"
        "empty_payload" -> "载荷为空"
        "alarm_interval_disabled" -> "闹钟间隔配置为0(co_a)"
        "existing_alarm" -> "已有未来闹钟"
        else -> reason
    }

    private fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    private fun i(message: String) {
        if (enabled) Log.i(TAG, message)
    }

    private fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    private fun e(message: String) {
        if (enabled) Log.e(TAG, message)
    }
}