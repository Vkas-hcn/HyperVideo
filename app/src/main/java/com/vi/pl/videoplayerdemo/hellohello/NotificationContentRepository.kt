package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import androidx.core.content.edit

/**
 * 通知内容源：本地静态文案池（4 组），按顺序轮换，避免连续两条相同。
 * 将来接回下发/推荐数据源时只替换本文件，调用方不变。
 */
object NotificationContentRepository {
    private const val PREFS_NAME = "notification_copy_rotation"
    private const val KEY_NEXT_INDEX = "next_index"

    private val pool = listOf(
        NotificationContent(
            id = "copy_1",
            title = "Fresh files, ready to play",
            summary = "New videos just landed on your device. Open and hit play.",
            action = "Browse library",
            // 示例：填入任意 URL，点击通知即在内置浏览器打开该页面；留空则默认进首页
            url = "",
        ),
        NotificationContent(
            id = "copy_2",
            title = "New videos just dropped in",
            summary = "Fresh files are waiting in your library. Open up and start watching.",
            action = "Open library",
            url = "",
        ),
        NotificationContent(
            id = "copy_3",
            title = "Your library just got bigger",
            summary = "New clips showed up. Pick one and press play.",
            action = "Check it out",
            url = "",
        ),
    )

    private val lock = Any()
    private var lastId: String? = null

    fun next(context: Context): NotificationContent? {
        synchronized(lock) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val index = prefs.getInt(KEY_NEXT_INDEX, 0)
            val content = pool[index % pool.size]
            prefs.edit { putInt(KEY_NEXT_INDEX, (index + 1) % pool.size) }
            // 进程内去重：避免连续两条相同文案
            if (content.id == lastId) return null
            lastId = content.id
            return content
        }
    }

    fun resetCopyCache() {
        synchronized(lock) { lastId = null }
    }
}
