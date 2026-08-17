package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteItem(
    val videoId: String,
    val title: String,
    val uri: Uri?,
    val durationMs: Long,
    val localId: Long?,
    val addedAt: Long,
)

object FavoriteStore {
    fun toggle(context: Context, item: LocalVideoItem): Boolean {
        val id = item.uri.toString()
        return if (contains(context, id)) {
            remove(context, listOf(id))
            false
        } else {
            add(context, item)
            true
        }
    }

    fun contains(context: Context, videoId: String): Boolean =
        load(context).any { it.videoId == videoId }

    fun all(context: Context): List<FavoriteItem> = load(context)

    fun remove(context: Context, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        save(context, load(context).filterNot { it.videoId in videoIds })
    }

    private fun add(context: Context, item: LocalVideoItem) {
        val uri = item.uri.toString()
        val items = load(context).toMutableList()
        items.removeAll { it.videoId == uri }
        items.add(
            0,
            FavoriteItem(
                videoId = uri,
                title = item.title.ifBlank { "Video" },
                uri = item.uri,
                durationMs = item.durationMs.coerceAtLeast(0L),
                localId = item.id,
                addedAt = System.currentTimeMillis(),
            ),
        )
        save(context, items.take(MAX_ITEMS))
    }

    private fun load(context: Context): List<FavoriteItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val uri = json.optString("uri")
                    if (uri.isBlank()) continue
                    add(
                        FavoriteItem(
                            videoId = uri,
                            title = json.optString("title").ifBlank { "Video" },
                            uri = Uri.parse(uri),
                            durationMs = json.optLong("duration").coerceAtLeast(0L),
                            localId = json.optLong("localId").takeIf { it != 0L }
                                ?: Uri.parse(uri).lastPathSegment?.toLongOrNull(),
                            addedAt = json.optLong("addedAt"),
                        ),
                    )
                }
            }.sortedByDescending { it.addedAt }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<FavoriteItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.videoId)
                    .put("title", item.title)
                    .put("duration", item.durationMs)
                    .put("localId", item.localId ?: 0L)
                    .put("addedAt", item.addedAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private const val PREFS = "favorites"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200
}
