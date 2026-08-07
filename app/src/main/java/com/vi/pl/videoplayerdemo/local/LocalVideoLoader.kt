package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

object LocalVideoLoader {
    fun loadFolders(context: Context): List<VideoFolder> {
        val videos = queryVideos(context)
        return videos
            .groupBy { it.folderPath }
            .map { (path, items) ->
                VideoFolder(
                    path = path,
                    name = items.first().folderName,
                    videos = items.sortedByDescending { it.dateAddedSec },
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun loadFolderVideos(context: Context, folderPath: String): List<LocalVideoItem> =
        queryVideos(context).filter { it.folderPath == folderPath }

    fun loadCategories(context: Context): List<LocalVideoCategory> =
        loadFolders(context).map { LocalVideoCategory(name = it.name, videos = it.videos) }

    private fun queryVideos(context: Context): List<LocalVideoItem> {
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Video.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Video.Media.DATA
        }

        val result = mutableListOf<LocalVideoItem>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val bucket = cursor.getString(bucketColumn).orEmpty().ifBlank { "Other" }
                val rawPath = cursor.getString(pathColumn).orEmpty()
                val folderPath = normalizeFolderPath(rawPath, bucket)
                val folderName = folderPath.substringAfterLast('/').ifBlank { bucket }
                result += LocalVideoItem(
                    id = id,
                    title = cursor.getString(nameColumn).orEmpty().ifBlank { "Video $id" },
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(id.toString())
                        .build(),
                    durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L),
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    dateAddedSec = cursor.getLong(dateColumn).coerceAtLeast(0L),
                    folderPath = folderPath,
                    folderName = folderName,
                )
            }
        }
        return result
    }

    private fun normalizeFolderPath(rawPath: String, fallbackName: String): String {
        if (rawPath.isBlank()) return fallbackName
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rawPath.trimEnd('/')
        } else {
            File(rawPath).parent?.trimEnd('/') ?: fallbackName
        }
        return path.ifBlank { fallbackName }
    }
}
