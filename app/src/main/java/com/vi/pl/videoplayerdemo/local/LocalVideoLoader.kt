package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.provider.MediaStore

object LocalVideoLoader {
    fun loadCategories(context: Context): List<LocalVideoCategory> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val grouped = linkedMapOf<String, MutableList<LocalVideoItem>>()

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(nameColumn).orEmpty().ifBlank { "Video $id" }
                val bucket = cursor.getString(bucketColumn).orEmpty().ifBlank { "Other" }
                val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build()
                val item = LocalVideoItem(
                    id = id,
                    title = title,
                    uri = contentUri,
                    durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L),
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    bucketName = bucket,
                )
                grouped.getOrPut(bucket) { mutableListOf() }.add(item)
            }
        }

        return grouped.map { (name, videos) ->
            LocalVideoCategory(name = name, videos = videos)
        }
    }
}
