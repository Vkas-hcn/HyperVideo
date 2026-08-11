package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoLoader
import com.vi.pl.videoplayerdemo.local.LocalVideoItem
import com.vi.pl.videoplayerdemo.local.VideoSort

class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var folderAdapter: FolderListAdapter
    private lateinit var videoAdapter: FolderVideoAdapter
    private lateinit var videoList: RecyclerView
    private lateinit var permissionContent: View
    private lateinit var settingsSteps: LinearLayout
    private lateinit var permissionAction: MaterialButton
    private lateinit var emptyText: TextView
    private lateinit var layoutToggle: ImageView
    private lateinit var filterButton: ImageView
    private lateinit var preferences: SharedPreferences
    private var selectedTab = R.id.tab_folders
    private var folderGridMode = false
    private var videoGridMode = false
    private var shortsGridMode = false
    private var videoSort = VideoSort.DATE_NEWEST
    private var shortsSort = VideoSort.DATE_NEWEST

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            preferences.edit().putInt(PREF_DENIAL_COUNT, 0).apply()
            showContent()
        } else {
            val denials = preferences.getInt(PREF_DENIAL_COUNT, 0) + 1
            preferences.edit().putInt(PREF_DENIAL_COUNT, denials).apply()
            showPermissionContent(showSettings = denials >= 2)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = requireContext().getSharedPreferences(PREFS_NAME, 0)
        videoList = view.findViewById(R.id.video_list)
        permissionContent = view.findViewById(R.id.permission_content)
        settingsSteps = view.findViewById(R.id.settings_steps)
        permissionAction = view.findViewById(R.id.permission_action)
        emptyText = view.findViewById(R.id.empty_text)
        layoutToggle = view.findViewById(R.id.layout_toggle)
        filterButton = view.findViewById(R.id.btn_filter)
        videoGridMode = preferences.getBoolean(PREF_VIDEOS_GRID_MODE, false)
        shortsGridMode = preferences.getBoolean(PREF_SHORTS_GRID_MODE, false)
        bindPermissionSteps(view)

        folderAdapter = FolderListAdapter { folder ->
            startActivity(
                FolderDetailActivity.createIntent(requireContext(), folder.path, folder.name),
            )
        }
        videoAdapter = FolderVideoAdapter(::openVideo)
        videoList.layoutManager = LinearLayoutManager(requireContext())
        videoList.adapter = folderAdapter

        listOf(R.id.tab_folders, R.id.tab_videos, R.id.tab_shorts).forEach { id ->
            view.findViewById<TextView>(id).setOnClickListener { selectTab(it as TextView) }
        }
        selectTab(view.findViewById(R.id.tab_folders))

        layoutToggle.setOnClickListener {
            if (!hasVideoPermission()) return@setOnClickListener
            if (selectedTab == R.id.tab_folders) {
                folderGridMode = !folderGridMode
                folderAdapter.setGridMode(folderGridMode)
            } else {
                setVideoGridMode(!videoGridMode())
            }
            updateLayoutToggle()
        }
        filterButton.setOnClickListener { showFilter() }

        permissionAction.setOnClickListener {
            when {
                hasVideoPermission() -> showContent()
                denialCount() >= 2 -> openAppSettings()
                else -> permissionLauncher.launch(requiredVideoPermission())
            }
        }
        updateContent()
    }

    override fun onResume() {
        super.onResume()
        if (view != null && hasVideoPermission()) {
            preferences.edit().putInt(PREF_DENIAL_COUNT, 0).apply()
            showContent()
        }
    }

    private fun bindPermissionSteps(view: View) {
        val steps = listOf(
            R.id.step_one to (1 to R.string.settings_step_one),
            R.id.step_two to (2 to R.string.settings_step_two),
            R.id.step_three to (3 to R.string.settings_step_three),
            R.id.step_four to (4 to R.string.settings_step_four),
        )
        steps.forEach { (includeId, content) ->
            val step = view.findViewById<View>(includeId)
            step.findViewById<TextView>(R.id.step_number).text = content.first.toString()
            step.findViewById<TextView>(R.id.step_text).setText(content.second)
        }
    }

    private fun updateContent() {
        if (hasVideoPermission()) showContent()
        else showPermissionContent(showSettings = denialCount() >= 2)
    }

    private fun showPermissionContent(showSettings: Boolean) {
        permissionContent.visibility = View.VISIBLE
        videoList.visibility = View.GONE
        emptyText.visibility = View.GONE
        layoutToggle.visibility = View.GONE
        filterButton.visibility = View.GONE
        settingsSteps.visibility = if (showSettings) View.VISIBLE else View.GONE
        permissionAction.setText(if (showSettings) R.string.go_to_settings else R.string.grant_access)
    }

    private fun showContent() {
        permissionContent.visibility = View.GONE
        layoutToggle.visibility = View.VISIBLE
        filterButton.visibility = if (selectedTab == R.id.tab_folders) View.GONE else View.VISIBLE
        when (selectedTab) {
            R.id.tab_folders -> showFolders()
            R.id.tab_videos -> showVideos(shortsOnly = false)
            R.id.tab_shorts -> showVideos(shortsOnly = true)
        }
    }

    private fun showFolders() {
        val folders = LocalVideoLoader.loadFolders(requireContext())
        videoList.layoutManager = LinearLayoutManager(requireContext())
        videoList.adapter = folderAdapter
        folderAdapter.setGridMode(folderGridMode)
        folderAdapter.submit(folders)
        updateLayoutToggle()
        videoList.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(R.string.local_video_empty)
    }

    private fun showVideos(shortsOnly: Boolean) {
        val videos = LocalVideoLoader.loadCategories(requireContext())
            .filter { category ->
                category.name.equals("Shorts", ignoreCase = true) == shortsOnly
            }
            .flatMap { it.videos }
        applyVideoLayoutManager()
        videoList.adapter = videoAdapter
        videoAdapter.submit(videos, activeSort(), videoGridMode())
        updateLayoutToggle()
        videoList.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(if (shortsOnly) R.string.local_shorts_empty else R.string.local_video_empty)
    }

    private fun openVideo(item: LocalVideoItem, playlist: List<LocalVideoItem>) {
        startActivity(
            PlayerActivity.createIntent(
                requireContext(),
                getString(if (selectedTab == R.id.tab_shorts) R.string.shorts else R.string.videos),
                ArrayList(playlist.map { it.uri.toString() }),
                ArrayList(playlist.map { it.title }),
                playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0),
            ),
        )
    }

    private fun showFilter() {
        if (selectedTab == R.id.tab_folders) return
        FilterBottomSheet(requireContext(), activeSort()) { selected ->
            if (selectedTab == R.id.tab_shorts) shortsSort = selected else videoSort = selected
            videoAdapter.setSort(selected)
        }.show()
    }

    private fun applyVideoLayoutManager() {
        videoList.layoutManager = if (videoGridMode()) {
            GridLayoutManager(requireContext(), 2).also { it.spanSizeLookup = videoAdapter.spanSizeLookup }
        } else {
            LinearLayoutManager(requireContext())
        }
    }

    private fun setVideoGridMode(enabled: Boolean) {
        if (selectedTab == R.id.tab_shorts) {
            shortsGridMode = enabled
            preferences.edit().putBoolean(PREF_SHORTS_GRID_MODE, enabled).apply()
        } else {
            videoGridMode = enabled
            preferences.edit().putBoolean(PREF_VIDEOS_GRID_MODE, enabled).apply()
        }
        applyVideoLayoutManager()
        videoAdapter.setGridMode(enabled)
    }

    private fun videoGridMode() =
        if (selectedTab == R.id.tab_shorts) shortsGridMode else videoGridMode

    private fun activeSort() =
        if (selectedTab == R.id.tab_shorts) shortsSort else videoSort

    private fun updateLayoutToggle() {
        layoutToggle.setImageResource(
            if (if (selectedTab == R.id.tab_folders) folderGridMode else videoGridMode()) {
                R.drawable.ic_layout2
            } else {
                R.drawable.ic_layout1
            },
        )
    }

    private fun selectTab(selected: TextView) {
        selectedTab = selected.id
        listOf(R.id.tab_folders, R.id.tab_videos, R.id.tab_shorts).forEach { id ->
            view?.findViewById<TextView>(id)?.isSelected = id == selected.id
        }
        if (hasVideoPermission()) showContent()
    }

    private fun denialCount() = preferences.getInt(PREF_DENIAL_COUNT, 0)

    private fun hasVideoPermission() = ContextCompat.checkSelfPermission(
        requireContext(),
        requiredVideoPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun requiredVideoPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            },
        )
    }

    private companion object {
        const val PREFS_NAME = "home_permission"
        const val PREF_DENIAL_COUNT = "permission_denial_count"
        const val PREF_VIDEOS_GRID_MODE = "videos_grid_mode"
        const val PREF_SHORTS_GRID_MODE = "shorts_grid_mode"
    }
}
