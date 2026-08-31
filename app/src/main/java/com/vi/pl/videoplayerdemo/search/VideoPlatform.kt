package com.vi.pl.videoplayerdemo.search

import android.net.Uri
import androidx.core.net.toUri

/**
 * Platform identity for search UI.
 * Brand buttons open [homeUrl]; paste/submit URLs open the in-app browser.
 */
enum class VideoPlatform(
    val homeUrl: String,
    private val hostHints: List<String>,
) {
    TIKTOK(
        "https://www.tiktok.com",
        listOf("tiktok.com", "vm.tiktok.com", "vt.tiktok.com"),
    ),
    INSTAGRAM(
        "https://www.instagram.com/reels/",
        listOf("instagram.com", "instagr.am"),
    ),
    YOUTUBE(
        "https://www.youtube.com/shorts",
        listOf("youtube.com", "youtu.be", "m.youtube.com"),
    ),
    GOOGLE(
        "https://www.google.com",
        listOf("google.com"),
    ),
    REDDIT(
        "https://www.reddit.com",
        listOf("reddit.com"),
    ),
    FACEBOOK(
        "https://www.facebook.com/reel/",
        listOf("facebook.com", "fb.watch", "fb.com", "m.facebook.com", "web.facebook.com"),
    ),
    SNAPCHAT(
        "https://www.snapchat.com/spotlight",
        listOf("snapchat.com"),
    ),
    LIKEE(
        "https://likee.video",
        listOf("likee.video", "likee.com"),
    ),
    KWAI(
        "https://www.kwai.com",
        listOf("kwai.com", "kw.ai"),
    );

    fun matches(raw: String): Boolean {
        val host = normalizeUrl(raw)?.host?.lowercase() ?: return false
        return hostHints.any { hint -> host == hint || host.endsWith(".$hint") }
    }

    companion object {
        fun fromUrl(raw: String): VideoPlatform? = entries.firstOrNull { it.matches(raw) }
    }
}

fun normalizeUrl(raw: String): Uri? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { withScheme.toUri() }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank()) return null
    return uri
}
