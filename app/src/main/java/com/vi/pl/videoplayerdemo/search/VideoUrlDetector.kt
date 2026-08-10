package com.vi.pl.videoplayerdemo.search

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Direct video files observed by the in-app WebView, excluding stream manifests. */
object VideoUrlDetector {
    private val fileExtensions = setOf("mp4", "webm", "mov", "m4v", "3gp", "mkv")

    fun isDirectVideoUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
        if (fileExtensions.any { path.endsWith(".$it") } || path.contains("videoplayback")) return true
        val query = runCatching {
            URLDecoder.decode(uri.rawQuery.orEmpty(), StandardCharsets.UTF_8).lowercase()
        }.getOrDefault("")
        return query.contains("mime=video") || query.contains("mime_type=video") ||
            query.contains("content-type=video") || query.contains("content_type=video")
    }

    fun isDownloadableHttpUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            !uri.path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") }
    }
}
