package com.vi.pl.videoplayerdemo.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoSort
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader
import com.vi.pl.videoplayerdemo.local.sortedWith

class FolderVideoAdapter(
    private val onVideoClick: (LocalVideoItem, List<LocalVideoItem>) -> Unit,
    private val onMoreClick: (LocalVideoItem, List<LocalVideoItem>) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    sealed class Row {
        data class Header(val label: String) : Row()
        data class Video(val item: LocalVideoItem) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var videos = emptyList<LocalVideoItem>()
    private var gridMode = false
    private var sort = VideoSort.DATE_NEWEST

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int =
            if (gridMode && rows.getOrNull(position) is Row.Header) 2 else 1
    }

    fun submit(videos: List<LocalVideoItem>, sort: VideoSort, gridMode: Boolean) {
        this.videos = videos.sortedWith(sort)
        this.sort = sort
        this.gridMode = gridMode
        rebuildRows()
        notifyDataSetChanged()
    }

    fun setGridMode(enabled: Boolean) {
        if (gridMode == enabled) return
        gridMode = enabled
        rebuildRows()
        notifyDataSetChanged()
    }

    fun setSort(sort: VideoSort) {
        this.sort = sort
        videos = videos.sortedWith(sort)
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun rebuildRows() {
        rows.clear()
        val sorted = videos
        val useDateHeaders = !gridMode &&
            (sort == VideoSort.DATE_NEWEST || sort == VideoSort.DATE_OLDEST)
        if (useDateHeaders) {
            var lastLabel: String? = null
            sorted.forEach { item ->
                val label = VideoFormat.dateLabel(item.dateAddedSec)
                if (label != lastLabel) {
                    rows += Row.Header(label)
                    lastLabel = label
                }
                rows += Row.Video(item)
            }
        } else {
            sorted.forEach { rows += Row.Video(it) }
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Video -> if (gridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.item_date_header, parent, false),
            )
            VIEW_TYPE_GRID -> GridHolder(
                inflater.inflate(R.layout.item_folder_video_grid, parent, false),
            )
            else -> ListHolder(
                inflater.inflate(R.layout.item_folder_video_list, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.label)
            is Row.Video -> {
                val click = { onVideoClick(row.item, videos) }
                val more = { onMoreClick(row.item, videos) }
                when (holder) {
                    is ListHolder -> holder.bind(row.item, click, more)
                    is GridHolder -> holder.bind(row.item, click, more)
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.date_header)
        fun bind(label: String) {
            text.text = label
        }
    }

    private class ListHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.video_thumbnail)
        private val title: TextView = itemView.findViewById(R.id.video_title)
        private val duration: TextView = itemView.findViewById(R.id.video_duration)
        private val more: ImageView = itemView.findViewById(R.id.video_more)

        fun bind(item: LocalVideoItem, onClick: () -> Unit, onMore: () -> Unit) {
            (thumb.parent as? View)?.apply {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            title.text = item.title
            duration.text = VideoFormat.duration(item.durationMs)
            VideoThumbnailLoader.load(itemView.context, item, thumb)
            itemView.setOnClickListener { onClick() }
            more.setOnClickListener { onMore() }
        }
    }

    private class GridHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.video_thumbnail)
        private val title: TextView = itemView.findViewById(R.id.video_title)
        private val duration: TextView = itemView.findViewById(R.id.video_duration)
        private val more: ImageView = itemView.findViewById(R.id.video_more)

        fun bind(item: LocalVideoItem, onClick: () -> Unit, onMore: () -> Unit) {
            (thumb.parent as? View)?.apply {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            title.text = item.title
            duration.text = VideoFormat.duration(item.durationMs)
            VideoThumbnailLoader.load(itemView.context, item, thumb)
            itemView.setOnClickListener { onClick() }
            more.setOnClickListener { onMore() }
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_LIST = 1
        const val VIEW_TYPE_GRID = 2
    }
}
