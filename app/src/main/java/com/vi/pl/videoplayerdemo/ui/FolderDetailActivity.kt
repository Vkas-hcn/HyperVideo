package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.FavoriteStore
import com.vi.pl.videoplayerdemo.local.LocalVideoActions
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
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
    private var pendingRename: Pair<Uri, String>? = null

    private val mediaRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val rename = pendingRename
        pendingRename = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        if (rename != null) {
            val ok = LocalVideoActions.applyRename(this, rename.first, rename.second, rename.second)
            if (!ok) Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
        }
        reload()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* playback still starts; notification may be limited without grant */ }

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

        adapter = FolderVideoAdapter(
            onVideoClick = { item, playlist -> openPlayer(item, playlist) },
            onMoreClick = { item, playlist -> showMoreMenu(item, playlist) },
        )
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

    private fun showMoreMenu(item: LocalVideoItem, playlist: List<LocalVideoItem>) {
        VideoMoreBottomSheet(this, FavoriteStore.contains(this, item.uri.toString())) { action ->
            when (action) {
                VideoMoreBottomSheet.Action.PLAY_BACKGROUND -> {
                    maybeRequestNotificationPermission()
                    openPlayer(item, playlist, background = true)
                }
                VideoMoreBottomSheet.Action.PLAY_MUTED ->
                    openPlayer(item, playlist, muted = true)
                VideoMoreBottomSheet.Action.FAVORITE -> FavoriteStore.toggle(this, item)
                VideoMoreBottomSheet.Action.RENAME -> showRename(item)
                VideoMoreBottomSheet.Action.SHARE -> LocalVideoActions.share(this, item)
                VideoMoreBottomSheet.Action.DELETE -> showDelete(item)
            }
        }.show()
    }

    private fun showRename(item: LocalVideoItem) {
        AppActionDialog.rename(this, item.title) { newName ->
            try {
                val pending = LocalVideoActions.rename(this, item, newName)
                if (pending != null) {
                    pendingRename = item.uri to newName
                    mediaRequestLauncher.launch(
                        IntentSenderRequest.Builder(pending).build(),
                    )
                } else {
                    reload()
                }
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun showDelete(item: LocalVideoItem) {
        AppActionDialog.delete(this) {
            try {
                val pending = LocalVideoActions.delete(this, item)
                if (pending != null) {
                    pendingRename = null
                    mediaRequestLauncher.launch(
                        IntentSenderRequest.Builder(pending).build(),
                    )
                } else {
                    reload()
                }
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun openPlayer(
        item: LocalVideoItem,
        playlist: List<LocalVideoItem>,
        muted: Boolean = false,
        background: Boolean = false,
    ) {
        val index = playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        startActivity(
            PlayerActivity.createIntent(
                this,
                folderName,
                ArrayList(playlist.map { it.uri.toString() }),
                ArrayList(playlist.map { it.title }),
                index,
                startMuted = muted,
                playInBackground = background,
            ),
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
