package com.vi.pl.videoplayerdemo.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

object VideoFormat {
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

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
}
