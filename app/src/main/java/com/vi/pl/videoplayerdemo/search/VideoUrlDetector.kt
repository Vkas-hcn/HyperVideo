package com.vi.pl.videoplayerdemo.search

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Direct video files observed by the in-app WebView, excluding stream manifests. */
object VideoUrlDetector {
    private val fileExtensions = setOf("mp4", "webm", "mov", "m4v", "3gp", "mkv")

    fun isManifestUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        if (isBlobUrl(rawUrl) || !(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        return lower.contains(".m3u8") || lower.contains(".mpd") ||
            lower.contains("manifest") || lower.contains("master.m3u8")
    }

    fun isBlobUrl(rawUrl: String): Boolean = rawUrl.trim().startsWith("blob:", ignoreCase = true)

    fun isDirectVideoUrl(rawUrl: String): Boolean {
        if (isBlobUrl(rawUrl) || isManifestUrl(rawUrl)) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
        if (hasByteRange(uri)) return false
        if (fileExtensions.any { path.endsWith(".$it") } || path.contains("videoplayback")) return true
        if (isFacebookCdnVideo(uri) || isTikTokCdnUrl(rawUrl) || isInstagramCdnUrl(rawUrl)) return true
        val query = runCatching {
            URLDecoder.decode(uri.rawQuery.orEmpty(), "UTF-8").lowercase()
        }.getOrDefault("")
        return query.contains("mime=video") || query.contains("mime_type=video") ||
            query.contains("content-type=video") || query.contains("content_type=video")
    }

    /** Facebook reel/watch permalink with a video id — not the /reel/ feed. */
    fun isFacebookWatchUrl(rawUrl: String): Boolean {
        val uri = Uri.parse(rawUrl)
        val host = uri.host.orEmpty().lowercase()
        if (host == "fb.watch" || host.endsWith(".fb.watch")) return true
        if (!(host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.com" || host.endsWith(".fb.com"))) {
            return false
        }
        val path = uri.path.orEmpty()
        val query = uri.encodedQuery.orEmpty()
        return FACEBOOK_WATCH_PATH.containsMatchIn(path) ||
            query.contains("v=") || query.contains("story_fbid=")
    }

    /** Turn a Facebook CDN fragment into the reel page yt-dlp can resolve. */
    fun facebookDownloadUrl(rawUrl: String): String? = when {
        isFacebookWatchUrl(rawUrl) -> rawUrl
        else -> facebookReelUrlFromCdn(rawUrl)
    }

    /** Alternate Facebook permalinks for extractors that cannot resolve the original route. */
    fun facebookWatchFallbackUrls(rawUrl: String): List<String> {
        val id = Regex("""/(?:reel|reels|videos)/(\d+)""").find(rawUrl)?.groupValues?.get(1)
            ?: Uri.parse(rawUrl).getQueryParameter("v")
            ?: return emptyList()
        return listOf(
            "https://m.facebook.com/watch/?v=$id",
            "https://m.facebook.com/reel/$id",
        ).filter { it != rawUrl }
    }

    fun facebookReelUrlFromCdn(rawUrl: String): String? {
        if (!rawUrl.contains("fbcdn.net", ignoreCase = true)) return null
        val efg = EFG_PARAM.find(rawUrl)?.groupValues?.get(1) ?: return null
        val json = decodeEfg(efg) ?: return null
        val id = VIDEO_ID_IN_JSON.find(json)?.groupValues?.get(1) ?: return null
        return "https://www.facebook.com/reel/$id"
    }

    fun isFacebookCdnUrl(rawUrl: String): Boolean =
        !isManifestUrl(rawUrl) && runCatching { URI(rawUrl) }.getOrNull()?.let { uri ->
            val path = uri.path.orEmpty().lowercase()
            uri.host.orEmpty().endsWith("fbcdn.net", ignoreCase = true) &&
                !imageExtensions.any { path.endsWith(".$it") } &&
                (isFacebookCdnVideo(uri) || path.endsWith(".mp4") || path.contains("video"))
        } == true

    fun stripByteRange(rawUrl: String): String {
        val uri = Uri.parse(rawUrl)
        val names = uri.queryParameterNames
        if (names.none { it == "bytestart" || it == "byteend" || it == "bytes" }) return rawUrl
        val builder = uri.buildUpon().clearQuery()
        names.filterNot { it == "bytestart" || it == "byteend" || it == "bytes" }.forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        return builder.build().toString()
    }

    private fun decodeEfg(value: String): String? {
        val raw = URLDecoder.decode(value, "UTF-8")
            .replace('-', '+')
            .replace('_', '/')
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun hasByteRange(rawUrl: String): Boolean =
        rawUrl.contains("bytestart=") || rawUrl.contains("byteend=")

    private fun hasByteRange(uri: URI): Boolean {
        val query = uri.rawQuery.orEmpty()
        return query.contains("bytestart=") || query.contains("byteend=")
    }

    private fun isFacebookCdnVideo(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("fbcdn.net")) return false
        val path = uri.path.orEmpty().lowercase()
        return host.contains("video") || path.contains("/t42.") || path.contains("/t66.") ||
            fileExtensions.any { path.contains(".$it") }
    }

    private val FACEBOOK_WATCH_PATH = Regex("""/(?:reel|reels|videos)/\d+|/(?:share)/(?:r|v)/[^/]+""")
    private val VIDEO_ID_IN_JSON = Regex(""""video_id"\s*:\s*"?(\d+)""")
    private val EFG_PARAM = Regex("""[?&]efg=([^&]+)""")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    private val TIKTOK_VIDEO_PATH = Regex("""/@[^/]+/video/\d+|/(?:v|video)/\d+|/t/[^/]+""")
    private val TIKTOK_VIDEO_ID = Regex("""/(?:video|v)/(\d{8,})""")

    fun isTikTokWatchUrl(rawUrl: String): Boolean {
        val uri = Uri.parse(rawUrl)
        val host = uri.host.orEmpty().lowercase()
        if (host.contains("vm.tiktok") || host.contains("vt.tiktok")) return true
        if (!host.contains("tiktok.com")) return false
        return TIKTOK_VIDEO_PATH.containsMatchIn(uri.path.orEmpty())
    }

    fun tiktokDownloadUrl(rawUrl: String): String? {
        if (isTikTokWatchUrl(rawUrl)) return rawUrl
        val id = TIKTOK_VIDEO_ID.find(rawUrl)?.groupValues?.get(1) ?: return null
        return "https://www.tiktok.com/@tiktok/video/$id"
    }

    fun isTikTokCdnUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (isManifestUrl(rawUrl) || isBlobUrl(rawUrl)) return false
        return (lower.contains("tiktokcdn") || lower.contains("muscdn.com") ||
            lower.contains("tiktok.com/aweme") || lower.contains("tiktok.com/video/tos")) &&
            (lower.contains(".mp4") || lower.contains("mime_type=video") || lower.contains("/video/"))
    }

    fun isInstagramCdnUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (isManifestUrl(rawUrl) || isBlobUrl(rawUrl)) return false
        return lower.contains("cdninstagram.com") &&
            (lower.contains(".mp4") || lower.contains("video"))
    }

