package com.vi.pl.videoplayerdemo.ui

import android.app.DownloadManager
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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.DownloadRegistry
import java.util.Locale

class DownloadFragment : Fragment(R.layout.fragment_download) {
    private lateinit var manager: DownloadManager
    private lateinit var adapter: DownloadAdapter
    private lateinit var empty: View
    private lateinit var delete: View
    private lateinit var deleteText: TextView
    private lateinit var edit: TextView
    private lateinit var tabDownloading: TextView
    private lateinit var tabCompleted: TextView
    private var completedTab = false
    private var editing = false
    private val paused = mutableSetOf<Long>()
    private val speedSamples = mutableMapOf<Long, Pair<Long, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            load()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        manager = requireContext().getSystemService(DownloadManager::class.java)
        empty = view.findViewById(R.id.download_empty)
        delete = view.findViewById(R.id.delete_selected)
        deleteText = view.findViewById(R.id.delete_selected_text)
        edit = view.findViewById(R.id.download_edit)
        tabDownloading = view.findViewById(R.id.tab_downloading)
        tabCompleted = view.findViewById(R.id.tab_completed)
        adapter = DownloadAdapter(requireContext())
        view.findViewById<RecyclerView>(R.id.download_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DownloadFragment.adapter
        }
        edit.setOnClickListener {
            editing = !editing
            if (!editing) adapter.selected.clear()
            updateMode()
            adapter.notifyDataSetChanged()
        }
        tabDownloading.setOnClickListener { selectTab(false) }
        tabCompleted.setOnClickListener { selectTab(true) }
        delete.setOnClickListener {
            if (adapter.selected.isEmpty()) return@setOnClickListener
            val removed = adapter.selected.toList()
            removed.forEach { manager.remove(it) }
            DownloadRegistry.remove(requireContext(), removed)
            adapter.selected.clear()
            load()
        }
        selectTab(false)
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun selectTab(completed: Boolean) {
        completedTab = completed
        adapter.selected.clear()
        editing = false
        tabDownloading.isSelected = !completed
        tabCompleted.isSelected = completed
        load()
    }

    private fun load() {
        if (!::manager.isInitialized) return
        val result = mutableListOf<DownloadRecord>()
        val registered = DownloadRegistry.ids(requireContext())
        val query = if (registered.isEmpty()) {
            DownloadManager.Query()
        } else {
            DownloadManager.Query().setFilterById(*registered.toLongArray())
        }
        manager.query(query).use { c ->
            val id = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val title = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val status = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytes = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (c.moveToNext()) {
                val taskId = c.getLong(id)
                val done = c.getInt(status) == DownloadManager.STATUS_SUCCESSFUL
                if (done != completedTab) continue
                val now = System.currentTimeMillis()
                val b = c.getLong(bytes).coerceAtLeast(0)
                val old = speedSamples[taskId]
                val speed = if (old != null && now > old.second) {
                    ((b - old.first) * 1000L / (now - old.second)).coerceAtLeast(0)
                } else {
                    0L
                }
                speedSamples[taskId] = b to now
                result += DownloadRecord(
                    taskId,
                    c.getString(title).orEmpty().ifBlank { "Downloaded video" },
                    c.getString(uriCol),
                    b,
                    c.getLong(total),
                    speed,
                )
            }
        }
        adapter.items = result
        adapter.notifyDataSetChanged()
        empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        updateMode()
    }

    private fun updateMode() {
        edit.text = if (editing) "Done" else "Edit"
        delete.visibility = if (editing) View.VISIBLE else View.GONE
        deleteText.text = "Delete Selected (${adapter.selected.size})"
        delete.alpha = if (adapter.selected.isEmpty()) 0.5f else 1f
    }

    private data class DownloadRecord(
        val id: Long,
        val title: String,
        val uri: String?,
        val bytes: Long,
        val total: Long,
        val speed: Long,
    )

