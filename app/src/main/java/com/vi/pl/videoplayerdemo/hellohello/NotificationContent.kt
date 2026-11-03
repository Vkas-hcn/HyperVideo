package com.vi.pl.videoplayerdemo.hellohello

/**
 * 通知内容数据模型。HyperVideo 无新闻推荐数据源与 URL 深链，
 * url 保留字段兼容 FCM 预留，本地文案池恒为空。
 */
data class NotificationContent(
    val id: String,
    val title: String,
    val summary: String,
    val action: String,
    val url: String,
)
