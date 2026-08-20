package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Shader
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
import com.vi.pl.videoplayerdemo.local.UserSession
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader
import com.vi.pl.videoplayerdemo.local.WatchHistoryItem
import com.vi.pl.videoplayerdemo.local.WatchHistoryStore

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
            val ctx = requireContext()
            startActivity(
                if (UserSession.get(ctx) == null) {
                    LoginActivity.createIntent(ctx)
                } else {
                    ProfileEditActivity.createIntent(ctx)
                },
            )
        }
        view.findViewById<View>(R.id.profile_manage_plan).setOnClickListener {
            startActivity(SubscriptionActivity.createIntent(requireContext()))
        }
        view.findViewById<View>(R.id.profile_history_header).setOnClickListener {
            startActivity(WatchHistoryActivity.createIntent(requireContext()))
        }
        view.findViewById<View>(R.id.profile_favorites).setOnClickListener {
            startActivity(FavoritesActivity.createIntent(requireContext()))
        }
        view.findViewById<View>(R.id.profile_help).setOnClickListener {
            FeedbackDialog(requireContext()).show()
        }
        view.findViewById<View>(R.id.profile_privacy).setOnClickListener {
            startActivity(PrivacyActivity.createIntent(requireContext()))
        }
        bindHistory()
        bindUser()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) bindHistory()
        if (view != null) bindUser()
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

    private fun bindUser() {
        val root = view ?: return
        val user = UserSession.get(requireContext())
        root.findViewById<TextView>(R.id.profile_user_name).text =
            user?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.profile_guest_name)
        root.findViewById<TextView>(R.id.profile_user_meta).text =
            user?.email?.takeIf { it.isNotBlank() } ?: getString(R.string.profile_guest_meta)
        UserSession.bindPhoto(root.findViewById(R.id.profile_avatar), user?.photoUrl)
    }

    private fun bindHistory() {
        val items = WatchHistoryStore.all(requireContext()).take(3)
        adapter.submit(items)
        historyList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        historyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

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
                resolution.text = ""
                resolution.visibility = View.GONE
                resolution.setBackgroundResource(R.drawable.bg_quality_badge)
                item.uri?.let { uri ->
                    VideoThumbnailLoader.load(
                        context,
                        item.localId ?: uri.hashCode().toLong(),
                        uri,
                        cover,
                        onVideoSize = { width, height ->
                            VideoFormat.qualityLabel(width, height)?.let {
                                resolution.text = it
                                resolution.visibility = View.VISIBLE
                            }
                        },
                    )
                } ?: cover.setImageResource(R.drawable.ic_play)
            }
        }
    }
}
