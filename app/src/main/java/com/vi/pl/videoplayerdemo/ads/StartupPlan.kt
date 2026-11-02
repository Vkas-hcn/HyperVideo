package com.vi.pl.videoplayerdemo.ads

/**
 * 启动页三路广告的纯逻辑（platform-free，可单测）：
 * 冷启动只加载开屏；热启动按 开屏 → 引导插屏1 → 引导插屏2 的顺序取第一份有效缓存，
 * 三路都没有缓存则回落冷启动。对齐 Efile SplashStartupPlan。
 */
internal object StartupPlan {
    const val SLOT_OPEN = "open_back"
    const val SLOT_GUIDE_1 = "inters_guide_1"
    const val SLOT_GUIDE_2 = "inters_guide_2"

    /** 启动页三路展示优先级。 */
    val STARTUP_ORDER = listOf(SLOT_OPEN, SLOT_GUIDE_1, SLOT_GUIDE_2)

    /** 开屏关闭后的补货延迟（对齐 Efile §7.8-1，避开关闭动画与页面跳转抢资源）。 */
    const val STARTUP_REPLENISH_DELAY_MILLIS = 1_000L

    /** 启动位撞上全屏最小间隔时，允许在本次启动流程内等待并重试的最大毫秒数。 */
    const val STARTUP_INTERVAL_MAX_WAIT_MILLIS = 2_000L

    /** 短等待后补一点余量，避免边界抖动导致二次判定仍未满足间隔。 */
    const val STARTUP_INTERVAL_RETRY_MARGIN_MILLIS = 50L

    /** 冷启动恒 null；热启动返回第一份有效缓存（调用方负责过滤已达上限的缓存）。 */
    fun startupCachedName(coldStart: Boolean, cachedNames: Set<String>): String? {
        if (coldStart) return null
        return STARTUP_ORDER.firstOrNull { it in cachedNames }
    }

    /** 距上次全屏关闭是否已满最小间隔（纯计算）；从未记录视为放行。 */
    fun intervalRemainingMillis(now: Long, lastCloseAt: Long, minInterval: Long): Long {
        if (minInterval <= 0L || lastCloseAt <= 0L) return 0L
        return (minInterval - (now - lastCloseAt)).coerceAtLeast(0L)
    }

    /**
     * 启动位展示前需要等待的全屏最小间隔毫秒数：
     * 0 表示无需等待；null 表示间隔过长，放弃等待、按取消处理，避免启动页被拖住。
     */
    fun startupIntervalWaitMillis(remainingMillis: Long): Long? = when {
        remainingMillis <= 0L -> 0L
        remainingMillis > STARTUP_INTERVAL_MAX_WAIT_MILLIS -> null
        else -> remainingMillis + STARTUP_INTERVAL_RETRY_MARGIN_MILLIS
    }
}
