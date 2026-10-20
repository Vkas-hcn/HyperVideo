package com.vi.pl.videoplayerdemo.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import com.vi.pl.videoplayerdemo.search.VideoUrlDetector
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class VideoDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val dao = DownloadDatabase.get(context).jobs()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 只有 Manifest 里给 WorkManager 的前台服务声明了 dataSync 类型，才能调用
     * setForegroundAsync；否则 startForeground 会抛 IllegalArgumentException 直接崩进程。
     */
    private val foregroundSupported: Boolean by lazy { dataSyncForegroundDeclared() }
    private var resolverInitialized = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var notifyTitle = ""
    @Volatile private var notifyProgress = 0
    @Volatile private var notifyIndeterminate = true
    @Volatile private var lastNotifyAt = 0L
    @Volatile private var lastNotifiedProgress = -1

    override fun doWork(): Result {
        val id = inputData.getString(JOB_ID) ?: return Result.failure()
        val job = dao.find(id) ?: return Result.failure()
        abortedIds.remove(id)
        if (job.state == DownloadState.CANCELLED) return Result.success()
        job.failureReason = null
        dao.update(job)
        Log.e(TAG, "download start title=${job.title} url=${job.pageUrl}")
        val tempDir = VideoDownloadRepository.jobDir(applicationContext, id).apply { mkdirs() }
        return try {
            if (foregroundSupported) {
                runCatching { setForegroundAsync(getForegroundInfo()).get() }
                    .onFailure { Log.w(TAG, "foreground start failed, continue without it", it) }
            } else {
                Log.w(TAG, "foreground service type not declared, download without foreground")
            }
            val sourcePage = File(tempDir, SOURCE_URL).takeIf { it.isFile }?.readText()?.trim()
                ?.takeIf { it.isNotBlank() }
            val sourceUrl = VideoUrlDetector.facebookDownloadUrl(sourcePage.orEmpty())
                ?: VideoUrlDetector.tiktokDownloadUrl(sourcePage.orEmpty())
                ?: job.pageUrl
            val fallbacks = File(tempDir, "fallback.url").takeIf { it.isFile }?.readLines().orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { if (VideoUrlDetector.isManifestUrl(it)) it else VideoUrlDetector.stripByteRange(it) }
            val attempts = buildList {
                if (!VideoUrlDetector.isFeedPage(job.pageUrl)) add(job.pageUrl)
                if (sourceUrl != job.pageUrl && !VideoUrlDetector.isFeedPage(sourceUrl)) add(sourceUrl)
                addAll(VideoUrlDetector.facebookWatchFallbackUrls(sourceUrl))
                sourcePage?.takeIf { !VideoUrlDetector.isFeedPage(it) }?.let(::add)
                addAll(fallbacks.filterNot(VideoUrlDetector::isFeedPage))
            }.distinct()
            var lastError: Throwable? = null
            var metadataProbed = inputData.getBoolean(QUALITY_RESOLVED, false)
            for (attempt in attempts) {
                if (isInterrupted(id)) throw InterruptedIOException("Download stopped")
                Log.e(
                    TAG,
                    "download attempt url=${diagnosticUrl(attempt)} progressive=${VideoUrlDetector.isProgressiveFileUrl(attempt)} " +
                        "manifest=${VideoUrlDetector.isManifestUrl(attempt)} facebookCdn=${VideoUrlDetector.isFacebookCdnUrl(attempt)} " +
                        "byteRange=${VideoUrlDetector.hasByteRange(attempt)} platform=${job.platform}",
                )
                lastError = runCatching {
                    if (VideoUrlDetector.isProgressiveFileUrl(attempt)) {
                        update(job, DownloadState.DOWNLOADING, 0)
                        executeDirectDownload(attempt, tempDir, job)
                    } else {
                        ensureResolver()
                        if (!metadataProbed) {
                            update(job, DownloadState.RESOLVING_FORMATS, 0)
                            runCatching { probeMeta(attempt, tempDir, job) }
                            metadataProbed = true
                        }
                        update(job, DownloadState.DOWNLOADING, 0)
                        executeDownload(attempt, id, tempDir, job)
                    }
                    val produced = pickOutput(tempDir) ?: error("No video file was produced")
                    logMediaDiagnostics(attempt, produced, tempDir)
                    require(isPlayable(produced)) { "Downloaded file has no decodable video track" }
                    writeSize(tempDir, produced)
                }.exceptionOrNull()
                if (lastError == null) break
                Log.e(TAG, "download attempt failed: ${diagnosticUrl(attempt)}", lastError)
                logDirectoryDiagnostics(tempDir)
                // Keep partial files; they are the persisted resume checkpoint.
            }
            if (attempts.isEmpty()) error("No downloadable video found yet")
            if (lastError != null) throw lastError
            if (isInterrupted(id)) throw InterruptedIOException("Download stopped")
            val output = pickOutput(tempDir) ?: error("No video file was produced")
            update(job, DownloadState.VALIDATING, 99)
            require(isPlayable(output)) { "Downloaded file has no decodable video track" }
            update(job, DownloadState.MERGING, 99)
            val publishTitle = job.title.takeUnless { it.isBlank() || it == "Downloaded video" }
                ?: output.nameWithoutExtension
            val uri = publish(output, publishTitle)
            job.mediaUri = uri.toString()
            if (job.title.isBlank() || job.title == "Downloaded video") {
                job.title = publishTitle.ifBlank { job.title }
            }
            job.completedAt = System.currentTimeMillis()
            update(job, DownloadState.COMPLETED, 100)
            // The published MediaStore copy is durable; checkpoints are no longer needed.
            tempDir.deleteRecursively()
            Result.success()
        } catch (error: Throwable) {
            if (isExplicitlyCancelled(id)) {
                markCancelled(id, job)
                Result.success()
            } else if (isStopped || error.javaClass.simpleName == "CanceledException") {
                markPaused(id, job, "Download paused. Tap Continue to resume.")
                Result.success()
            } else if (isRetryableNetworkError(error) && runAttemptCount < MAX_NETWORK_RETRIES) {
                job.state = DownloadState.READY
                job.failureReason = "Connection interrupted. Retrying…"
                job.updatedAt = System.currentTimeMillis()
                dao.update(job)
                Log.w(TAG, "network interruption; resuming from ${job.downloadedBytes} bytes", error)
                Result.retry()
            } else if (error is InterruptedIOException) {
                markPaused(id, job, "Download paused. Tap Continue to resume.")
                Result.success()
            } else {
                Log.e(TAG, "download failed for ${job.pageUrl}", error)
                if (job.state == DownloadState.RETRY_REQUIRED) return Result.failure()
                job.failureReason = failureMessage(error)
                update(job, DownloadState.FAILED, job.progress)
                Result.failure()
            }
        }
    }

    override fun onStopped() {
        inputData.getString(JOB_ID)?.let { id ->
            runCatching { activeConnection?.disconnect() }
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            dao.find(id)?.let { job ->
                if (isExplicitlyCancelled(id)) markCancelled(id, job)
                else markPaused(id, job, "Download paused. Tap Continue to resume.")
            }
        }
        super.onStopped()
    }

    override fun getForegroundInfo(): ForegroundInfo {
        NotificationManagerCompat.from(applicationContext).cancel(LEGACY_WORKER_NOTIFICATION_ID)
        val notification = buildProgressNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** 下载过程中的前台服务通知（仅下载期间存在，不再是常驻条）。 */
    private fun buildProgressNotification(): Notification {
        ensureNotificationChannel()
        val title = notifyTitle.ifBlank { applicationContext.getString(R.string.download_notification_title) }
        val status = applicationContext.getString(R.string.download_notification_progress, notifyProgress)
        val click = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_btn)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(click)
            .setProgress(100, notifyProgress, notifyIndeterminate)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    private fun dataSyncForegroundDeclared(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val info = runCatching {
            applicationContext.packageManager.getServiceInfo(
                ComponentName(applicationContext, "androidx.work.impl.foreground.SystemForegroundService"),
                0,
            )
        }.getOrNull() ?: return false
        return info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
    }

    private fun ensureResolver() {
        if (!resolverInitialized) {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            resolverInitialized = true
        }
        val prefs = applicationContext.getSharedPreferences(RESOLVER_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(RESOLVER_UPDATED_AT, 0L) < RESOLVER_UPDATE_INTERVAL_MS) return
        synchronized(RESOLVER_UPDATE_LOCK) {
            val latest = prefs.getLong(RESOLVER_UPDATED_AT, 0L)
            if (now - latest < RESOLVER_UPDATE_INTERVAL_MS) return@synchronized
            // Bundled yt-dlp in 0.18.1 is too old for current YouTube. Update at most once per day.
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                prefs.edit().putLong(RESOLVER_UPDATED_AT, now).apply()
            }.onFailure { Log.w(TAG, "yt-dlp update skipped", it) }
        }
    }

    private fun executeDirectDownload(url: String, tempDir: File, job: DownloadJob) {
        val part = File(tempDir, DIRECT_FILE + ".part")
        val output = File(tempDir, DIRECT_FILE)
        job.resumeFilePath = part.absolutePath
        val offset = minOf(part.length().coerceAtLeast(0L), job.downloadedBytes.takeIf { it > 0L } ?: part.length())
        if (part.length() != offset) java.io.RandomAccessFile(part, "rw").use { it.setLength(offset) }
        val preflight = preflight(url, tempDir)
        val identity = mediaIdentity(preflight.finalUrl)
        if (offset > 0L && job.resourceIdentity.isNotBlank() && job.resourceIdentity != identity) retryRequired(job, "Video resource changed. Restart download to continue.")
        if (offset > 0L && job.etag.isNotBlank() && preflight.etag.isNotBlank() && job.etag != preflight.etag) retryRequired(job, "Video resource changed. Restart download to continue.")
        val canResume = offset > 0L && preflight.rangeSupported
        if (offset > 0L && !canResume) { part.delete(); job.downloadedBytes = 0L }
        val requestOffset = if (canResume) offset else 0L
        val connection = directConnection(preflight.finalUrl, tempDir).apply {
            if (requestOffset > 0L) {
                setRequestProperty("Range", "bytes=$requestOffset-")
                job.etag.ifBlank { job.lastModified }.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Range", it) }
            }
        }
        activeConnection = connection
        try {
            val code = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            if (requestOffset > 0L) require(code == HttpURLConnection.HTTP_PARTIAL && contentRangeStart(contentRange) == requestOffset) {
                "Server did not honor Range bytes=$requestOffset- (HTTP $code, $contentRange)"
            }
            Log.e(
                TAG,
                "direct response job=${job.id} url=${diagnosticUrl(url)} final=${diagnosticUrl(connection.url.toString())} offset=$requestOffset " +
                    "code=$code type=${connection.contentType} length=${connection.contentLengthLong} " +
                    "range=$contentRange etag=${connection.getHeaderField("ETag").orEmpty()} encoding=${connection.contentEncoding}",
            )
            require(code in 200..299) { "Direct download HTTP $code" }
            val total = totalBytes(connection, requestOffset, preflight.totalBytes)
            job.sourceType = "PROGRESSIVE"
            job.resolvedMediaUrl = connection.url.toString()
            job.resourceIdentity = identity
            job.etag = connection.getHeaderField("ETag").orEmpty().ifBlank { preflight.etag }
            job.lastModified = connection.getHeaderField("Last-Modified").orEmpty().ifBlank { preflight.lastModified }
            job.contentLength = total; job.totalBytes = total; job.acceptRanges = preflight.rangeSupported || code == HttpURLConnection.HTTP_PARTIAL
            var copied = requestOffset
            connection.inputStream.use { input ->
                FileOutputStream(part, requestOffset > 0L).use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (isInterrupted(job.id)) throw InterruptedIOException("Download stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        outputStream.write(buffer, 0, read)
                        copied += read
                        job.downloadedBytes = copied; job.updatedAt = System.currentTimeMillis()
                        if (total > 0L) update(job, DownloadState.DOWNLOADING, (copied * 100.0 / total).roundToInt().coerceIn(0, 99)) else dao.update(job)
                    }
                    outputStream.fd.sync()
                }
            }
            require(part.length() > 0L) { "Direct download returned an empty file" }
            if (total > 0L) require(part.length() == total) { "Direct download is incomplete (${part.length()}/$total)" }
            check(part.renameTo(output)) { "Unable to finalize direct download" }
            job.downloadedBytes = output.length(); job.resumeFilePath = output.absolutePath; job.updatedAt = System.currentTimeMillis(); dao.update(job)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private data class Preflight(val finalUrl: String, val totalBytes: Long, val etag: String, val lastModified: String, val rangeSupported: Boolean)

    private fun preflight(url: String, dir: File): Preflight {
        var head: Preflight? = null
        var connection = directConnection(url, dir).apply { requestMethod = "HEAD" }
        try {
            if (connection.responseCode in 200..299) {
                head = Preflight(connection.url.toString(), connection.contentLengthLong, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty(), false)
            }
        } finally { connection.disconnect() }
        // Accept-Ranges is advisory. Verify the actual 206 response before declaring resumable.
        connection = directConnection(head?.finalUrl ?: url, dir).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = connection.responseCode
            if (code !in 200..299 && head == null) error("Direct preflight HTTP $code")
            val range = connection.getHeaderField("Content-Range").orEmpty()
            return Preflight(
                connection.url.toString(),
                totalFromContentRange(range).takeIf { it > 0L } ?: head?.totalBytes ?: connection.contentLengthLong,
                connection.getHeaderField("ETag").orEmpty().ifBlank { head?.etag.orEmpty() },
                connection.getHeaderField("Last-Modified").orEmpty().ifBlank { head?.lastModified.orEmpty() },
                code == HttpURLConnection.HTTP_PARTIAL && contentRangeStart(range) == 0L,
            )
        } finally { connection.disconnect() }
    }

    private fun directConnection(url: String, dir: File): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        userAgent(dir).takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
        referer(dir).takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
        File(dir, "cookies.header").takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
    }

    private fun contentRangeStart(value: String): Long? = DownloadResume.contentRangeStart(value)
    private fun totalFromContentRange(value: String): Long = DownloadResume.totalFromContentRange(value)
    private fun totalBytes(connection: HttpURLConnection, offset: Long, fallback: Long): Long = totalFromContentRange(connection.getHeaderField("Content-Range").orEmpty()).takeIf { it > 0L } ?: connection.contentLengthLong.takeIf { it > 0L }?.plus(offset) ?: fallback
    private fun mediaIdentity(url: String): String = DownloadResume.resourceIdentity(url)
    private fun retryRequired(job: DownloadJob, message: String): Nothing { job.state = DownloadState.RETRY_REQUIRED; job.failureReason = message; job.updatedAt = System.currentTimeMillis(); dao.update(job); throw IllegalStateException(message) }

    private fun executeDownload(url: String, id: String, tempDir: File, job: DownloadJob) {
        val target = url.substringBefore('#')
        val generic = VideoUrlDetector.isProgressiveFileUrl(target)
        job.sourceType = when {
            target.contains(".m3u8", true) -> "HLS"
            target.contains(".mpd", true) -> "DASH"
            else -> "EXTRACTOR_PAGE"
        }
        job.manifestUrl = target.takeIf { VideoUrlDetector.isManifestUrl(it) }.orEmpty()
        job.selectedHeight = inputData.getInt(MAX_HEIGHT, job.selectedHeight)
        job.parseStage = "FORMATS"
        job.updatedAt = System.currentTimeMillis()
        dao.update(job)
        if (VideoUrlDetector.isFacebookCdnUrl(target) && VideoUrlDetector.hasByteRange(target)) {
            error("Facebook fragment is not a complete video")
        }
        val request = YoutubeDLRequest(target).apply {
            addOption("--no-playlist")
            if (!generic) {
                // yt-dlp owns HLS/DASH assembly; its .part/.ytdl checkpoints remain in filesDir.
                addOption("--continue")
                addOption("--format", formatSelector(inputData.getInt(MAX_HEIGHT, 0), job.platform))
                addOption("--merge-output-format", "mp4")
                addOption("--concurrent-fragments", "4")
                if (job.platform == VideoPlatform.FACEBOOK.name) addOption("--remux-video", "mp4")
            }
            addOption("--output", File(tempDir, "%(title).80s.%(ext)s").absolutePath)
            if (!generic) addOption("--write-thumbnail")
            userAgent(tempDir).takeIf { it.isNotBlank() }?.let {
                addOption("--user-agent", it)
            }
            referer(tempDir).ifBlank { job.pageUrl }.takeIf { it.isNotBlank() }?.let {
                if (job.platform != VideoPlatform.YOUTUBE.name) addOption("--referer", it)
            }
            cookiesFile(tempDir, target)?.let { addOption("--cookies", it.absolutePath) }
        }
        YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
            if (isInterrupted(id)) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            } else {
                update(job, DownloadState.DOWNLOADING, progress.toInt().coerceIn(0, 99))
            }
        }
    }

    private fun probeMeta(url: String, tempDir: File, job: DownloadJob) {
        job.parseStage = "FORMATS"
        job.updatedAt = System.currentTimeMillis()
        dao.update(job)
        val target = url.substringBefore('#')
        val request = YoutubeDLRequest(target).apply {
            addOption("--no-playlist")
            if (!VideoUrlDetector.isProgressiveFileUrl(target)) {
                addOption("--format", formatSelector(inputData.getInt(MAX_HEIGHT, 0), job.platform))
            }
            cookiesFile(tempDir, target)?.let { addOption("--cookies", it.absolutePath) }
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        info.title?.trim()?.ifBlank { null }?.let {
            job.title = it
            dao.update(job)
        }
        if (info.width > 0 && info.height > 0) writeSize(tempDir, info.width, info.height)
        info.thumbnail?.takeIf { it.startsWith("http") }?.let { thumb ->
            runCatching {
                java.net.URL(thumb).openStream().use { input ->
                    File(tempDir, "cover.jpg").outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun writeSize(dir: File, file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) writeSize(dir, width, height)
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeSize(dir: File, width: Int, height: Int) {
        File(dir, "size.txt").writeText("$width,$height")
    }

    private fun formatSelector(maxHeight: Int, platform: String): String {
        if (maxHeight <= 0) {
            return if (platform == VideoPlatform.FACEBOOK.name) "b[ext=mp4]/bv*+ba/b" else "bv*+ba/b"
        }
        val h = maxHeight
        // Landscape matches height; portrait matches width. Prefer exact rung so
        // bv*+ba cannot pick 144p when a 360p combined stream exists. No uncapped /b.
        return "b[height<=$h][width>=$h]/bv*[height<=$h][width>=$h]+ba/" +
            "b[width<=$h][height>=$h]/bv*[width<=$h][height>=$h]+ba/" +
            "b[height<=$h]/bv*[height<=$h]+ba/" +
            "b[width<=$h]/bv*[width<=$h]+ba"
    }

    private fun cookiesFile(dir: File, pageUrl: String): File? {
        val header = File(dir, "cookies.header").takeIf { it.isFile }?.readText().orEmpty()
        if (header.isBlank()) return null
        val host = Uri.parse(pageUrl).host?.removePrefix("www.") ?: return null
        val domain = when {
            host.contains("facebook") || host.contains("fbcdn") || host == "fb.com" || host.contains("fb.watch") ->
                ".facebook.com"
            host.contains("instagram") -> ".instagram.com"
            host.contains("tiktok") || host.contains("muscdn") -> ".tiktok.com"
            else -> ".$host"
        }
        val file = File(dir, "cookies.txt")
        file.writeText(buildString {
            appendLine("# Netscape HTTP Cookie File")
            header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=')
                appendLine("$domain\tTRUE\t/\tTRUE\t0\t$name\t$value")
            }
        })
        return file
    }

    private fun userAgent(dir: File): String =
        File(dir, VideoDownloadRepository.USER_AGENT_FILE).takeIf { it.isFile }?.readText().orEmpty()

    private fun referer(dir: File): String =
        File(dir, VideoDownloadRepository.REFERER_FILE).takeIf { it.isFile }?.readText().orEmpty()

    private fun update(job: DownloadJob, state: DownloadState, progress: Int) {
        if (state != DownloadState.CANCELLED && isInterrupted(job.id)) return
        val current = dao.find(job.id) ?: return
        if (current.state == DownloadState.CANCELLED || current.state == DownloadState.COMPLETED) return
        job.state = state
        job.progress = progress
        job.updatedAt = System.currentTimeMillis()
        dao.update(job)
        setProgressAsync(androidx.work.workDataOf("progress" to progress, "state" to state.name))
        publishDownloadNotification(job.title, progress, state)
        if (state == DownloadState.FAILED) notifyDownloadFailed(job)
    }

    /** 下载失败时给用户一个可见提示；应用在前台会立即弹出，后台时静默丢弃不崩溃。 */
    private fun notifyDownloadFailed(job: DownloadJob) {
        val message = job.failureReason?.takeIf { it.isNotBlank() }
            ?: applicationContext.getString(R.string.download_failed)
        mainHandler.post {
            runCatching { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun publishDownloadNotification(title: String, progress: Int, state: DownloadState) {
        notifyTitle = title.ifBlank { applicationContext.getString(R.string.download_notification_title) }
        notifyProgress = progress.coerceIn(0, 100)
        notifyIndeterminate = state != DownloadState.DOWNLOADING && state != DownloadState.COMPLETED
        val now = SystemClock.elapsedRealtime()
        val terminal = state == DownloadState.COMPLETED || state == DownloadState.FAILED ||
            state == DownloadState.CANCELLED || state == DownloadState.PAUSED
        if (!terminal && progress == lastNotifiedProgress && now - lastNotifyAt < 400L) return
        if (!terminal && now - lastNotifyAt < 200L) return
        lastNotifiedProgress = progress
        lastNotifyAt = now
        if (!foregroundSupported) return
        runCatching { setForegroundAsync(getForegroundInfo()) }
    }

    private fun markCancelled(id: String, job: DownloadJob) {
        abortedIds.add(id)
        val current = dao.find(id) ?: job
        if (current.state == DownloadState.COMPLETED) return
        current.state = DownloadState.CANCELLED
        current.updatedAt = System.currentTimeMillis()
        dao.update(current)
    }

    private fun markPaused(id: String, job: DownloadJob, reason: String) {
        val current = dao.find(id) ?: job
        if (current.state == DownloadState.COMPLETED || current.state == DownloadState.CANCELLED) return
        current.state = DownloadState.PAUSED
        current.failureReason = reason
        current.updatedAt = System.currentTimeMillis()
        dao.update(current)
    }

    private fun isExplicitlyCancelled(id: String): Boolean = id in abortedIds
    private fun isInterrupted(id: String): Boolean = isStopped || isExplicitlyCancelled(id)
    private fun isRetryableNetworkError(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is java.net.SocketException }

    private fun pickOutput(tempDir: File): File? {
        val files = tempDir.listFiles()?.filter { file ->
            file.isFile &&
                !file.name.endsWith(".part") &&
                !file.name.startsWith("cookies") &&
                !file.name.startsWith("cover") &&
                file.name != "size.txt" &&
                file.name != VideoDownloadRepository.USER_AGENT_FILE &&
                file.name != VideoDownloadRepository.REFERER_FILE &&
                file.name != VideoDownloadRepository.QUALITY_META_FILE &&
                file.extension.lowercase() !in setOf("jpg", "jpeg", "png", "webp", "url", "header", "json")
        }.orEmpty()
        return files.filterNot { it.name.endsWith(".m3u8", true) || isPlaylistFile(it) }
            .maxByOrNull(File::length)
    }

    private fun clearOutputs(tempDir: File) {
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile &&
                !file.name.startsWith("cookies") &&
                !file.name.startsWith("cover") &&
                file.name != "size.txt" &&
                file.name != VideoDownloadRepository.USER_AGENT_FILE &&
                file.name != VideoDownloadRepository.REFERER_FILE &&
                file.name != VideoDownloadRepository.QUALITY_META_FILE &&
                !file.name.endsWith(".url") &&
                !file.name.endsWith(".header")
            ) file.delete()
        }
    }

    private fun isPlayable(file: File): Boolean {
        if (!looksLikeMedia(file) || isPlaylistFile(file)) return false
        return hasVideoTrack(file)
    }

    private fun looksLikeMedia(file: File): Boolean {
        val bytes = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(bytes) } }.getOrDefault(0)
        if (n < 8) return false
        if (bytes[0] == '#'.code.toByte() || bytes[0] == '<'.code.toByte()) return false
        return String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" || bytes[0] == 0x1A.toByte()
    }

    private fun isPlaylistFile(file: File): Boolean =
        file.length() in 1 until 512_000 && runCatching {
            file.bufferedReader().use { it.readLine().orEmpty().startsWith("#EXTM3U") }
        }.getOrDefault(false)

    private fun hasVideoTrack(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0 &&
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0) > 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun logMediaDiagnostics(attempt: String, file: File, tempDir: File) {
        val retriever = MediaMetadataRetriever()
        val metadata = runCatching {
            retriever.setDataSource(file.absolutePath)
            listOf(
                "mime=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}",
                "width=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}",
                "height=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}",
                "duration=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}",
            ).joinToString(" ")
        }.getOrElse { "retrieverError=${it.javaClass.simpleName}:${it.message}" }
        runCatching { retriever.release() }
        Log.e(
            TAG,
            "media diagnostic url=${diagnosticUrl(attempt)} file=${file.name} size=${file.length()} " +
                "ext=${file.extension} $metadata",
        )
        logDirectoryDiagnostics(tempDir)
    }

    private fun logDirectoryDiagnostics(tempDir: File) {
        tempDir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            Log.e(TAG, "download artifact name=${file.name} size=${file.length()}")
        }
    }

    private fun diagnosticUrl(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        val queryNames = uri.queryParameterNames.joinToString(",")
        buildString {
            append(uri.scheme ?: "")
            append("://")
            append(uri.host ?: "")
            append(uri.path.orEmpty())
            if (queryNames.isNotBlank()) append("?[${queryNames}]")
        }
    }.getOrDefault(raw.substringBefore('?').substringBefore('#'))

    private fun publish(file: File, title: String): android.net.Uri {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Shorts_${System.currentTimeMillis()}" } + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Shorts/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create media file")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to write media file")
            val done = ContentValues().apply {
                put(MediaStore.Video.Media.SIZE, file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            }
            resolver.update(uri, done, null, null)
            if (!isReadableUri(uri)) {
                resolver.delete(uri, null, null)
                error("Downloaded file has no decodable video track")
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun isReadableUri(uri: android.net.Uri): Boolean =
        runCatching {
            applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0L }
        }.getOrNull() == true

    private fun failureMessage(error: Throwable): String {
        val raw = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        return when {
            raw.contains("Precondition check failed", true) || raw.contains("HTTP Error 400", true) ->
                "YouTube rejected this request. Open the video in the in-app browser, then try again."
            raw.contains("not a bot", true) || raw.contains("Sign in", true) ->
                "YouTube requires a signed-in browser session. Open the video in-app first, then download."
            raw.contains("Unsupported URL", true) -> "This platform or link is not supported"
            raw.contains("DRM", true) -> "DRM-protected video cannot be downloaded"
            raw.contains("Private", true) -> "This video requires an eligible signed-in account"
            else -> raw.lineSequence().map { it.trim() }
                .lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
                ?.removePrefix("ERROR: ")
                ?.take(160)
                ?: raw.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() }?.take(160)
                ?: "Unable to download this video"
        }
    }

    companion object {
        const val JOB_ID = "job_id"
        const val USER_AGENT = "user_agent"
        const val REFERER = "referer"
        const val MAX_HEIGHT = "max_height"
        const val QUALITY_RESOLVED = "quality_resolved"
        private const val TAG = "VideoDownload"
        private const val LEGACY_WORKER_NOTIFICATION_ID = 717
        private const val NOTIFICATION_ID = 9802
        private const val CHANNEL_ID = "downloads"
        private const val DIRECT_FILE = "direct.mp4"
        private const val SOURCE_URL = "source.url"
        private const val RESOLVER_PREFS = "video_resolver"
        private const val RESOLVER_UPDATED_AT = "yt_dlp_updated_at"
        private const val RESOLVER_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_NETWORK_RETRIES = 3
        private val RESOLVER_UPDATE_LOCK = Any()
        private val abortedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun abort(id: String) {
            abortedIds.add(id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
    }
}
