package com.vi.pl.videoplayerdemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoLoader
import com.vi.pl.videoplayerdemo.local.VideoSort

class FolderDetailActivity : AppCompatActivity() {
    private lateinit var videoList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var layoutToggle: ImageView
    private lateinit var adapter: FolderVideoAdapter
    private lateinit var folderPath: String
    private lateinit var folderName: String
    private var gridMode = false
    private var sort = VideoSort.DATE_NEWEST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_detail)
        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH).orEmpty()
        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME).orEmpty()
        if (folderPath.isBlank()) {
            finish()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.folder_detail_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        videoList = findViewById(R.id.video_list)
        emptyText = findViewById(R.id.empty_text)
        layoutToggle = findViewById(R.id.layout_toggle)
        findViewById<TextView>(R.id.folder_title).text = folderName
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_filter).setOnClickListener { showFilter() }
        layoutToggle.setOnClickListener { toggleLayout() }

        adapter = FolderVideoAdapter { item, playlist ->
            val index = playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            startActivity(
                PlayerActivity.createIntent(
                    this,
                    folderName,
                    ArrayList(playlist.map { it.uri.toString() }),
                    ArrayList(playlist.map { it.title }),
                    index,
                ),
            )
        }
        applyLayoutManager()
        videoList.adapter = adapter
        reload()
    }

    private fun reload() {
        val videos = LocalVideoLoader.loadFolderVideos(this, folderPath)
        adapter.submit(videos, sort, gridMode)
        val empty = videos.isEmpty()
        videoList.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun toggleLayout() {
        gridMode = !gridMode
        layoutToggle.setImageResource(if (gridMode) R.drawable.ic_layout2 else R.drawable.ic_layout1)
        applyLayoutManager()
        adapter.setGridMode(gridMode)
    }

    private fun applyLayoutManager() {
        videoList.layoutManager = if (gridMode) {
            GridLayoutManager(this, 2).also { it.spanSizeLookup = adapter.spanSizeLookup }
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun showFilter() {
        FilterBottomSheet(this, sort) { selected ->
            sort = selected
            adapter.setSort(sort)
        }.show()
    }

    companion object {
        private const val EXTRA_FOLDER_PATH = "folder_path"
        private const val EXTRA_FOLDER_NAME = "folder_name"

        fun createIntent(context: Context, folderPath: String, folderName: String): Intent =
            Intent(context, FolderDetailActivity::class.java)
                .putExtra(EXTRA_FOLDER_PATH, folderPath)
                .putExtra(EXTRA_FOLDER_NAME, folderName)
    }
}
