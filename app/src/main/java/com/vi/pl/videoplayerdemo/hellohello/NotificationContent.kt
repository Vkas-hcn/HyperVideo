package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import com.vi.pl.videoplayerdemo.R

data class NotificationContent(
    val id: String,
    val title: String,
    val action: String,
    val summary: String,
    val url: String = "",
)

fun interface NotificationContentSource {
    suspend fun load(context: Context, showType: Int): NotificationContent?
}

object PlaceholderContentSource : NotificationContentSource {
    override suspend fun load(context: Context, showType: Int): NotificationContent =
        NotificationContent(
            id = "placeholder",
            title = context.getString(R.string.notice_placeholder_title),
            action = context.getString(R.string.notice_placeholder_action),
            summary = context.getString(R.string.notice_placeholder_summary),
        )
}
