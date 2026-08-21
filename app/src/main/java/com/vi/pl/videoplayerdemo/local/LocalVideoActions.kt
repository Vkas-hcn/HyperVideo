package com.vi.pl.videoplayerdemo.local

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.vi.pl.videoplayerdemo.R

object LocalVideoActions {
    fun share(context: Context, item: LocalVideoItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TITLE, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_video)))
    }

    /** Returns null on success; otherwise a write-request PendingIntent (API 30+). */
    fun rename(context: Context, item: LocalVideoItem, newTitle: String): PendingIntent? {
        val name = ensureExtension(newTitle.trim(), item.title)
        if (name.isBlank() || name == item.title) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        return try {
            val updated = context.contentResolver.update(item.uri, values, null, null)
            if (updated <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createWriteRequest(context.contentResolver, listOf(item.uri))
            } else {
                null
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createWriteRequest(context.contentResolver, listOf(item.uri))
            } else {
                throw SecurityException("No write access")
            }
        }
    }

    fun applyRename(context: Context, uri: Uri, newTitle: String, fallbackTitle: String): Boolean {
        val name = ensureExtension(newTitle.trim(), fallbackTitle)
        if (name.isBlank()) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        return try {
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: SecurityException) {
            false
        }
    }

    /** Returns null when deleted inline; otherwise a delete-request PendingIntent (API 30+). */
    fun delete(context: Context, item: LocalVideoItem): PendingIntent? {
        return try {
            val deleted = context.contentResolver.delete(item.uri, null, null)
            if (deleted > 0) {
                FavoriteStore.remove(context, listOf(item.uri.toString()))
                WatchHistoryStore.remove(context, listOf(item.uri.toString()))
                null
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
            } else {
                throw SecurityException("Delete failed")
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
            } else {
                throw SecurityException("No delete access")
            }
        }
    }

    fun launchPending(activity: Activity, pending: PendingIntent, requestCode: Int) {
        activity.startIntentSenderForResult(
            pending.intentSender,
            requestCode,
            null,
            0,
            0,
            0,
        )
    }

    private fun ensureExtension(newName: String, original: String): String {
        if (newName.isBlank()) return newName
        val dot = original.lastIndexOf('.')
        if (dot <= 0 || dot == original.lastIndexOf('/') + 1) return newName
        val ext = original.substring(dot)
        return if (newName.contains('.')) newName else newName + ext
    }
}