    private inner class DownloadAdapter(
        private val context: Context,
    ) : RecyclerView.Adapter<DownloadAdapter.Holder>() {
        var items = emptyList<DownloadRecord>()
        val selected = mutableSetOf<Long>()

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = items[pos]
            h.bind(item, selected.contains(item.id))
            h.root.setOnClickListener {
                if (editing) {
                    if (!selected.add(item.id)) selected.remove(item.id)
                    notifyItemChanged(pos)
                    updateMode()
                } else if (completedTab) {
                    play(item)
                }
            }
            h.pause.setOnClickListener {
                if (!paused.remove(item.id)) paused.add(item.id)
                notifyItemChanged(pos)
            }
            h.play.setOnClickListener { play(item) }
        }

        private fun play(item: DownloadRecord) {
            val uri = item.uri?.let(Uri::parse) ?: return
            if (uri.scheme == "file" && !java.io.File(uri.path.orEmpty()).exists()) {
                Toast.makeText(context, "Video file not found", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(
                PlayerActivity.createIntent(
                    context,
                    "Downloads",
                    arrayListOf(uri.toString()),
                    arrayListOf(item.title),
                    0,
                ),
            )
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.download_item_root)
            val select: ImageView = view.findViewById(R.id.download_select)
            val thumb: ImageView = view.findViewById(R.id.download_thumb)
            val badge: TextView = view.findViewById(R.id.download_badge)
            val title: TextView = view.findViewById(R.id.download_item_title)
            val subtitle: TextView = view.findViewById(R.id.download_item_subtitle)
            val progress: ProgressBar = view.findViewById(R.id.download_progress)
            val pause: ImageView = view.findViewById(R.id.download_pause)
            val play: LinearLayout = view.findViewById(R.id.download_play)

            init {
                (thumb.parent as View).apply {
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
            }

            fun bind(item: DownloadRecord, checked: Boolean) {
                val pad = if (editing) (12 * context.resources.displayMetrics.density).toInt() else 0
                root.setPadding(pad, pad, pad, pad)
                root.setBackgroundResource(if (editing) R.drawable.bg_download_item_edit else 0)
                select.visibility = if (editing) View.VISIBLE else View.GONE
                select.setImageResource(if (checked) R.drawable.ic_select_sel else R.drawable.ic_select_nor)
                title.text = item.title
                val quality = quality(item.title)
                badge.text = quality
                badge.setBackgroundResource(
                    if (quality.equals("Dolby", true)) R.drawable.bg_quality_badge_dolby
                    else R.drawable.bg_quality_badge,
                )
                item.uri?.let { thumb.setImageURI(Uri.parse(it)) }
                    ?: thumb.setImageResource(R.drawable.ic_play)
                if (completedTab) {
                    // Design shows "4K · 2.4MB/s"; keep same visual format with size.
                    subtitle.text = "$quality · ${formatSize(item.total.coerceAtLeast(item.bytes))}"
                    progress.visibility = View.GONE
                    pause.visibility = View.GONE
                    play.visibility = View.VISIBLE
                } else {
                    subtitle.text = formatSpeed(item.speed)
                    progress.visibility = View.VISIBLE
                    progress.progress = if (item.total > 0) ((item.bytes * 100) / item.total).toInt() else 0
                    pause.visibility = View.VISIBLE
                    pause.setImageResource(
                        if (paused.contains(item.id)) R.drawable.ic_play else R.drawable.ic_pause_download,
                    )
                    play.visibility = View.GONE
                }
            }
        }

        private fun quality(title: String) = when {
            title.contains("dolby", true) -> "Dolby"
            title.contains("1080", true) -> "1080P"
            else -> "4K"
        }

        private fun formatSpeed(bytes: Long): String = when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1fMB/s", bytes / 1_000_000f)
            bytes >= 1_000 -> "${bytes / 1_000}KB/s"
            else -> "0 KB/s"
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1fGB", bytes / 1_000_000_000f)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1fMB", bytes / 1_000_000f)
            bytes >= 1_000 -> "${bytes / 1_000}KB"
            else -> "0KB"
        }
    }
}
