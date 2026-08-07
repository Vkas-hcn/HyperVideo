package com.vi.pl.videoplayerdemo

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vi.pl.videoplayerdemo.player.VideoEpisode
import com.vi.pl.videoplayerdemo.player.VideoPlayerManager
import com.vi.pl.videoplayerdemo.player.VideoSeason
import com.vi.pl.videoplayerdemo.player.VideoSource
import com.vi.pl.videoplayerdemo.ui.CustomPlayerView

class PlayerActivity : AppCompatActivity() {
    private val playerManager: VideoPlayerManager by viewModels()
    private lateinit var customPlayerView: CustomPlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerManager.restoreFromBundle(savedInstanceState)
        setContentView(R.layout.activity_player)

        customPlayerView = findViewById(R.id.custom_player_view)
        customPlayerView.onFullscreenChanged = { fullscreen ->
            val params = customPlayerView.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            customPlayerView.layoutParams = params
            requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        customPlayerView.onBackPressed = {
            finish()
            true
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.player_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!customPlayerView.handleBackPress()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        val seasons = buildSeasonsFromIntent()
        customPlayerView.bind(playerManager, seasons)
        if (savedInstanceState == null || !playerManager.hasPlaylist()) {
            loadMedia(seasons)
        }
    }

    override fun onStop() {
        super.onStop()
        playerManager.saveCurrentProgress()
    }

    override fun onDestroy() {
        customPlayerView.unbind()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        playerManager.saveToBundle(outState)
        super.onSaveInstanceState(outState)
    }

    private fun buildSeasonsFromIntent(): List<VideoSeason> {
        val uris = intent.getStringArrayListExtra(EXTRA_VIDEO_URIS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_VIDEO_TITLES).orEmpty()
        if (uris.isEmpty()) return emptyList()

        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME).orEmpty()
            .ifBlank { getString(R.string.local_video_title) }
        val episodes = uris.mapIndexed { index, uriString ->
            VideoEpisode(
                id = uriString,
                title = titles.getOrNull(index).orEmpty().ifBlank { "Video ${index + 1}" },
                sources = listOf(VideoSource("Default", Uri.parse(uriString))),
                episodeNumber = index + 1,
                seasonNumber = 1,
                summary = categoryName,
            )
        }
        return listOf(
            VideoSeason(
                seasonNumber = 1,
                title = categoryName,
                episodes = episodes,
                latestEpisodeNumber = episodes.size,
            ),
        )
    }

    private fun loadMedia(seasons: List<VideoSeason>) {
        val externalUri = intent?.data
        val input = intent?.getStringExtra(EXTRA_MEDIA_URI)
        when {
            seasons.isNotEmpty() -> {
                val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    .coerceIn(0, seasons.first().episodes.lastIndex)
                playerManager.setPlaylist(seasons.first().episodes, startIndex = startIndex)
            }
            externalUri != null -> playerManager.setSingle(externalUri, "External video")
            !input.isNullOrBlank() -> playerManager.setSingle(
                VideoPlayerManager.uriFromInput(input),
                "Input video",
            )
            else -> finish()
        }
    }

    companion object {
        const val EXTRA_VIDEO_URIS = "extra_video_uris"
        const val EXTRA_VIDEO_TITLES = "extra_video_titles"
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
        const val EXTRA_MEDIA_URI = "media_uri"

        fun createIntent(
            context: Context,
            categoryName: String,
            videoUris: ArrayList<String>,
            videoTitles: ArrayList<String>,
            startIndex: Int,
        ): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_VIDEO_URIS, videoUris)
                putStringArrayListExtra(EXTRA_VIDEO_TITLES, videoTitles)
                putExtra(EXTRA_START_INDEX, startIndex)
                putExtra(EXTRA_CATEGORY_NAME, categoryName)
            }
    }
}
