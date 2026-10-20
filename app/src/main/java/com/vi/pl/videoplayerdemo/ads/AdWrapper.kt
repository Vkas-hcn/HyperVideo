package com.vi.pl.videoplayerdemo.ads



/**
 * 广告包装器：记录广告实例和加载时间。
 */
data class AdWrapper<T>(
    val ad: T,
    val slotId: String,
    val loadTime: Long,
)

