package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Fills in a video's duration text. When MediaStore has no duration (common for freshly
 * downloaded files), it reads the real value from the file on a background thread and updates
 * the target [TextView] once available.
 */
object VideoDurationLoader {
    private val cache = LruCache<Long, Long>(256)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun bind(context: Context, item: LocalVideoItem, into: TextView) {
        into.tag = item.id
        if (item.durationMs > 0L) {
            into.text = VideoFormat.duration(item.durationMs)
            return
        }
        cache.get(item.id)?.let {
            into.text = VideoFormat.duration(it)
            return
        }
        into.text = VideoFormat.duration(0L)
        val app = context.applicationContext
        executor.execute {
            val durationMs = extract(app, item.uri)
            cache.put(item.id, durationMs)
            main.post {
                if (into.tag == item.id) into.text = VideoFormat.duration(durationMs)
            }
        }
    }

    private fun extract(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
