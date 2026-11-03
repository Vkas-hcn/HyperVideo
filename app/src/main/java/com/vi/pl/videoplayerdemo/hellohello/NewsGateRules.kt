package com.vi.pl.videoplayerdemo.hellohello

/**
 * 通知发送门禁的纯逻辑（platform-free，可单测）：
 * 冷却、日限、动态通知 ID。时段窗口复用 NoticeConfigStore.isHourAllowed。
 */
internal object NewsGateRules {
    const val FIRST_DYNAMIC_NOTIFICATION_ID = 70_000

    /** 距上次发送是否已满足冷却（分钟）。 */
    fun cooldownSatisfied(lastSentAtMs: Long, nowMs: Long, cooldownMinutes: Int): Boolean =
        cooldownMinutes * 60_000L <= nowMs - lastSentAtMs

    /** 当日计数是否低于上限。 */
    fun dailyCountBelow(count: Long, limit: Int): Boolean = count < limit

    /** 动态通知 ID：70_000 起递增，溢出回到起点；不互相覆盖。 */
    fun nextNotificationId(lastId: Int): Int =
        if (lastId in FIRST_DYNAMIC_NOTIFICATION_ID until Int.MAX_VALUE) lastId + 1
        else FIRST_DYNAMIC_NOTIFICATION_ID
}
