package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
import com.vi.pl.videoplayerdemo.local.LocalVideoLoader
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private lateinit var historyList: RecyclerView
    private lateinit var historyEmpty: View
    private lateinit var adapter: WatchHistoryAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ImageView>(R.id.profile_avatar).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        view.findViewById<View>(R.id.profile_vip_card).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        applyVipTitleGradient(view.findViewById(R.id.tv_vip_title))
        historyList = view.findViewById(R.id.profile_history_list)
        historyEmpty = view.findViewById(R.id.profile_history_empty)
        adapter = WatchHistoryAdapter(requireContext()) { item, playlist ->
            openHistory(item, playlist)
        }
        historyList.layoutManager = GridLayoutManager(requireContext(), 3)
        historyList.adapter = adapter
        historyList.isNestedScrollingEnabled = false
        val gap = (8 * resources.displayMetrics.density).toInt()
        historyList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position in 0 until 2) outRect.right = gap
            }
        })

        view.findViewById<View>(R.id.profile_user_row).setOnClickListener {
            startActivity(ProfileEditActivity.createIntent(requireContext()))
        }
        view.findViewById<View>(R.id.profile_manage_plan).setOnClickListener {
            // TODO: Implement Google Play subscription flow.
            Toast.makeText(requireContext(), R.string.feature_placeholder, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.profile_history_header).setOnClickListener {
            startActivity(WatchHistoryActivity.createIntent(requireContext()))
        }
        view.findViewById<View>(R.id.profile_favorites).setOnClickListener {
            // TODO: Implement Favorites feature in future iteration.
            Toast.makeText(requireContext(), R.string.feature_placeholder, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.profile_help).setOnClickListener {
            // TODO: Implement Help & Support feature in future iteration.
            Toast.makeText(requireContext(), R.string.feature_placeholder, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.profile_privacy).setOnClickListener {
            // TODO: Implement Privacy page in future iteration.
            Toast.makeText(requireContext(), R.string.feature_placeholder, Toast.LENGTH_SHORT).show()
        }
        bindHistory()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) bindHistory()
    }

    private fun applyVipTitleGradient(title: TextView) {
        title.post {
            val width = title.paint.measureText(title.text.toString()).coerceAtLeast(1f)
            title.paint.shader = LinearGradient(
                0f,
                0f,
                width,
                0f,
                ContextCompat.getColor(requireContext(), R.color.vip_gradient_start),
                ContextCompat.getColor(requireContext(), R.color.vip_gradient_end),
                Shader.TileMode.CLAMP,
            )
            title.invalidate()
        }
    }

    private fun bindHistory() {
        val items = loadHistory().take(3)
        adapter.submit(items)
        historyList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        historyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadHistory(): List<WatchHistoryItem> {
        val local = loadLocalVideos()
        if (local.isNotEmpty()) {
            val store = requireContext().getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            return local.take(3).mapIndexed { index, video ->
                video.toHistory(index, storedProgress(store, video))
            }
        }
        return mockHistory()
    }

    private fun loadLocalVideos(): List<LocalVideoItem> {
        if (!hasVideoPermission()) return emptyList()
        return runCatching {
            LocalVideoLoader.loadCategories(requireContext()).flatMap { it.videos }
        }.getOrDefault(emptyList())
    }

    private fun storedProgress(store: SharedPreferences, video: LocalVideoItem): Int {
        val position = store.getLong("progress_${video.uri}", 0L).coerceAtLeast(0L)
        if (video.durationMs <= 0L) return MOCK_PROGRESS.getOrElse(0) { 35 }
        return ((position * 100) / video.durationMs).toInt().coerceIn(0, 100)
    }

    private fun mockHistory(): List<WatchHistoryItem> = listOf(
        WatchHistoryItem("mock_1", "Star Frontier II", null, "4K", 35, 260_000L, null),
        WatchHistoryItem("mock_2", "Night Pursuit", null, "HDR", 55, 240_000L, null),
        WatchHistoryItem("mock_3", "Ocean's Soul", null, "Dolby", 20, 190_000L, null),
    )

    private fun openHistory(item: WatchHistoryItem, playlist: List<WatchHistoryItem>) {
        val playable = playlist.mapNotNull { history ->
            history.uri?.let { history to it }
        }
        if (item.uri == null || playable.isEmpty()) {
            // TODO: Open player when watch history has a real video source.
            Toast.makeText(requireContext(), R.string.feature_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        val start = playable.indexOfFirst { it.first.videoId == item.videoId }.coerceAtLeast(0)
        startActivity(
            PlayerActivity.createIntent(
                requireContext(),
                getString(R.string.profile_continue_watching),
                ArrayList(playable.map { it.second.toString() }),
                ArrayList(playable.map { it.first.title }),
                start,
            ),
        )
    }

    private fun hasVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun LocalVideoItem.toHistory(index: Int, progress: Int): WatchHistoryItem {
        val resolved = progress.takeIf { it > 0 } ?: MOCK_PROGRESS.getOrElse(index) { 35 }
        return WatchHistoryItem(
            videoId = id.toString(),
            title = title,
            uri = uri,
            resolution = MOCK_RESOLUTION.getOrElse(index) { "1080P" },
            progress = resolved.coerceIn(0, 100),
            durationMs = durationMs,
            localId = id,
        )
    }

    private class WatchHistoryAdapter(
        private val context: Context,
        private val onClick: (WatchHistoryItem, List<WatchHistoryItem>) -> Unit,
    ) : RecyclerView.Adapter<WatchHistoryAdapter.Holder>() {
        private val items = mutableListOf<WatchHistoryItem>()

        fun submit(data: List<WatchHistoryItem>) {
            items.clear()
            items += data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watch_history, parent, false)
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
            private val duration: TextView = view.findViewById(R.id.history_duration)
            private val progress: ProgressBar = view.findViewById(R.id.history_progress)
            private val title: TextView = view.findViewById(R.id.history_title)

            init {
                coverFrame.outlineProvider = ViewOutlineProvider.BACKGROUND
                coverFrame.clipToOutline = true
                view.setOnClickListener {
                    val item = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    onClick(item, items)
                }
            }

            fun bind(item: WatchHistoryItem) {
                title.text = item.title
                duration.text = VideoFormat.duration(item.durationMs)
                progress.progress = item.progress.coerceIn(0, 100)
                resolution.text = item.resolution
                resolution.setBackgroundResource(badgeBackground(item.resolution))
                val localId = item.localId
                val uri = item.uri
                if (localId != null && uri != null) {
                    VideoThumbnailLoader.load(
                        context,
                        localId,
                        uri,
                        cover,
                        onVideoSize = { width, height ->
                            quality(width, height)?.let { label ->
                                resolution.text = label
                                resolution.setBackgroundResource(badgeBackground(label))
                            }
                        },
                    )
                } else {
                    cover.setImageResource(R.drawable.ic_play)
                }
            }
        }

        private fun badgeBackground(label: String): Int = when (label.uppercase()) {
            "HDR" -> R.drawable.bg_quality_badge_hdr
            "DOLBY" -> R.drawable.bg_quality_badge_dolby
            else -> R.drawable.bg_quality_badge
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

    private companion object {
        const val PROGRESS_PREFS = "video_player_progress"
        val MOCK_PROGRESS = listOf(35, 55, 20)
        val MOCK_RESOLUTION = listOf("4K", "HDR", "Dolby")
    }
}

data class WatchHistoryItem(
    val videoId: String,
    val title: String,
    val uri: Uri?,
    val resolution: String,
    val progress: Int,
    val durationMs: Long,
    val localId: Long?,
)
