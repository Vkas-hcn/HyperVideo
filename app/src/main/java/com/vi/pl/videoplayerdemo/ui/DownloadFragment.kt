package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.DownloadDatabase
import com.vi.pl.videoplayerdemo.download.DownloadJob
import com.vi.pl.videoplayerdemo.download.DownloadState
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader
import java.io.File
import java.util.concurrent.Executors

class DownloadFragment : Fragment(R.layout.fragment_download) {
    private lateinit var adapter: DownloadAdapter
    private lateinit var empty: View
    private lateinit var tabDownloading: TextView
    private lateinit var tabCompleted: TextView
    private var completedTab = false
    private val main = Handler(Looper.getMainLooper())
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private val refresh = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        empty = view.findViewById(R.id.download_empty)
        tabDownloading = view.findViewById(R.id.tab_downloading)
        tabCompleted = view.findViewById(R.id.tab_completed)
        adapter = DownloadAdapter(requireContext())
        view.findViewById<RecyclerView>(R.id.download_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DownloadFragment.adapter
        }
        view.findViewById<View>(R.id.download_edit).visibility = View.GONE
        view.findViewById<View>(R.id.delete_selected).visibility = View.GONE
        tabDownloading.setOnClickListener { selectTab(false) }
        tabCompleted.setOnClickListener { selectTab(true) }
        selectTab(false)
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        super.onStop()
    }

    private fun selectTab(completed: Boolean) {
        completedTab = completed
        tabDownloading.isSelected = !completed
        tabCompleted.isSelected = completed
        load()
    }

    private fun load() {
        if (!isAdded) return
        val app = requireContext().applicationContext
        databaseExecutor.execute {
            val jobs = DownloadDatabase.get(app).jobs().all()
                .filter { (it.state == DownloadState.COMPLETED) == completedTab }
            main.post {
                if (!isAdded) return@post
                adapter.items = jobs
                adapter.notifyDataSetChanged()
                empty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private inner class DownloadAdapter(private val context: Context) : RecyclerView.Adapter<DownloadAdapter.Holder>() {
        var items = emptyList<DownloadJob>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false),
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.root.setOnClickListener {
                if (item.state == DownloadState.COMPLETED) play(item)
            }
            holder.pause.setOnClickListener {
                VideoDownloadRepository.cancel(context, item.id)
                item.state = DownloadState.CANCELLED
                holder.bind(item)
            }
            holder.play.setOnClickListener { play(item) }
        }

        private fun play(item: DownloadJob) {
            val uri = item.mediaUri?.let(Uri::parse) ?: return
            startActivity(PlayerActivity.createIntent(context, "Downloads", arrayListOf(uri.toString()), arrayListOf(item.title), 0))
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.download_item_root)
            private val coverFrame: View = view.findViewById(R.id.download_cover_frame)
            private val thumb: ImageView = view.findViewById(R.id.download_thumb)
            private val badge: TextView = view.findViewById(R.id.download_badge)
            private val title: TextView = view.findViewById(R.id.download_item_title)
            private val subtitle: TextView = view.findViewById(R.id.download_item_subtitle)
            private val progress: ProgressBar = view.findViewById(R.id.download_progress)
            val pause: ImageView = view.findViewById(R.id.download_pause)
            val play: LinearLayout = view.findViewById(R.id.download_play)

            init {
                coverFrame.outlineProvider = ViewOutlineProvider.BACKGROUND
                coverFrame.clipToOutline = true
            }

            fun bind(item: DownloadJob) {
                title.text = item.title
                bindCoverAndQuality(item)
                if (item.state == DownloadState.COMPLETED) {
                    subtitle.text = "Completed"
                    progress.visibility = View.GONE
                    pause.visibility = View.GONE
                    play.visibility = View.VISIBLE
                } else {
                    subtitle.text = item.failureReason ?: stateLabel(item.state)
                    progress.visibility = View.VISIBLE
                    progress.progress = item.progress
                    pause.visibility = if (item.state in setOf(DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.VALIDATING)) View.VISIBLE else View.GONE
                    play.visibility = View.GONE
                }
            }

            private fun bindCoverAndQuality(item: DownloadJob) {
                badge.visibility = View.GONE
                badge.setBackgroundResource(R.drawable.bg_quality_badge)
                val mediaUri = item.mediaUri?.let(Uri::parse)
                if (mediaUri != null) {
                    VideoThumbnailLoader.load(
                        context,
                        mediaUri.lastPathSegment?.toLongOrNull() ?: mediaUri.hashCode().toLong(),
                        mediaUri,
                        thumb,
                        onVideoSize = { width, height -> showQuality(width, height) },
                    )
                    return
                }
                val dir = VideoDownloadRepository.jobDir(context, item.id)
                val cover = File(dir, "cover.jpg").takeIf { it.isFile }
                    ?: dir.listFiles()?.filter {
                        it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
                    }?.maxByOrNull { it.lastModified() }
                if (cover != null) {
                    VideoThumbnailLoader.loadFile(context, item.id.hashCode().toLong(), cover, thumb)
                } else {
                    thumb.setImageResource(R.drawable.ic_default_cover)
                }
                readSize(dir)?.let { (width, height) -> showQuality(width, height) }
            }

            private fun showQuality(width: Int, height: Int) {
                VideoFormat.qualityLabel(width, height)?.let {
                    badge.text = it
                    badge.visibility = View.VISIBLE
                }
            }

            private fun readSize(dir: File): Pair<Int, Int>? {
                val raw = File(dir, "size.txt").takeIf { it.isFile }?.readText()?.trim() ?: return null
                val parts = raw.split(',', 'x')
                val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val height = parts.getOrNull(1)?.toIntOrNull() ?: return null
                return if (width > 0 && height > 0) width to height else null
            }
        }

        private fun stateLabel(state: DownloadState): String = when (state) {
            DownloadState.QUEUED -> "Queued"
            DownloadState.RESOLVING -> "Resolving stream"
            DownloadState.DOWNLOADING -> "Downloading"
            DownloadState.MERGING -> "Saving video"
            DownloadState.VALIDATING -> "Validating video"
            DownloadState.CANCELLED -> "Cancelled"
            DownloadState.FAILED -> "Failed"
            DownloadState.COMPLETED -> "Completed"
        }
    }
}
