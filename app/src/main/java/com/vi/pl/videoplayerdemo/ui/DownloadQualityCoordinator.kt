package com.vi.pl.videoplayerdemo.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.search.VideoUrlDetector
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class QualityDownloadRequest(
    val url: String,
    val sourcePageUrl: String,
    val fallbackUrl: String,
    val title: String,
    val coverUrl: String,
    val userAgent: String,
    val cookies: String,
    val referer: String,
    val durationSec: Double,
)

/** Presents a quality choice on whichever app activity is currently visible. */
object DownloadQualityCoordinator : Application.ActivityLifecycleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolver = Executors.newSingleThreadExecutor()
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private var currentActivity: Activity? = null
    @Volatile private var resolving: Future<*>? = null
    @Volatile private var resolutionToken: String? = null
    private var pending: ResolvedRequest? = null
    private var pendingMessage: Int? = null
    private var shown: DownloadQualitySheet? = null
    private var shownFor: Activity? = null
    private var retainingPending = false

    fun resolve(context: Context, request: QualityDownloadRequest) {
        if (resolving?.isDone == false) {
            Toast.makeText(context.applicationContext, R.string.browser_quality_resolving, Toast.LENGTH_SHORT).show()
            return
        }
        val token = UUID.randomUUID().toString()
        resolutionToken = token
        val appContext = context.applicationContext
        resolving = resolver.submit {
            runCatching { resolveFormats(appContext, request) }
                .onSuccess { resolved -> mainHandler.post { deliver(token, resolved, null) } }
                .onFailure { error -> mainHandler.post { deliver(token, null, error) } }
        }
        timeoutExecutor.schedule({
            if (resolutionToken != token) return@schedule
            val task = resolving ?: return@schedule
            if (!task.isDone && task.cancel(true)) {
                mainHandler.post { deliver(token, null, QualityResolutionTimeout()) }
            }
        }, RESOLUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun deliver(token: String, result: ResolvedRequest?, error: Throwable?) {
        if (resolutionToken != token) return
        resolving = null
        resolutionToken = null
        if (error != null) {
            showMessage(R.string.browser_quality_failed)
            return
        }
        pending = result ?: return showMessage(R.string.browser_quality_failed)
        showPending()
    }

    private fun showPending() {
        val host = currentActivity ?: return
        val request = pending ?: return
        if (host.isFinishing || host.isDestroyed || shown != null) return
        shownFor = host
        shown = DownloadQualitySheet(
            host,
            options = request.options,
            durationSec = request.durationSec,
            onDownload = { height ->
                pending?.let { selection ->
                    pending = null
                    VideoDownloadRepository.enqueue(
                        host.applicationContext,
                        selection.request.url,
                        sourcePageUrl = selection.request.sourcePageUrl,
                        title = selection.request.title,
                        coverUrl = selection.request.coverUrl,
                        userAgent = selection.request.userAgent,
                        cookies = selection.request.cookies,
                        referer = selection.request.referer,
                        fallbackUrl = selection.request.fallbackUrl,
                        maxHeight = height,
                    )
                    Toast.makeText(host.applicationContext, R.string.search_download_queued, Toast.LENGTH_SHORT).show()
                }
            },
        ).also { dialog ->
            dialog.setOnDismissListener {
                if (shown === dialog) {
                    shown = null
                    shownFor = null
                    if (!retainingPending) pending = null
                }
            }
            dialog.show()
        }
    }

    private fun resolveFormats(context: Context, request: QualityDownloadRequest): ResolvedRequest {
        YoutubeDL.getInstance().init(context)
        maybeUpdateResolver(context)
        val dir = File(context.cacheDir, "quality-resolution").apply { mkdirs() }
        val cookies = File(dir, "${UUID.randomUUID()}.cookies")
        try {
            val candidates = buildList {
                add(request.url)
                if (request.sourcePageUrl != request.url) add(request.sourcePageUrl)
                addAll(request.fallbackUrl.lineSequence().map(String::trim).filter(String::isNotBlank))
            }.filterNot(VideoUrlDetector::isFeedPage).distinct()
            for (url in candidates) {
                val info = runCatching {
                    YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        request.userAgent.takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                        request.referer.takeIf { it.isNotBlank() }?.let { addOption("--referer", it) }
                        if (request.cookies.isNotBlank()) {
                            cookies.writeText(netscapeCookies(request.cookies, url))
                            addOption("--cookies", cookies.absolutePath)
                        }
                    })
                }.getOrNull() ?: continue
                val heights = info.formats.orEmpty().asSequence()
                    .filter { it.height > 0 && !it.vcodec.equals("none", ignoreCase = true) }
                    .map { minOf(it.width.takeIf { width -> width > 0 } ?: it.height, it.height) }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                    .toList()
                    .ifEmpty {
                        listOf(minOf(info.width.takeIf { it > 0 } ?: info.height, info.height)).filter { it > 0 }
                    }
                if (heights.isNotEmpty()) {
                    return ResolvedRequest(
                        request,
                        heights.map(DownloadQualitySheet::Option),
                        info.duration.toDouble().takeIf { it > 0 } ?: request.durationSec,
                    )
                }
            }
            error("No video formats found")
        } finally {
            cookies.delete()
        }
    }

    private fun netscapeCookies(header: String, url: String): String {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""
        val domain = when {
            host.contains("facebook") || host.contains("fbcdn") || host.contains("fb.watch") -> ".facebook.com"
            host.contains("instagram") -> ".instagram.com"
            host.contains("tiktok") || host.contains("muscdn") -> ".tiktok.com"
            else -> ".$host"
        }
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            header.split(';').map(String::trim).filter { it.contains('=') }.forEach { pair ->
                appendLine("$domain\tTRUE\t/\tTRUE\t0\t${pair.substringBefore('=')}\t${pair.substringAfter('=')}")
            }
        }
    }

    private fun maybeUpdateResolver(context: Context) {
        val prefs = context.getSharedPreferences(RESOLVER_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(RESOLVER_UPDATED_AT, 0L) < RESOLVER_UPDATE_INTERVAL_MS) return
        synchronized(RESOLVER_UPDATE_LOCK) {
            if (now - prefs.getLong(RESOLVER_UPDATED_AT, 0L) >= RESOLVER_UPDATE_INTERVAL_MS) {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                    prefs.edit().putLong(RESOLVER_UPDATED_AT, now).apply()
                }
            }
        }
    }

    private fun showMessage(message: Int) {
        val host = currentActivity
        if (host == null) {
            pendingMessage = message
        } else {
            Toast.makeText(host, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        pendingMessage?.let { message ->
            pendingMessage = null
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
        showPending()
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
        if (shownFor === activity) {
            retainingPending = true
            shown?.dismiss()
            retainingPending = false
        }
    }

    override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private data class ResolvedRequest(
        val request: QualityDownloadRequest,
        val options: List<DownloadQualitySheet.Option>,
        val durationSec: Double,
    )

    private class QualityResolutionTimeout : RuntimeException()

    private const val RESOLUTION_TIMEOUT_SECONDS = 20L
    private const val RESOLVER_PREFS = "video_resolver"
    private const val RESOLVER_UPDATED_AT = "yt_dlp_updated_at"
    private const val RESOLVER_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private val RESOLVER_UPDATE_LOCK = Any()
}
