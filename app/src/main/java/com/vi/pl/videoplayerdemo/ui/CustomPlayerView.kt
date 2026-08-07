package com.vi.pl.videoplayerdemo.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.player.ClarityOption
import com.vi.pl.videoplayerdemo.player.EpisodeStartStrategy
import com.vi.pl.videoplayerdemo.player.VideoEpisode
import com.vi.pl.videoplayerdemo.player.VideoPlayerManager
import com.vi.pl.videoplayerdemo.player.VideoSeason
import com.vi.pl.videoplayerdemo.player.VideoTrackOption
import java.util.Locale
import kotlin.math.max

class CustomPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), VideoPlayerManager.Listener {
    var onFullscreenChanged: ((Boolean) -> Unit)? = null
    var onPickLocalRequested: (() -> Unit)? = null
    var onBackPressed: (() -> Boolean)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var manager: VideoPlayerManager? = null
    private var seasons: List<VideoSeason> = emptyList()
    private var tracks: List<VideoTrackOption> = emptyList()
    private var episodes: List<VideoEpisode> = emptyList()
    private var currentEpisodeIndex = 0
    private var selectedSeasonIndex = 0
    private var selectedEpisodeNumber = 1
    private var isDragging = false
    private var isFullscreen = false
    private var isLocked = false
    private var currentSpeed = 1f
    private var currentClarityHeight = 0
    private var controlsVisible = true
    private var activePanel: PanelType? = null

    private var aspectFrame: AspectRatioFrameLayout? = null
    private var textureView: TextureView? = null
    private var controlsOverlay: View? = null
    private var touchCatcher: View? = null
    private var topGradient: View? = null
    private var topBar: View? = null
    private var centerControls: View? = null
    private var bottomBar: View? = null
    private var titleText: TextView? = null
    private var currentTimeText: TextView? = null
    private var remainingTimeText: TextView? = null
    private var playPauseButton: ImageButton? = null
    private var seekBar: SeekBar? = null
    private var bottomSheetScrim: View? = null
    private var bottomSheetTitle: TextView? = null
    private var bottomSheetOptions: LinearLayout? = null
    private var episodeSelectionOverlay: View? = null
    private var seasonTabs: LinearLayout? = null
    private var seasonHeaderText: TextView? = null
    private var seasonUpdateText: TextView? = null
    private var episodeGrid: GridLayout? = null
    private var episodeSummaryText: TextView? = null
    private var sidePanelScrim: View? = null
    private var sidePanel: View? = null
    private var sidePanelTitle: TextView? = null
    private var sidePanelOptions: GridLayout? = null
    private var sidePanelEpisodeGrid: GridLayout? = null
    private var brightnessSeek: SeekBar? = null
    private var volumeSeek: SeekBar? = null
    private var lockControls: View? = null
    private var brightnessControls: View? = null
    private var volumeControls: View? = null
    private var btnLock: ImageButton? = null
    private var btnUnlock: ImageButton? = null

