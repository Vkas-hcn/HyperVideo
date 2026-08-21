package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class WatchHistoryItem(
    val videoId: String,
    val title: String,
    val uri: Uri?,
    val resolution: String = "",
    val progress: Int,
    val durationMs: Long,
    val localId: Long?,
    val watchedAt: Long = 0L,
    val positionMs: Long = 0L,
)

object WatchHistoryStore {
    fun record(
        context: Context,
        uri: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (uri.isBlank()) return
        val items = all(context).toMutableList()
        val previous = items.find { it.videoId == uri }
        items.removeAll { it.videoId == uri }
        val duration = durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0L
        items.add(
            0,
            WatchHistoryItem(
                videoId = uri,
                title = title.ifBlank { previous?.title ?: "Video" },
                uri = Uri.parse(uri),
                progress = progressOf(positionMs, duration),
                durationMs = duration,
                localId = localIdOf(uri),
                watchedAt = System.currentTimeMillis(),
                positionMs = positionMs.coerceAtLeast(0L),
            ),
        )
        save(context, items.take(MAX_ITEMS))
    }

    fun all(context: Context): List<WatchHistoryItem> {
        val items = load(context)
        val available = items.filter { it.uri == null || isAvailable(context, it.uri) }
        if (available.size != items.size) save(context, available)
        return available
    }

    fun remove(context: Context, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        save(context, load(context).filterNot { it.videoId in videoIds })
        val progress = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE).edit()
        videoIds.forEach { progress.remove("progress_$it") }
        progress.apply()
    }

    private fun load(context: Context): List<WatchHistoryItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val uri = json.optString("uri")
                    if (uri.isBlank()) continue
                    val position = json.optLong("position")
                    val duration = json.optLong("duration")
                    add(
                        WatchHistoryItem(
                            videoId = uri,
                            title = json.optString("title").ifBlank { "Video" },
                            uri = Uri.parse(uri),
                            progress = progressOf(position, duration),
                            durationMs = duration.coerceAtLeast(0L),
                            localId = localIdOf(uri),
                            watchedAt = json.optLong("watchedAt"),
                            positionMs = position.coerceAtLeast(0L),
                        ),
                    )
                }
            }.sortedByDescending { it.watchedAt }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<WatchHistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.videoId)
                    .put("title", item.title)
                    .put("position", item.positionMs)
                    .put("duration", item.durationMs)
                    .put("watchedAt", item.watchedAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun isAvailable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use {
            it.moveToFirst()
        } ?: true
    }.getOrDefault(true)

    private fun progressOf(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return ((positionMs.coerceAtLeast(0L) * 100) / durationMs).toInt().coerceIn(0, 100)
    }

    private fun localIdOf(uri: String): Long? =
        Uri.parse(uri).lastPathSegment?.toLongOrNull() ?: uri.hashCode().toLong()

    private const val PREFS = "watch_history"
    private const val KEY_ITEMS = "items"
    private const val PROGRESS_PREFS = "video_player_progress"
    private const val MAX_ITEMS = 200
}
