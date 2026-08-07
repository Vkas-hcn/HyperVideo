package com.vi.pl.videoplayerdemo.local

import android.content.Context
import android.graphics.Bitmap
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

    private fun decode(context: Context, item: LocalVideoItem, sizePx: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
    }
}