    private enum class PanelType {
        SPEED,
        CLARITY,
        EPISODES,
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateProgress()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspectFrame?.setAspectRatio(
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height,
                )
            }
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (!isLocked && activePanel == null && episodeSelectionOverlay?.isVisible != true) {
            setControlsVisible(false)
        }
    }

    init {
        setBackgroundColor(context.getColor(R.color.player_background))
        isFocusable = true
        inflateLayout()
    }

    fun bind(manager: VideoPlayerManager, seasons: List<VideoSeason> = emptyList()) {
        unbindInternal()
        this.manager = manager
        this.seasons = seasons.ifEmpty { manager.currentSeasons() }
        this.episodes = flattenEpisodes(this.seasons)
        manager.player.setVideoTextureView(textureView)
        manager.player.addListener(playerListener)
        manager.addListener(this)
        updatePlayPauseIcon()
        updateProgress()
        syncSeasonSelection()
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        scheduleHideControls()
    }

    fun unbind() {
        unbindInternal()
    }

    fun handleBackPress(): Boolean {
        if (episodeSelectionOverlay?.isVisible == true) {
            hideEpisodeSelection()
            return true
        }
        if (activePanel != null) {
            hideActivePanel()
            return true
        }
        if (isFullscreen) {
            toggleFullscreen()
            return true
        }
        return onBackPressed?.invoke() ?: false
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val player = manager?.player
        val wasPlaying = player?.isPlaying == true
        val position = player?.currentPosition ?: 0L
        hideActivePanel()
        hideEpisodeSelection()
        unbindSurfaceOnly()
        removeAllViews()
        inflateLayout()
        manager?.let { bind(it, seasons) }
        player?.seekTo(position)
        if (wasPlaying) player?.play()
    }

    override fun onVideoTracksChanged(tracks: List<VideoTrackOption>) {
        this.tracks = tracks
        val options = buildClarityOptions()
        if (options.isNotEmpty() && options.none { it.maxHeight == currentClarityHeight }) {
            currentClarityHeight = options.first().maxHeight
        }
    }

    override fun onPlaylistChanged(episodes: List<VideoEpisode>, currentIndex: Int) {
        this.episodes = episodes
        currentEpisodeIndex = currentIndex.coerceAtLeast(0)
        updateTitle()
        syncSeasonSelection()
        refreshEpisodeGrids()
    }

    override fun onSourceChanged(sourceIndex: Int) = Unit

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(hideControlsRunnable)
        super.onDetachedFromWindow()
    }

    private fun inflateLayout() {
        LayoutInflater.from(context).inflate(R.layout.custom_player_view, this, true)
        aspectFrame = findViewById(R.id.aspect_ratio_frame)
        textureView = findViewById(R.id.texture_view)
        controlsOverlay = findViewById(R.id.controls_overlay)
        touchCatcher = findViewById(R.id.touch_catcher)
        topGradient = findViewById(R.id.top_gradient)
        topBar = findViewById(R.id.top_bar)
        centerControls = findViewById(R.id.center_controls)
        bottomBar = findViewById(R.id.bottom_bar)
        titleText = findViewById(R.id.title_text)
        currentTimeText = findViewById(R.id.current_time_text)
        remainingTimeText = findViewById(R.id.remaining_time_text)
        playPauseButton = findViewById(R.id.btn_play_pause)
        seekBar = findViewById(R.id.seek_bar)
        bottomSheetScrim = findViewById(R.id.bottom_sheet_scrim)
        bottomSheetTitle = findViewById(R.id.bottom_sheet_title)
        bottomSheetOptions = findViewById(R.id.bottom_sheet_options)
        episodeSelectionOverlay = findViewById(R.id.episode_selection_overlay)
        seasonTabs = findViewById(R.id.season_tabs)
        seasonHeaderText = findViewById(R.id.season_header_text)
        seasonUpdateText = findViewById(R.id.season_update_text)
        episodeGrid = findViewById(R.id.episode_grid)
        episodeSummaryText = findViewById(R.id.episode_summary_text)
        sidePanelScrim = findViewById(R.id.side_panel_scrim)
        sidePanel = findViewById(R.id.side_panel)
        sidePanelTitle = findViewById(R.id.side_panel_title)
        sidePanelOptions = findViewById(R.id.side_panel_options)
        sidePanelEpisodeGrid = findViewById(R.id.side_panel_episode_grid)
        brightnessSeek = findViewById(R.id.brightness_seek)
        volumeSeek = findViewById(R.id.volume_seek)
        lockControls = findViewById(R.id.lock_controls)
        brightnessControls = findViewById(R.id.brightness_controls)
        volumeControls = findViewById(R.id.volume_controls)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlock = findViewById(R.id.btn_unlock)

        aspectFrame?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setupCommonActions()
        setupLandscapeOnlyActions()
        buildSeasonTabs()
        setControlsVisible(true)
    }

    private fun setupCommonActions() {
        findViewById<View>(R.id.btn_back)?.setOnClickListener {
            if (!handleBackPress()) {
                (context as? Activity)?.finish()
            }
        }
        findViewById<View>(R.id.btn_episode_back)?.setOnClickListener { hideEpisodeSelection() }
        findViewById<View>(R.id.btn_rewind)?.setOnClickListener {
            manager?.seekBy(-10_000L)
            scheduleHideControls()
        }
        findViewById<View>(R.id.btn_forward)?.setOnClickListener {
            manager?.seekBy(10_000L)
            scheduleHideControls()
        }
        playPauseButton?.setOnClickListener {
            manager?.togglePlayPause()
            scheduleHideControls()
        }
        findViewById<View>(R.id.btn_fullscreen)?.setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btn_speed)?.setOnClickListener { showSpeedPanel() }
        findViewById<View>(R.id.btn_clarity)?.setOnClickListener { showClarityPanel() }
        findViewById<View>(R.id.btn_episodes)?.setOnClickListener { showEpisodePanel() }

        seekBar?.max = 1000
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = manager?.player ?: return
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
                manager?.seekTo(duration * (seekBar?.progress ?: 0) / 1000L)
                isDragging = false
                scheduleHideControls()
            }
        })

        bottomSheetScrim?.setOnClickListener { hideActivePanel() }
        sidePanelScrim?.setOnClickListener { hideActivePanel() }

        touchCatcher?.setOnClickListener { toggleControlsVisibility() }
        episodeSelectionOverlay?.setOnClickListener { }
        bottomSheetScrim?.findViewById<View>(R.id.bottom_sheet_panel)?.setOnClickListener { }
        sidePanelScrim?.findViewById<View>(R.id.side_panel)?.setOnClickListener { }
    }

    private fun toggleControlsVisibility() {
        if (isLocked) return
        if (activePanel != null || episodeSelectionOverlay?.isVisible == true) return
        setControlsVisible(!controlsVisible)
        if (controlsVisible) {
            scheduleHideControls()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun setupLandscapeOnlyActions() {
        btnLock?.setOnClickListener { setLocked(true) }
        btnUnlock?.setOnClickListener { setLocked(false) }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeek?.max = maxVolume
        volumeSeek?.progress = currentVolume
        volumeSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleHideControls()
            }
        })

        val activity = context as? Activity
        val window = activity?.window
        val layoutParams = window?.attributes
        val currentBrightness = when {
            layoutParams != null && layoutParams.screenBrightness >= 0f ->
                (layoutParams.screenBrightness * 100).toInt()
            else -> 50
        }
        brightnessSeek?.progress = currentBrightness
        brightnessSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && window != null) {
                    val params = window.attributes
                    params.screenBrightness = progress / 100f
                    window.attributes = params
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleHideControls()
            }
        })
    }

    private fun showSpeedPanel() {
        activePanel = PanelType.SPEED
        handler.removeCallbacks(hideControlsRunnable)
        val title = context.getString(R.string.speed)
        if (isLandscape()) {
            resetSidePanelWidth()
            sidePanelTitle?.text = title
            sidePanelOptions?.isVisible = true
            sidePanelEpisodeGrid?.isVisible = false
            sidePanelOptions?.columnCount = 3
            sidePanelOptions?.removeAllViews()
            LayoutInflater.from(context).inflate(R.layout.speed_options_land, sidePanelOptions, true)
            bindSpeedOptions(sidePanelOptions)
            showPanelScrim(sidePanelScrim)
        } else {
            bottomSheetTitle?.text = title
            bottomSheetOptions?.removeAllViews()
            LayoutInflater.from(context).inflate(R.layout.speed_options_portrait, bottomSheetOptions, true)
            bindSpeedOptions(bottomSheetOptions)
            showPanelScrim(bottomSheetScrim)
        }
    }

    private fun showPanelScrim(scrim: View?) {
        scrim ?: return
        scrim.isVisible = true
        scrim.bringToFront()
        scrim.elevation = dp(16).toFloat()
    }

    private fun bindSpeedOptions(container: ViewGroup?) {
        container ?: return
        for (i in 0 until container.childCount) {
            val option = container.getChildAt(i) as? TextView ?: continue
            val speed = option.tag?.toString()?.toFloatOrNull() ?: continue
            option.isSelected = speed == currentSpeed
            option.setOnClickListener {
                currentSpeed = speed
                manager?.setSpeed(speed)
                hideActivePanel()
            }
        }
    }

    private fun showClarityPanel() {
        activePanel = PanelType.CLARITY
        handler.removeCallbacks(hideControlsRunnable)
        val title = context.getString(R.string.clarity)
        val options = buildClarityOptions()
        if (isLandscape()) {
            fitSidePanelWidthForLabels(options.map { it.label }, columns = 3)
            sidePanelTitle?.text = title
            sidePanelOptions?.isVisible = true
            sidePanelEpisodeGrid?.isVisible = false
            sidePanelOptions?.columnCount = 3
            sidePanelOptions?.removeAllViews()
            options.forEach { option ->
                sidePanelOptions?.addView(
                    inflateOptionItem(
                        R.layout.option_item_land,
                        sidePanelOptions,
                        option.label,
                        option.maxHeight == currentClarityHeight,
                    ) {
                        applyClarityOption(option)
                    },
                )
            }
            showPanelScrim(sidePanelScrim)
        } else {
            bottomSheetTitle?.text = title
            bottomSheetOptions?.removeAllViews()
            options.forEach { option ->
                bottomSheetOptions?.addView(
                    inflateOptionItem(
                        R.layout.option_item_portrait,
                        bottomSheetOptions,
                        option.label,
                        option.maxHeight == currentClarityHeight,
                    ) {
                        applyClarityOption(option)
                    },
                )
            }
            showPanelScrim(bottomSheetScrim)
        }
    }

    private fun applyClarityOption(option: ClarityOption) {
        currentClarityHeight = option.maxHeight
        manager?.selectClarity(option.maxHeight)
        hideActivePanel()
    }

    private fun inflateOptionItem(
        layoutRes: Int,
        parent: ViewGroup?,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val view = LayoutInflater.from(context).inflate(layoutRes, parent, false) as TextView
        view.text = label
        view.isSelected = selected
        view.setOnClickListener { onClick() }
        return view
    }

    private fun showEpisodePanel() {
        if (isLandscape()) {
            showLandscapeEpisodePanel()
        } else {
            showEpisodeSelection()
        }
    }

    private fun showLandscapeEpisodePanel() {
        activePanel = PanelType.EPISODES
        handler.removeCallbacks(hideControlsRunnable)
        resetSidePanelWidth()
        sidePanelTitle?.text = context.getString(R.string.episodes)
        sidePanelOptions?.isVisible = false
        sidePanelEpisodeGrid?.isVisible = true
        refreshLandscapeEpisodeGrid()
        showPanelScrim(sidePanelScrim)
    }

    private fun resetSidePanelWidth() {
        val panel = sidePanel ?: return
        val params = panel.layoutParams ?: return
        params.width = resources.getDimensionPixelSize(R.dimen.player_side_panel_width)
        panel.layoutParams = params
    }

    /** Widen side panel when [columns] labels cannot fit in one row at the default width. */
    private fun fitSidePanelWidthForLabels(labels: List<String>, columns: Int) {
        val panel = sidePanel ?: return
        val baseWidth = resources.getDimensionPixelSize(R.dimen.player_side_panel_width)
        if (labels.isEmpty()) {
            resetSidePanelWidth()
            return
        }
        val paint = TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics,
            )
        }
        val maxLabel = labels.maxOf { paint.measureText(it) }
        val itemPadding = dp(16) // horizontal padding inside item
        val itemMargin = dp(8) // 4dp * 2
        val panelPadding = dp(32) // ScrollView paddingHorizontal 16*2
        val needed = (maxLabel + itemPadding + itemMargin) * columns + panelPadding
        val maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        val params = panel.layoutParams ?: return
        params.width = needed.toInt().coerceIn(baseWidth, maxWidth)
        panel.layoutParams = params
    }

    private fun showEpisodeSelection() {
        episodeSelectionOverlay?.isVisible = true
        handler.removeCallbacks(hideControlsRunnable)
        refreshPortraitEpisodeGrid()
    }

    private fun hideEpisodeSelection() {
        episodeSelectionOverlay?.isVisible = false
        scheduleHideControls()
    }

    private fun hideActivePanel() {
        activePanel = null
        bottomSheetScrim?.isVisible = false
        sidePanelScrim?.isVisible = false
        scheduleHideControls()
    }

    private fun buildClarityOptions(): List<ClarityOption> {
        val maxHeight = manager?.nativeVideoHeight()?.takeIf { it > 0 }
            ?: tracks.maxOfOrNull { it.height }?.takeIf { it > 0 }
            ?: return emptyList()
        val ladder = listOf(
            2160 to R.string.clarity_4k,
            1080 to R.string.clarity_1080,
            720 to R.string.clarity_720,
            480 to R.string.clarity_480,
        )
        val capped = ladder.filter { (height, _) -> height <= maxHeight }.take(3)
        if (capped.isNotEmpty()) {
            return capped.map { (height, labelRes) ->
                ClarityOption(label = context.getString(labelRes), maxHeight = height)
            }
        }
        // Video below 480p: one option, label of nearest rung, cap at real height.
        val (_, labelRes) = ladder.minBy { (height, _) -> kotlin.math.abs(height - maxHeight) }
        return listOf(ClarityOption(label = context.getString(labelRes), maxHeight = maxHeight))
    }

    private fun buildSeasonTabs() {
        val container = seasonTabs ?: return
        container.removeAllViews()
        seasons.forEachIndexed { index, season ->
            val tab = TextView(context).apply {
                text = context.getString(R.string.season_format, season.seasonNumber)
                setTextColor(context.getColor(R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_season_tab)
                isSelected = index == selectedSeasonIndex
                val horizontal = dp(20)
                val vertical = dp(8)
                setPadding(horizontal, vertical, horizontal, vertical)
                setOnClickListener {
                    selectedSeasonIndex = index
                    updateSeasonTabSelection()
                    refreshPortraitEpisodeGrid()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) marginStart = dp(8)
            }
            container.addView(tab, params)
        }
    }

    private fun updateSeasonTabSelection() {
        val container = seasonTabs ?: return
        for (i in 0 until container.childCount) {
            container.getChildAt(i).isSelected = i == selectedSeasonIndex
        }
        val season = seasons.getOrNull(selectedSeasonIndex) ?: return
        seasonHeaderText?.text = season.title
        seasonUpdateText?.text = context.getString(R.string.updated_to_ep, season.latestEpisodeNumber)
    }

    private fun refreshPortraitEpisodeGrid() {
        val grid = episodeGrid ?: return
        val season = seasons.getOrNull(selectedSeasonIndex) ?: return
        grid.removeAllViews()
        updateSeasonTabSelection()
        val currentEpisode = episodes.getOrNull(currentEpisodeIndex)
        season.episodes.forEach { episode ->
            val globalIndex = episodes.indexOfFirst { it.id == episode.id }
            val item = createEpisodeItemView(
                episodeNumber = episode.episodeNumber,
                selected = episode.id == currentEpisode?.id,
                landStyle = false,
            ) {
                if (globalIndex >= 0) {
                    manager?.switchEpisode(globalIndex, EpisodeStartStrategy.RESUME_SAVED)
                    selectedEpisodeNumber = episode.episodeNumber
                    updateEpisodeSummary(episode)
                    refreshPortraitEpisodeGrid()
                    hideEpisodeSelection()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(56)
                height = dp(56)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(item, params)
        }
        val selected = season.episodes.firstOrNull { it.episodeNumber == selectedEpisodeNumber }
            ?: season.episodes.firstOrNull()
        selected?.let { updateEpisodeSummary(it) }
    }

    private fun refreshLandscapeEpisodeGrid() {
        val grid = sidePanelEpisodeGrid ?: return
        val season = seasons.getOrNull(selectedSeasonIndex) ?: seasons.firstOrNull() ?: return
        grid.removeAllViews()
        val currentEpisode = episodes.getOrNull(currentEpisodeIndex)
        season.episodes.take(16).forEach { episode ->
            val globalIndex = episodes.indexOfFirst { it.id == episode.id }
            val item = createEpisodeItemView(
                episodeNumber = episode.episodeNumber,
                selected = episode.id == currentEpisode?.id,
                landStyle = true,
            ) {
                if (globalIndex >= 0) {
                    manager?.switchEpisode(globalIndex, EpisodeStartStrategy.RESUME_SAVED)
                    hideActivePanel()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(52)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(item, params)
        }
    }

    private fun refreshEpisodeGrids() {
        if (episodeSelectionOverlay?.isVisible == true) {
            refreshPortraitEpisodeGrid()
        }
        if (activePanel == PanelType.EPISODES) {
            refreshLandscapeEpisodeGrid()
        }
    }

    private fun updateEpisodeSummary(episode: VideoEpisode) {
        selectedEpisodeNumber = episode.episodeNumber
        episodeSummaryText?.text = context.getString(
            R.string.episode_title_format,
            episode.episodeNumber,
            episode.summary,
        )
    }

    private fun syncSeasonSelection() {
        val episode = episodes.getOrNull(currentEpisodeIndex) ?: return
        selectedSeasonIndex = seasons.indexOfFirst { season ->
            season.episodes.any { it.id == episode.id }
        }.coerceAtLeast(0)
        selectedEpisodeNumber = episode.episodeNumber
        updateSeasonTabSelection()
    }

    private fun createEpisodeItemView(
        episodeNumber: Int,
        selected: Boolean,
        landStyle: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = episodeNumber.toString()
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            isSelected = selected
            setBackgroundResource(
                if (landStyle) R.drawable.bg_episode_item_land else R.drawable.bg_episode_item,
            )
            setTextColor(
                if (selected && landStyle) context.getColor(R.color.black)
                else context.getColor(R.color.white),
            )
            setOnClickListener { onClick() }
        }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        btnLock?.isVisible = !locked
        btnUnlock?.isVisible = locked
        if (locked) {
            topGradient?.isVisible = false
            topBar?.isVisible = false
            centerControls?.isVisible = false
            bottomBar?.isVisible = false
            brightnessControls?.isVisible = false
            volumeControls?.isVisible = false
            lockControls?.isVisible = true
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            setControlsVisible(true)
            scheduleHideControls()
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (isLocked && visible) return
        controlsVisible = visible
        topGradient?.isVisible = visible
        topBar?.isVisible = visible
        centerControls?.isVisible = visible
        bottomBar?.isVisible = visible
        brightnessControls?.isVisible = visible && isLandscape()
        volumeControls?.isVisible = visible && isLandscape()
        lockControls?.isVisible = visible && isLandscape()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000L)
    }

    private fun toggleFullscreen() {
        val activity = context as? Activity ?: return
        isFullscreen = !isFullscreen
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onFullscreenChanged?.invoke(isFullscreen)
        applySystemUiVisibility(activity, isFullscreen)
        scheduleHideControls()
    }

    private fun applySystemUiVisibility(activity: Activity, fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = activity.window.insetsController
            if (fullscreen) {
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updatePlayPauseIcon() {
        val playing = manager?.player?.isPlaying == true
        playPauseButton?.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
    }

    private fun updateTitle() {
        val episode = episodes.getOrNull(currentEpisodeIndex) ?: return
        titleText?.text = episode.title
    }

    private fun updateProgress() {
        val player = manager?.player ?: return
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = max(0L, player.currentPosition)
        if (!isDragging) {
            seekBar?.progress = if (duration > 0) (position * 1000L / duration).toInt() else 0
            seekBar?.secondaryProgress =
                if (duration > 0) (player.bufferedPosition * 1000L / duration).toInt() else 0
        }
        currentTimeText?.text = formatTime(position)
        remainingTimeText?.text = if (duration > 0) {
            "-${formatTime(duration - position)}"
        } else {
            "-00:00"
        }
        updatePlayPauseIcon()
    }

    private fun unbindInternal() {
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(hideControlsRunnable)
        manager?.player?.removeListener(playerListener)
        unbindSurfaceOnly()
        manager?.removeListener(this)
        manager = null
    }

    private fun unbindSurfaceOnly() {
        manager?.player?.clearVideoTextureView(textureView)
    }

    private fun flattenEpisodes(seasons: List<VideoSeason>): List<VideoEpisode> =
        seasons.flatMap { it.episodes }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun formatTime(ms: Long): String {
        val totalSeconds = max(0L, ms / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

private fun VideoPlayerManager.currentSeasons(): List<VideoSeason> = emptyList()
