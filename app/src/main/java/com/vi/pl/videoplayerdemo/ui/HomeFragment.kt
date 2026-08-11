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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vi.pl.videoplayerdemo.PlayerActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.local.LocalVideoLoader

class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var folderAdapter: FolderListAdapter
    private lateinit var videoAdapter: LocalVideoListAdapter
    private lateinit var videoList: RecyclerView
    private lateinit var permissionContent: View
    private lateinit var settingsSteps: LinearLayout
    private lateinit var permissionAction: MaterialButton
    private lateinit var emptyText: TextView
    private lateinit var layoutToggle: ImageView
    private lateinit var preferences: SharedPreferences
    private var selectedTab = R.id.tab_folders
    private var folderGridMode = false

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
        bindPermissionSteps(view)

        folderAdapter = FolderListAdapter { folder ->
            startActivity(
                FolderDetailActivity.createIntent(requireContext(), folder.path, folder.name),
            )
        }
        videoAdapter = LocalVideoListAdapter { category, index ->
            val playlist = category.videos
            startActivity(
                PlayerActivity.createIntent(
                    requireContext(),
                    category.name,
                    ArrayList(playlist.map { it.uri.toString() }),
                    ArrayList(playlist.map { it.title }),
                    index,
                ),
            )
        }
        videoList.layoutManager = LinearLayoutManager(requireContext())
        videoList.adapter = folderAdapter

        listOf(R.id.tab_folders, R.id.tab_videos, R.id.tab_shorts).forEach { id ->
            view.findViewById<TextView>(id).setOnClickListener { selectTab(it as TextView) }
        }
        selectTab(view.findViewById(R.id.tab_folders))

        layoutToggle.setOnClickListener {
            if (selectedTab != R.id.tab_folders || !hasVideoPermission()) return@setOnClickListener
            folderGridMode = !folderGridMode
            layoutToggle.setImageResource(
                if (folderGridMode) R.drawable.ic_layout2 else R.drawable.ic_layout1,
            )
            folderAdapter.setGridMode(folderGridMode)
        }

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
        settingsSteps.visibility = if (showSettings) View.VISIBLE else View.GONE
        permissionAction.setText(if (showSettings) R.string.go_to_settings else R.string.grant_access)
    }

    private fun showContent() {
        permissionContent.visibility = View.GONE
        layoutToggle.visibility = if (selectedTab == R.id.tab_folders) View.VISIBLE else View.GONE
        when (selectedTab) {
            R.id.tab_folders -> showFolders()
            R.id.tab_videos -> showVideos(shortsOnly = false)
            R.id.tab_shorts -> showVideos(shortsOnly = true)
        }
    }

    private fun showFolders() {
        val folders = LocalVideoLoader.loadFolders(requireContext())
        videoList.adapter = folderAdapter
        folderAdapter.setGridMode(folderGridMode)
        folderAdapter.submit(folders)
        layoutToggle.setImageResource(
            if (folderGridMode) R.drawable.ic_layout2 else R.drawable.ic_layout1,
        )
        videoList.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(R.string.local_video_empty)
    }

    private fun showVideos(shortsOnly: Boolean) {
        val categories = LocalVideoLoader.loadCategories(requireContext())
            .filter { category ->
                category.name.equals("Shorts", ignoreCase = true) == shortsOnly
            }
        videoList.adapter = videoAdapter
        videoAdapter.submitCategories(categories)
        videoList.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(if (shortsOnly) R.string.local_shorts_empty else R.string.local_video_empty)
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
    }
}
