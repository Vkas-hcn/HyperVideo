package com.vi.pl.videoplayerdemo.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

object VideoFormat {
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun watchedAgo(watchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val delta = (nowMs - watchedAtMs).coerceAtLeast(0L)
        val minutes = delta / 60_000L
        val hours = delta / 3_600_000L
        val days = delta / 86_400_000L
        return when {
            minutes < 1L -> "Just now"
            minutes < 60L -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            hours < 24L -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
            else -> if (days == 1L) "1 day ago" else "$days days ago"
        }
    }

    fun duration(durationMs: Long): String {
        val totalSeconds = max(0L, durationMs / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun dateLabel(dateAddedSec: Long): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochSecond(dateAddedSec).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dateFormatter)
        }
    }

    fun qualityLabel(width: Int, height: Int): String? {
        val shortEdge = minOf(width, height)
        return when {
            shortEdge >= 2160 -> "4K"
            shortEdge >= 1440 -> "2K"
            shortEdge >= 1080 -> "1080P"
            shortEdge >= 720 -> "720P"
            shortEdge >= 480 -> "480P"
            shortEdge >= 360 -> "360P"
            shortEdge > 0 -> "${shortEdge}P"
            else -> null
        }
    }
}
