package com.vi.pl.videoplayerdemo.local

import android.net.Uri

data class LocalVideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val bucketName: String,
)

data class LocalVideoCategory(
    val name: String,
    val videos: List<LocalVideoItem>,
)
