package com.vi.pl.videoplayerdemo.player

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlin.math.max

class VideoPlayerManager(application: Application) : AndroidViewModel(application) {
    interface Listener {
        fun onVideoTracksChanged(tracks: List<VideoTrackOption>) = Unit
        fun onPlaylistChanged(episodes: List<VideoEpisode>, currentIndex: Int) = Unit
        fun onSourceChanged(sourceIndex: Int) = Unit
        fun onPlaybackError(error: PlaybackException) = Unit
    }

    val player: ExoPlayer

    private val progressStore =
        application.getSharedPreferences("video_player_progress", Context.MODE_PRIVATE)
    private val listeners = linkedSetOf<Listener>()
    private var episodes: List<VideoEpisode> = emptyList()
    private val sourceIndexByEpisodeId = mutableMapOf<String, Int>()
    private var lastTracks: Tracks = Tracks.EMPTY
    private var restoredEpisodeIndex = 0
    private var restoredPositionMs = 0L
    private var restoredSpeed = 1f
    private var restoredSourceIndex = 0
    private var autoPlay = true

    init {
        val dataSourceFactory = DefaultDataSource.Factory(application)
        player = ExoPlayer.Builder(application)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                playWhenReady = autoPlay
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        lastTracks = tracks
                        notifyVideoTracks()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        saveCurrentProgress()
                        notifyPlaylist()
                        notifySource()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        listeners.forEach { it.onPlaybackError(error) }
                    }
                })
            }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPlaylistChanged(episodes, currentEpisodeIndex())
        listener.onSourceChanged(currentSourceIndex())
        listener.onVideoTracksChanged(getVideoTrackOptions())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun setPlaylist(
        items: List<VideoEpisode>,
        startIndex: Int = restoredEpisodeIndex,
        startStrategy: EpisodeStartStrategy = EpisodeStartStrategy.RESUME_SAVED,
    ) {
        if (items.isEmpty()) return
        episodes = items
        items.forEach { episode ->
            sourceIndexByEpisodeId.putIfAbsent(episode.id, episode.defaultSourceIndex)
        }
        val index = startIndex.coerceIn(items.indices)
        if (restoredSourceIndex in items[index].sources.indices) {
            sourceIndexByEpisodeId[items[index].id] = restoredSourceIndex
        }
        val mediaItems = items.map { episode ->
            episode.mediaItem(sourceIndexByEpisodeId[episode.id] ?: episode.defaultSourceIndex)
        }
        val position = when {
            restoredPositionMs > 0 -> restoredPositionMs
            startStrategy == EpisodeStartStrategy.RESUME_SAVED -> savedProgress(items[index].id)
            else -> 0L
        }
        player.setMediaItems(mediaItems, index, position)
        player.setPlaybackParameters(PlaybackParameters(restoredSpeed))
        player.playWhenReady = autoPlay
        player.prepare()
        notifyPlaylist()
        notifySource()
    }

    fun setSingle(uri: Uri, title: String = "Video") {
        setPlaylist(
            listOf(
                VideoEpisode(
                    id = uri.toString(),
                    title = title,
                    sources = listOf(VideoSource("Default", uri)),
                ),
            ),
        )
    }

    fun setSinglePath(path: String, title: String = File(path).name.ifBlank { "Local video" }) {
        setSingle(Uri.fromFile(File(path)), title)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun setSpeed(speed: Float) {
        restoredSpeed = speed
        player.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun switchUrlQuality(sourceIndex: Int) {
        val episode = currentEpisode() ?: return
        if (sourceIndex !in episode.sources.indices) return
        saveCurrentProgress()
        val position = max(0L, player.currentPosition)
        val episodeIndex = currentEpisodeIndex().coerceIn(episodes.indices)
        val wasPlaying = player.playWhenReady
        sourceIndexByEpisodeId[episode.id] = sourceIndex
        restoredSourceIndex = sourceIndex
        player.stop()
        val mediaItems = episodes.map { item ->
            item.mediaItem(sourceIndexByEpisodeId[item.id] ?: item.defaultSourceIndex)
        }
        player.setMediaItems(mediaItems, episodeIndex, position)
        player.prepare()
        player.playWhenReady = wasPlaying
        notifySource()
    }

    fun switchEpisode(index: Int, strategy: EpisodeStartStrategy) {
        if (index !in episodes.indices) return
        saveCurrentProgress()
        val position = if (strategy == EpisodeStartStrategy.RESUME_SAVED) {
            savedProgress(episodes[index].id)
        } else {
            0L
        }
        player.seekTo(index, position)
        player.playWhenReady = true
        notifyPlaylist()
        notifySource()
    }

    fun getVideoTrackOptions(): List<VideoTrackOption> {
        val result = mutableListOf<VideoTrackOption>()
        lastTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex, true)) continue
                val format = group.getTrackFormat(trackIndex)
                val width = format.width.takeIf { it > 0 } ?: 0
                val height = format.height.takeIf { it > 0 } ?: 0
                val bitrate = format.bitrate.takeIf { it > 0 } ?: 0
                val label = buildString {
                    if (height > 0) append("${height}p") else append("Video ${trackIndex + 1}")
                    if (bitrate > 0) append(" ${bitrate / 1000}kbps")
                }
                result += VideoTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    label = label,
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }
        return result.sortedWith(
            compareByDescending<VideoTrackOption> { it.height }.thenByDescending { it.bitrate },
        )
    }

    fun selectAdaptiveTrack(option: VideoTrackOption?) {
        val builder: TrackSelectionParameters.Builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        if (option != null) {
            val group = lastTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)))
        }
        player.trackSelectionParameters = builder.build()
    }

    /** Caps playback to [targetHeight] when a lower/equal track exists; otherwise plays native. */
    fun selectClarity(targetHeight: Int) {
        val tracks = getVideoTrackOptions()
        val nativeMax = tracks.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: targetHeight
        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)

        if (targetHeight < nativeMax) {
            val best = tracks
                .filter { it.height in 1..targetHeight }
                .maxWithOrNull(
                    compareBy<VideoTrackOption> { it.height }.thenBy { it.bitrate },
                )
            if (best != null) {
                val group = lastTracks.groups.getOrNull(best.groupIndex) ?: return
                builder.addOverride(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(best.trackIndex)),
                )
            } else if (tracks.size > 1) {
                builder.setMaxVideoSize(Integer.MAX_VALUE, targetHeight)
            }
            // ponytail: single progressive file has no lower encode; UI still switches, decode stays native
        }
        player.trackSelectionParameters = builder.build()
    }

    fun nativeVideoHeight(): Int =
        getVideoTrackOptions().maxOfOrNull { it.height }?.takeIf { it > 0 }
            ?: player.videoSize.height.takeIf { it > 0 }
            ?: 0

    fun saveToBundle(outState: Bundle) {
        saveCurrentProgress()
        outState.putInt(KEY_EPISODE_INDEX, currentEpisodeIndex())
        outState.putLong(KEY_POSITION, player.currentPosition)
        outState.putFloat(KEY_SPEED, player.playbackParameters.speed)
        outState.putInt(KEY_SOURCE_INDEX, currentSourceIndex())
        outState.putBoolean(KEY_AUTO_PLAY, player.playWhenReady)
    }

    fun restoreFromBundle(state: Bundle?) {
        if (state == null) return
        restoredEpisodeIndex = state.getInt(KEY_EPISODE_INDEX, 0)
        restoredPositionMs = state.getLong(KEY_POSITION, 0L)
        restoredSpeed = state.getFloat(KEY_SPEED, 1f)
        restoredSourceIndex = state.getInt(KEY_SOURCE_INDEX, 0)
        autoPlay = state.getBoolean(KEY_AUTO_PLAY, true)
    }

    fun currentEpisode(): VideoEpisode? = episodes.getOrNull(currentEpisodeIndex())

    fun currentEpisodeIndex(): Int =
        player.currentMediaItemIndex.takeIf { it in episodes.indices }
            ?: restoredEpisodeIndex.coerceAtLeast(0)

    fun currentSourceIndex(): Int {
        val episode = currentEpisode() ?: return restoredSourceIndex
        return sourceIndexByEpisodeId[episode.id] ?: episode.defaultSourceIndex
    }

    fun hasPlaylist(): Boolean = episodes.isNotEmpty()

    fun saveCurrentProgress() {
        val episode = currentEpisode() ?: return
        val position = player.currentPosition.takeIf { it >= 0 } ?: return
        progressStore.edit().putLong(progressKey(episode.id), position).apply()
    }

    override fun onCleared() {
        saveCurrentProgress()
        player.release()
    }

    private fun savedProgress(episodeId: String): Long =
        progressStore.getLong(progressKey(episodeId), 0L)

    private fun progressKey(episodeId: String): String = "progress_$episodeId"

    private fun notifyVideoTracks() {
        val options = getVideoTrackOptions()
        listeners.forEach { it.onVideoTracksChanged(options) }
    }

    private fun notifyPlaylist() {
        listeners.forEach { it.onPlaylistChanged(episodes, currentEpisodeIndex()) }
    }

    private fun notifySource() {
        listeners.forEach { it.onSourceChanged(currentSourceIndex()) }
    }

    companion object {
        private const val KEY_EPISODE_INDEX = "player_episode_index"
        private const val KEY_POSITION = "player_position"
        private const val KEY_SPEED = "player_speed"
        private const val KEY_SOURCE_INDEX = "player_source_index"
        private const val KEY_AUTO_PLAY = "player_auto_play"

        fun uriFromInput(input: String): Uri {
            val parsed = Uri.parse(input)
            return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(input)) else parsed
        }
    }
}
