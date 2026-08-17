package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.FavoriteItem
import com.vi.pl.videoplayerdemo.local.FavoriteStore
import com.vi.pl.videoplayerdemo.local.VideoFormat
import com.vi.pl.videoplayerdemo.local.VideoThumbnailLoader

class FavoritesActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var adapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.favorites_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.favorites_list)
        empty = findViewById(R.id.favorites_empty)
        adapter = FavoritesAdapter()
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        adapter.submit(FavoriteStore.all(this))
        updateEmpty()
    }

    private fun updateEmpty() {
        val noItems = adapter.itemCount == 0
        empty.visibility = if (noItems) View.VISIBLE else View.GONE
        list.visibility = if (noItems) View.GONE else View.VISIBLE
    }

    private fun openPlayer(item: FavoriteItem) {
        val uri = item.uri ?: return
        startActivity(
            PlayerActivity.createIntent(
                this,
                getString(R.string.profile_favorites),
                arrayListOf(uri.toString()),
                arrayListOf(item.title),
                0,
            ),
        )
    }

    private inner class FavoritesAdapter : RecyclerView.Adapter<FavoritesAdapter.Holder>() {
        private val items = mutableListOf<FavoriteItem>()

        fun submit(data: List<FavoriteItem>) {
            items.clear()
            items += data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val cover: ImageView = view.findViewById(R.id.favorite_cover)
            private val heart: ImageView = view.findViewById(R.id.favorite_heart)
            private val resolution: TextView = view.findViewById(R.id.favorite_resolution)
            private val title: TextView = view.findViewById(R.id.favorite_title)
            private val duration: TextView = view.findViewById(R.id.favorite_duration)

            init {
                view.outlineProvider = ViewOutlineProvider.BACKGROUND
                view.clipToOutline = true
                view.setOnClickListener {
                    val item = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    openPlayer(item)
                }
                heart.setOnClickListener {
                    val position = bindingAdapterPosition
                    val item = items.getOrNull(position) ?: return@setOnClickListener
                    FavoriteStore.remove(this@FavoritesActivity, listOf(item.videoId))
                    items.removeAt(position)
                    notifyItemRemoved(position)
                    updateEmpty()
                }
            }

            fun bind(item: FavoriteItem) {
                title.text = item.title
                duration.text = VideoFormat.duration(item.durationMs)
                resolution.text = ""
                resolution.visibility = View.GONE
                resolution.setBackgroundResource(R.drawable.bg_quality_badge)
                val uri = item.uri
                if (uri != null) {
                    VideoThumbnailLoader.load(
                        this@FavoritesActivity,
                        item.localId ?: uri.hashCode().toLong(),
                        uri,
                        cover,
                        onVideoSize = { width, height ->
                            quality(width, height)?.let { label ->
                                resolution.text = label
                                resolution.setBackgroundResource(badgeBackground(label))
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

        private fun badgeBackground(label: String): Int = when (label.uppercase()) {
            "HDR" -> R.drawable.bg_quality_badge_hdr
            "DOLBY" -> R.drawable.bg_quality_badge_dolby
            else -> R.drawable.bg_quality_badge
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, FavoritesActivity::class.java)
    }
}
