package com.vi.pl.videoplayerdemo.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.google.android.gms.ads.AdActivity
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
    val sourceHeight: Int,
)

/** Presents a quality choice on whichever app activity is currently visible. */
object DownloadQualityCoordinator : Application.ActivityLifecycleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolver = Executors.newSingleThreadExecutor()
    private val updater = Executors.newSingleThreadExecutor()
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private var currentActivity: Activity? = null
    private var applicationContext: Context? = null
    @Volatile private var resolving: Future<*>? = null
    @Volatile private var resolutionToken: String? = null
    private var pending: ResolvedRequest? = null
    private var pendingMessage: Int? = null
    private var preparedJobId: String? = null
    private var shown: DownloadQualitySheet? = null
    private var shownFor: Activity? = null
    private var retainingPending = false

    fun resolve(context: Context, request: QualityDownloadRequest, existingJobId: String? = null) {
        Log.e(TAG, "quality resolve start title=${request.title} url=${request.url} source=${request.sourcePageUrl}")
        if (resolving?.isDone == false) {
            Toast.makeText(context.applicationContext, R.string.browser_quality_resolving, Toast.LENGTH_SHORT).show()
            return
        }
        val token = UUID.randomUUID().toString()
        resolutionToken = token
        val appContext = context.applicationContext
        applicationContext = appContext
        preparedJobId = existingJobId ?: VideoDownloadRepository.prepare(
            appContext, request.url, request.sourcePageUrl, request.title, request.coverUrl,
            request.userAgent, request.cookies, request.referer, request.fallbackUrl,
        )
        VideoDownloadRepository.saveQualityMetadata(appContext, preparedJobId.orEmpty(), request.sourceHeight, request.durationSec)
        VideoDownloadRepository.setAwaitingQuality(appContext, preparedJobId.orEmpty(), false)
        VideoDownloadRepository.markResolvingFormats(appContext, preparedJobId.orEmpty())
        resolving = resolver.submit {
            runCatching { resolveFormats(appContext, request) }
                .onSuccess { resolved -> mainHandler.post { deliver(token, resolved, null) } }
                .onFailure { error ->
                    Log.e(TAG, "quality resolve failed title=${request.title} url=${request.url}", error)
                    mainHandler.post { deliver(token, null, error) }
                }
        }
        timeoutExecutor.schedule({
            if (resolutionToken != token) return@schedule
            val task = resolving ?: return@schedule
            if (!task.isDone && task.cancel(true)) {
                mainHandler.post { deliver(token, null, QualityResolutionTimeout()) }
            }
        }, RESOLUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun resume(context: Context, id: String) {
        if (resolving?.isDone == false || !VideoDownloadRepository.isAwaitingQuality(context, id)) return
        val appContext = context.applicationContext
        resolver.execute {
            val job = com.vi.pl.videoplayerdemo.download.DownloadDatabase.get(appContext).jobs().find(id) ?: return@execute
            val dir = VideoDownloadRepository.jobDir(appContext, id)
            val (sourceHeight, durationSec) = File(dir, VideoDownloadRepository.QUALITY_META_FILE)
                .takeIf { it.isFile }?.readText()?.split(',')?.let { it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)?.toDoubleOrNull() } ?: (0 to 0.0)
            mainHandler.post {
                resolve(
                    context,
                    QualityDownloadRequest(
                        url = job.pageUrl,
                        sourcePageUrl = File(dir, "source.url").takeIf { it.isFile }?.readText().orEmpty().ifBlank { job.pageUrl },
                        fallbackUrl = File(dir, "fallback.url").takeIf { it.isFile }?.readText().orEmpty(),
                        title = job.title,
                        coverUrl = "",
                        userAgent = File(dir, VideoDownloadRepository.USER_AGENT_FILE).takeIf { it.isFile }?.readText().orEmpty(),
                        cookies = File(dir, "cookies.header").takeIf { it.isFile }?.readText().orEmpty(),
                        referer = File(dir, VideoDownloadRepository.REFERER_FILE).takeIf { it.isFile }?.readText().orEmpty(),
                        durationSec = durationSec ?: 0.0,
                        sourceHeight = sourceHeight ?: 0,
                    ),
                    existingJobId = id,
                )
            }
        }
    }

    private fun deliver(token: String, result: ResolvedRequest?, error: Throwable?) {
        if (resolutionToken != token) return
        resolving = null
        resolutionToken = null
        if (error != null) {
            preparedJobId?.let { id -> applicationContext?.let { VideoDownloadRepository.failPrepared(it, id, "Unable to resolve video quality") } }
            preparedJobId = null
            showMessage(if (error is QualityResolutionTimeout) R.string.browser_quality_timeout else R.string.browser_quality_failed)
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
            availableOptions = request.options,
            videoName = request.request.title.ifBlank { host.getString(R.string.download_video) },
            durationSec = request.durationSec,
            onDownload = { height ->
                pending?.let { selection ->
                    pending = null
                    preparedJobId?.let { id ->
                        VideoDownloadRepository.setAwaitingQuality(host.applicationContext, id, false)
                        VideoDownloadRepository.startPrepared(host.applicationContext, id, height, qualityResolved = true)
                    }
                    preparedJobId = null
                    Toast.makeText(host.applicationContext, R.string.search_download_queued, Toast.LENGTH_SHORT).show()
                }
            },
        ).also { dialog ->
            dialog.setOnDismissListener {
                if (shown === dialog) {
                    shown = null
                    shownFor = null
                    if (!retainingPending && pending != null) {
                        preparedJobId?.let { VideoDownloadRepository.setAwaitingQuality(host.applicationContext, it, true) }
                        pending = null
                    }
                }
            }
            dialog.show()
        }
    }

    private fun resolveFormats(context: Context, request: QualityDownloadRequest): ResolvedRequest {
        if (VideoUrlDetector.isProgressiveFileUrl(request.url)) {
            val height = request.sourceHeight.takeIf { it > 0 } ?: 360
            return ResolvedRequest(request, listOf(DownloadQualitySheet.Option(DownloadQualitySheet.displayHeight(height))), request.durationSec)
        }
        YoutubeDL.getInstance().init(context)
        val dir = File(context.cacheDir, "quality-resolution").apply { mkdirs() }
        val cookies = File(dir, "${UUID.randomUUID()}.cookies")
        try {
            val candidates = buildList {
                add(request.url)
                addAll(VideoUrlDetector.facebookWatchFallbackUrls(request.url))
                if (request.sourcePageUrl != request.url) add(request.sourcePageUrl)
                addAll(VideoUrlDetector.facebookWatchFallbackUrls(request.sourcePageUrl))
                request.fallbackUrl.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { fallback ->
                    add(fallback)
                    addAll(VideoUrlDetector.facebookWatchFallbackUrls(fallback))
                }
            }.filterNot(VideoUrlDetector::isFeedPage).distinct()
            for (url in candidates) {
                Log.e(TAG, "quality candidate url=$url title=${request.title}")
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
                    scheduleResolverUpdate(context)
                    return ResolvedRequest(
                        request,
                        heights.map { DownloadQualitySheet.Option(DownloadQualitySheet.displayHeight(it)) },
                        info.duration.toDouble().takeIf { it > 0 } ?: request.durationSec,
                    )
                }
            }
            scheduleResolverUpdate(context)
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

    private fun scheduleResolverUpdate(context: Context) {
        updater.execute { updateResolverIfDue(context) }
    }

    private fun updateResolverIfDue(context: Context) {
        val prefs = context.getSharedPreferences(RESOLVER_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(RESOLVER_UPDATED_AT, 0L) < RESOLVER_UPDATE_INTERVAL_MS) return
        synchronized(RESOLVER_UPDATE_LOCK) {
            if (now - prefs.getLong(RESOLVER_UPDATED_AT, 0L) >= RESOLVER_UPDATE_INTERVAL_MS) {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                    prefs.edit().putLong(RESOLVER_UPDATED_AT, now).apply()
                }.onFailure { android.util.Log.e("VideoDownload", "yt-dlp background update failed", it) }
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
        // 广告全屏 Activity 不是宿主：解析完成恰好赶上广告展示时，不能用它弹窗（无 Material 主题）
        if (activity is AdActivity) return
        currentActivity = activity
        pendingMessage?.let { message ->
            pendingMessage = null
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
        showPending()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is AdActivity) return
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

    private const val RESOLUTION_TIMEOUT_SECONDS = 60L
    private const val TAG = "VideoDownload"
    private const val RESOLVER_PREFS = "video_resolver"
    private const val RESOLVER_UPDATED_AT = "yt_dlp_updated_at"
    private const val RESOLVER_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private val RESOLVER_UPDATE_LOCK = Any()
}
