package com.vi.pl.videoplayerdemo.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
import com.vi.pl.videoplayerdemo.local.VideoDurationLoader
import com.vi.pl.videoplayerdemo.local.VideoFolder
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader

class FolderListAdapter(
    private val onFolderClick: (VideoFolder) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val folders = mutableListOf<VideoFolder>()
    private var gridMode = false

    fun submit(folders: List<VideoFolder>) {
        this.folders.clear()
        this.folders.addAll(folders)
        notifyDataSetChanged()
    }

    fun setGridMode(enabled: Boolean) {
        if (gridMode == enabled) return
        gridMode = enabled
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (gridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            GridHolder(inflater.inflate(R.layout.item_folder_grid, parent, false))
        } else {
            ListHolder(inflater.inflate(R.layout.item_folder_list, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val folder = folders[position]
        when (holder) {
            is ListHolder -> holder.bind(folder, onFolderClick)
            is GridHolder -> holder.bind(folder, onFolderClick)
        }
    }

    override fun getItemCount(): Int = folders.size

    private class ListHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.folder_icon)
        private val name: TextView = itemView.findViewById(R.id.folder_name)
        private val count: TextView = itemView.findViewById(R.id.folder_count)

        fun bind(folder: VideoFolder, onClick: (VideoFolder) -> Unit) {
            icon.setImageResource(folder.iconRes())
            name.text = folder.name
            count.text = itemView.context.getString(R.string.video_count_format, folder.count)
            itemView.setOnClickListener { onClick(folder) }
        }
    }

    private class GridHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.folder_icon)
        private val name: TextView = itemView.findViewById(R.id.folder_name)
        private val count: TextView = itemView.findViewById(R.id.folder_count)
        private val thumbs = listOf(
            itemView.findViewById<View>(R.id.thumb_one),
            itemView.findViewById(R.id.thumb_two),
            itemView.findViewById(R.id.thumb_three),
        )

        fun bind(folder: VideoFolder, onClick: (VideoFolder) -> Unit) {
            icon.setImageResource(folder.iconRes())
            name.text = folder.name
            count.text = itemView.context.getString(R.string.video_count_format, folder.count)
            val preview = folder.videos.take(3)
            thumbs.forEachIndexed { index, thumb ->
                bindThumb(thumb, preview.getOrNull(index))
            }
            itemView.setOnClickListener { onClick(folder) }
        }

        private fun bindThumb(root: View, item: LocalVideoItem?) {
            root.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
            if (item == null) return
            root.outlineProvider = ViewOutlineProvider.BACKGROUND
            root.clipToOutline = true
            val image = root.findViewById<ImageView>(R.id.thumb_image)
            val duration = root.findViewById<TextView>(R.id.thumb_duration)
            VideoDurationLoader.bind(root.context, item, duration)
            VideoThumbnailLoader.load(root.context, item, image)
        }
    }

    private companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }
}
