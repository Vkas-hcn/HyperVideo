package com.vi.pl.videoplayerdemo.local

import android.net.Uri
import com.vi.pl.videoplayerdemo.R

data class LocalVideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val folderPath: String,
    val folderName: String,
)

data class VideoFolder(
    val path: String,
    val name: String,
    val videos: List<LocalVideoItem>,
) {
    val count: Int get() = videos.size

    fun iconRes(): Int = when (name.lowercase()) {
        "download", "downloads" -> R.drawable.ic_download
        "movies", "movie" -> R.drawable.ic_movies
        else -> R.drawable.ic_latest_add
    }
}

enum class VideoSort {
    DATE_NEWEST,
    DATE_OLDEST,
    NAME_AZ,
    NAME_ZA,
    SIZE_LARGEST,
    SIZE_SMALLEST,
}

fun List<LocalVideoItem>.sortedWith(sort: VideoSort): List<LocalVideoItem> = when (sort) {
    VideoSort.DATE_NEWEST -> sortedByDescending { it.dateAddedSec }
    VideoSort.DATE_OLDEST -> sortedBy { it.dateAddedSec }
    VideoSort.NAME_AZ -> sortedBy { it.title.lowercase() }
    VideoSort.NAME_ZA -> sortedByDescending { it.title.lowercase() }
    VideoSort.SIZE_LARGEST -> sortedByDescending { it.sizeBytes }
    VideoSort.SIZE_SMALLEST -> sortedBy { it.sizeBytes }
}

data class LocalVideoCategory(
    val name: String,
    val videos: List<LocalVideoItem>,
)
