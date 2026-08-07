package com.vi.pl.videoplayerdemo.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoCategory
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
import java.util.Locale
import kotlin.math.max

class LocalVideoListAdapter(
    private val onVideoClick: (category: LocalVideoCategory, index: Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    sealed class Row {
        data class Header(val category: LocalVideoCategory) : Row()
        data class Video(val category: LocalVideoCategory, val index: Int, val item: LocalVideoItem) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submitCategories(categories: List<LocalVideoCategory>) {
        rows.clear()
        categories.forEach { category ->
            rows += Row.Header(category)
            category.videos.forEachIndexed { index, item ->
                rows += Row.Video(category, index, item)
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Video -> VIEW_TYPE_VIDEO
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_video_category_header, parent, false),
            )
            else -> VideoViewHolder(
                inflater.inflate(R.layout.item_local_video, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.category)
            is Row.Video -> (holder as VideoViewHolder).bind(row.item) {
                onVideoClick(row.category, row.index)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.category_title)
        private val countText: TextView = itemView.findViewById(R.id.category_count)

        fun bind(category: LocalVideoCategory) {
            titleText.text = category.name
            countText.text = itemView.context.getString(
                R.string.video_count_format,
                category.videos.size,
            )
        }
    }

    private class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.video_thumbnail)
        private val titleText: TextView = itemView.findViewById(R.id.video_title)
        private val metaText: TextView = itemView.findViewById(R.id.video_meta)

        fun bind(item: LocalVideoItem, onClick: () -> Unit) {
            titleText.text = item.title
            metaText.text = itemView.context.getString(
                R.string.video_meta_format,
                formatDuration(item.durationMs),
                formatSize(item.sizeBytes),
            )
            thumbnail.setImageResource(R.drawable.ic_play)
            itemView.setOnClickListener { onClick() }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = max(0L, durationMs / 1000L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        private fun formatSize(sizeBytes: Long): String {
            if (sizeBytes <= 0L) return "0 B"
            val kb = sizeBytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.1f GB", mb / 1024.0)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_VIDEO = 1
    }
}
