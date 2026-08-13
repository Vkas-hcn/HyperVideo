package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.vi.pl.videoplayerdemo.R
import java.util.concurrent.Executors

object VideoThumbnailLoader {
    private val cache = LruCache<Long, Bitmap>(80)
    private val videoSizes = LruCache<Long, Pair<Int, Int>>(80)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, item: LocalVideoItem, into: ImageView, sizePx: Int = 256) {
        val app = context.applicationContext
        into.tag = item.id
        cache.get(item.id)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageResource(R.drawable.ic_play)
        executor.execute {
            val bitmap = runCatching { decode(app, item, sizePx) }.getOrNull() ?: return@execute
            cache.put(item.id, bitmap)
            main.post {
                if (into.tag == item.id) into.setImageBitmap(bitmap)
            }
        }
    }

    fun load(
        context: Context,
        key: Long,
        uri: Uri,
        into: ImageView,
        onVideoSize: (width: Int, height: Int) -> Unit,
        sizePx: Int = 256,
    ) {
        val app = context.applicationContext
        into.tag = key
        cache.get(key)?.let {
            into.setImageBitmap(it)
            videoSizes.get(key)?.let { (width, height) -> onVideoSize(width, height) }
            return
        }
        into.setImageResource(R.drawable.ic_play)
        executor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        sizePx,
                        sizePx,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    retriever.frameAtTime
                } ?: return@execute
                cache.put(key, bitmap)
                videoSizes.put(key, width to height)
                main.post {
                    if (into.tag == key) {
                        into.setImageBitmap(bitmap)
                        onVideoSize(width, height)
                    }
                }
            } catch (_: Throwable) {
                // The download may not yet contain a decodable video frame.
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun decode(context: Context, item: LocalVideoItem, sizePx: Int): Bitmap? {
        val fromStore = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
        }.getOrNull()
        // MediaStore often has no thumbnail for freshly downloaded files; pull a frame directly.
        return fromStore ?: extractFrame(context, item.uri, sizePx)
    }

    private fun extractFrame(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    -1L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    sizePx,
                    sizePx,
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.frameAtTime
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
