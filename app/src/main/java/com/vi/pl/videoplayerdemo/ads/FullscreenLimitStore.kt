package com.vi.pl.videoplayerdemo.ads

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * 全屏广告（开屏 + 插屏）共享频控的唯一持久化入口（对齐 Efile AdsLimitStore）。
 *
 * 展示次数按日累计，开屏与插屏共用同一个上限；跨天清计数但保留上次全屏关闭时间
 * （最小间隔判据跨天仍有效）。读改写在同一把锁内完成，避免多个广告回调并发丢计数。
 */
object FullscreenLimitStore {
    private const val PREF_NAME = "fullscreen_limits"
    private const val KEY_COUNT_DATE = "count_date"
    private const val KEY_FULLSCREEN_IMPRESSIONS = "fullscreen_impressions"
    private const val KEY_LAST_FULLSCREEN_CLOSE_AT = "last_fullscreen_close_at"

    private val lock = Any()

    fun isFullscreenReached(context: Context?, dailyCap: Int): Boolean {
        if (context == null || dailyCap <= 0) return false
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            resetIfNewDay(prefs)
            return prefs.getInt(KEY_FULLSCREEN_IMPRESSIONS, 0) >= dailyCap
        }
    }

    fun getTodayCount(context: Context?): Int {
        if (context == null) return 0
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            resetIfNewDay(prefs)
            return prefs.getInt(KEY_FULLSCREEN_IMPRESSIONS, 0)
        }
    }

    fun recordFullscreenImpression(context: Context?) {
        if (context == null) return
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            resetIfNewDay(prefs)
            val current = prefs.getInt(KEY_FULLSCREEN_IMPRESSIONS, 0)
            prefs.edit().putInt(KEY_FULLSCREEN_IMPRESSIONS, current + 1).apply()
        }
    }

    fun recordFullscreenClosed(context: Context?, now: Long = System.currentTimeMillis()) {
        if (context == null) return
        synchronized(lock) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_FULLSCREEN_CLOSE_AT, now).apply()
        }
    }

    /** 距下一次允许展示全屏还差多少毫秒；0 表示已满足间隔。 */
    fun fullscreenIntervalRemainingMillis(context: Context?, now: Long, minInterval: Long): Long {
        if (context == null) return 0L
        synchronized(lock) {
            val lastCloseAt = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_FULLSCREEN_CLOSE_AT, 0L)
            return StartupPlan.intervalRemainingMillis(now, lastCloseAt, minInterval)
        }
    }

    private fun resetIfNewDay(prefs: SharedPreferences) {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_COUNT_DATE, null) == today) return
        // 跨天清计数，但全屏关闭时间不能清（最小间隔判据跨天仍有效）。
        val lastClose = prefs.getLong(KEY_LAST_FULLSCREEN_CLOSE_AT, 0L)
        prefs.edit().clear()
            .putString(KEY_COUNT_DATE, today)
            .putLong(KEY_LAST_FULLSCREEN_CLOSE_AT, lastClose)
            .commit()
    }
}