    /** Progressive CDN file: download as-is. Page URLs and HLS still go through yt-dlp formats. */
    fun isProgressiveFileUrl(rawUrl: String): Boolean =
        !isManifestUrl(rawUrl) && !hasByteRange(rawUrl) &&
            (isDirectVideoUrl(rawUrl) || isTikTokCdnUrl(rawUrl) || isInstagramCdnUrl(rawUrl) ||
                isFacebookCdnUrl(rawUrl))

    fun isFeedPage(rawUrl: String): Boolean = when (VideoPlatform.fromUrl(rawUrl)) {
        VideoPlatform.TIKTOK -> !isTikTokWatchUrl(rawUrl) && !isTikTokCdnUrl(rawUrl)
        VideoPlatform.FACEBOOK -> !isFacebookWatchUrl(rawUrl) && !isFacebookCdnUrl(rawUrl) && !isManifestUrl(rawUrl)
        VideoPlatform.YOUTUBE -> {
            val uri = Uri.parse(rawUrl)
            val path = uri.path.orEmpty()
            uri.getQueryParameter("v").isNullOrBlank() &&
                !path.contains("/shorts/") && !path.contains("/embed/") &&
                !path.contains("/live/") &&
                !uri.host.orEmpty().contains("youtu.be")
        }
        else -> false
    }

    fun isDownloadableHttpUrl(rawUrl: String): Boolean {
        val uri = Uri.parse(rawUrl)
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
