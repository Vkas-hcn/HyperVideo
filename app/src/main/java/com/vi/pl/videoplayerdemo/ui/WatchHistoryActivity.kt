package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader
import com.vi.pl.videoplayerdemo.local.WatchHistoryItem
import com.vi.pl.videoplayerdemo.local.WatchHistoryStore

class WatchHistoryActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var edit: TextView
    private lateinit var actions: View
    private lateinit var delete: View
    private lateinit var deleteText: TextView
    private lateinit var selectAllIcon: ImageView
    private lateinit var selectAllText: TextView
    private lateinit var adapter: HistoryAdapter
    private var editing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.watch_history_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.watch_history_list)
        empty = findViewById(R.id.watch_history_empty)
        edit = findViewById(R.id.watch_history_edit)
        actions = findViewById(R.id.watch_history_actions)
        delete = findViewById(R.id.watch_history_delete)
        deleteText = findViewById(R.id.watch_history_delete_text)
        selectAllIcon = findViewById(R.id.watch_history_select_all_icon)
        selectAllText = findViewById(R.id.watch_history_select_all_text)
        adapter = HistoryAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        edit.setOnClickListener { setEditing(!editing) }
        findViewById<View>(R.id.watch_history_select_all).setOnClickListener { toggleSelectAll() }
        delete.setOnClickListener { confirmDelete() }
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val items = WatchHistoryStore.all(this)
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty() && editing) setEditing(false)
        edit.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        adapter.selected.retainAll(items.map { it.videoId }.toSet())
        updateActions()
    }

    private fun setEditing(enabled: Boolean) {
        editing = enabled
        if (!editing) adapter.selected.clear()
        adapter.notifyDataSetChanged()
        updateActions()
    }

    private fun toggleSelectAll() {
        val ids = adapter.items.map { it.videoId }
        if (adapter.selected.size == ids.size) adapter.selected.clear() else adapter.selected.addAll(ids)
        adapter.notifyDataSetChanged()
        updateActions()
    }

    private fun confirmDelete() {
        if (adapter.selected.isEmpty()) return
        AppActionDialog.deleteHistory(this) {
            WatchHistoryStore.remove(this, adapter.selected.toList())
            adapter.selected.clear()
            setEditing(false)
            bind()
        }.show()
    }

    private fun updateActions() {
        edit.setText(if (editing) R.string.done else R.string.edit)
        actions.visibility = if (editing) View.VISIBLE else View.GONE
        val allSelected = adapter.items.isNotEmpty() && adapter.selected.size == adapter.items.size
        selectAllIcon.setImageResource(if (allSelected) R.drawable.ic_select_sel else R.drawable.ic_select_nor)
        selectAllText.setText(if (allSelected) R.string.unselect_all else R.string.select_all)
        deleteText.text = getString(R.string.delete_selected_format, adapter.selected.size)
        delete.alpha = if (adapter.selected.isEmpty()) 0.5f else 1f
    }

    private fun openPlayer(item: WatchHistoryItem) {
        val uri = item.uri ?: return
        startActivity(
            PlayerActivity.createIntent(
                this,
                getString(R.string.profile_watch_history_title),
                arrayListOf(uri.toString()),
                arrayListOf(item.title),
                0,
            ),
        )
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        var items = emptyList<WatchHistoryItem>()
            private set
        val selected = mutableSetOf<String>()

        fun submit(data: List<WatchHistoryItem>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watch_history_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val coverFrame: View = view.findViewById(R.id.history_cover_frame)
            private val cover: ImageView = view.findViewById(R.id.history_cover)
            private val resolution: TextView = view.findViewById(R.id.history_resolution)
            private val select: ImageView = view.findViewById(R.id.history_select)
            private val progress: ProgressBar = view.findViewById(R.id.history_progress)
            private val title: TextView = view.findViewById(R.id.history_title)
            private val watchedAgo: TextView = view.findViewById(R.id.history_watched_ago)

            init {
                coverFrame.outlineProvider = ViewOutlineProvider.BACKGROUND
                coverFrame.clipToOutline = true
                view.setOnClickListener {
                    val item = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    if (editing) {
                        if (!selected.add(item.videoId)) selected.remove(item.videoId)
                        notifyItemChanged(bindingAdapterPosition)
                        updateActions()
                    } else {
                        openPlayer(item)
                    }
                }
            }

            fun bind(item: WatchHistoryItem) {
                title.text = item.title
                watchedAgo.text = VideoFormat.watchedAgo(item.watchedAt)
                val value = item.progress.coerceIn(0, 100)
                progress.progress = value
                select.visibility = if (editing) View.VISIBLE else View.GONE
                select.setImageResource(
                    if (selected.contains(item.videoId)) R.drawable.ic_select_sel else R.drawable.ic_select_nor,
                )
                resolution.text = ""
                resolution.visibility = View.GONE
                resolution.setBackgroundResource(R.drawable.bg_quality_badge)
                val uri = item.uri
                if (uri != null) {
                    VideoThumbnailLoader.load(
                        this@WatchHistoryActivity,
                        item.localId ?: uri.hashCode().toLong(),
                        uri,
                        cover,
                        onVideoSize = { width, height ->
                            quality(width, height)?.let {
                                resolution.text = it
                                resolution.visibility = View.VISIBLE
                            }
                        },
                    )
                } else {
                    cover.setImageResource(R.drawable.ic_play)
                }
            }
        }

        private fun quality(width: Int, height: Int): String? {
            val shortEdge = minOf(width, height)
            return when {
                shortEdge >= 2160 -> "4K"
                shortEdge >= 1440 -> "2K"
                shortEdge >= 1080 -> "1080P"
                shortEdge >= 720 -> "720P"
                shortEdge >= 480 -> "480P"
                shortEdge > 0 -> "${shortEdge}P"
                else -> null
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, WatchHistoryActivity::class.java)
    }
}
