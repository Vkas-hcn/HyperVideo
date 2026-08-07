package com.vi.pl.videoplayerdemo.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class VideoSource(
    val label: String,
    val uri: Uri,
    val mimeType: String? = null,
)

data class VideoEpisode(
    val id: String,
    val title: String,
    val sources: List<VideoSource>,
    val episodeNumber: Int = 1,
    val seasonNumber: Int = 1,
    val summary: String = "",
    val defaultSourceIndex: Int = 0,
) {
    init {
        require(sources.isNotEmpty()) { "VideoEpisode must contain at least one source." }
    }

    fun displayTitle(): String = title

    fun mediaItem(sourceIndex: Int): MediaItem {
        val source = sources[sourceIndex.coerceIn(0, sources.lastIndex)]
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(source.uri)
            .setMimeType(source.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
    }
}

data class VideoSeason(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<VideoEpisode>,
    val latestEpisodeNumber: Int = episodes.size,
) {
    init {
        require(episodes.isNotEmpty()) { "VideoSeason must contain at least one episode." }
    }
}

enum class EpisodeStartStrategy {
    FROM_START,
    RESUME_SAVED,
}

data class VideoTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val label: String,
    val selected: Boolean,
)

data class ClarityOption(
    val label: String,
    val maxHeight: Int,
)
