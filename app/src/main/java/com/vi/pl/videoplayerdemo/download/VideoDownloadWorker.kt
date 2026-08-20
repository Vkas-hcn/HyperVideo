package com.vi.pl.videoplayerdemo.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import com.vi.pl.videoplayerdemo.search.VideoUrlDetector
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class VideoDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val dao = DownloadDatabase.get(context).jobs()
    private var resolverInitialized = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun doWork(): Result {
        val id = inputData.getString(JOB_ID) ?: return Result.failure()
        val job = dao.find(id) ?: return Result.failure()
        if (isAborted(id) || job.state == DownloadState.CANCELLED) return Result.success()
        val tempDir = File(applicationContext.cacheDir, "video-downloads/$id").apply { mkdirs() }
        return try {
            runCatching { setForegroundAsync(getForegroundInfo()).get() }
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
                addAll(facebookWatchFallback(sourceUrl))
                sourcePage?.takeIf { !VideoUrlDetector.isFeedPage(it) }?.let(::add)
                addAll(fallbacks.filterNot(VideoUrlDetector::isFeedPage))
            }.distinct()
            var lastError: Throwable? = null
            var metadataProbed = false
            for (attempt in attempts) {
                if (isAborted(id)) throw InterruptedIOException("Download stopped")
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
                        update(job, DownloadState.RESOLVING, 0)
                        ensureResolver()
                        if (!metadataProbed) {
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
                clearOutputs(tempDir)
            }
            if (attempts.isEmpty()) error("No downloadable video found yet")
            if (lastError != null) throw lastError
            if (isAborted(id)) throw InterruptedIOException("Download stopped")
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
            tempDir.deleteRecursively()
            Result.success()
        } catch (error: Throwable) {
            if (isAborted(id) || error is InterruptedIOException ||
                error.javaClass.simpleName == "CanceledException"
            ) {
                markCancelled(id, job)
                tempDir.deleteRecursively()
                Result.success()
            } else {
                Log.e(TAG, "download failed for ${job.pageUrl}", error)
                job.failureReason = failureMessage(error)
                update(job, DownloadState.FAILED, job.progress)
                tempDir.deleteRecursively()
                Result.failure()
            }
        }
    }

    override fun onStopped() {
        inputData.getString(JOB_ID)?.let { id ->
            abortedIds.add(id)
            runCatching { activeConnection?.disconnect() }
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            dao.find(id)?.let { markCancelled(id, it) }
        }
        super.onStopped()
    }

    override fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_btn)
            .setContentTitle("Downloading video")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
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
        // ponytail: restart a failed signed-CDN request instead of resuming with a stale range token.
        part.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            inputData.getString(USER_AGENT).orEmpty().takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("User-Agent", it) }
            inputData.getString(REFERER).orEmpty().takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Referer", it) }
            File(tempDir, "cookies.header").takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }
        activeConnection = connection
        try {
            val code = connection.responseCode
            Log.e(
                TAG,
                "direct response url=${diagnosticUrl(url)} final=${diagnosticUrl(connection.url.toString())} " +
                    "code=$code type=${connection.contentType} length=${connection.contentLengthLong} " +
                    "range=${connection.getHeaderField("Content-Range")} encoding=${connection.contentEncoding}",
            )
            require(code in 200..299) { "Direct download HTTP $code" }
            val total = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (isAborted(job.id)) throw InterruptedIOException("Download stopped")
                        val read = input.read(buffer)
                        if (read < 0) break
                        outputStream.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) update(job, DownloadState.DOWNLOADING, (copied * 100.0 / total).roundToInt().coerceIn(0, 99))
                    }
                }
            }
            require(part.length() > 0L) { "Direct download returned an empty file" }
            check(part.renameTo(output)) { "Unable to finalize direct download" }
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun executeDownload(url: String, id: String, tempDir: File, job: DownloadJob) {
        val target = url.substringBefore('#')
        val generic = VideoUrlDetector.isProgressiveFileUrl(target)
        if (VideoUrlDetector.isFacebookCdnUrl(target) && VideoUrlDetector.hasByteRange(target)) {
            error("Facebook fragment is not a complete video")
        }
        val request = YoutubeDLRequest(target).apply {
            addOption("--no-playlist")
            if (!generic) {
                addOption("--format", formatSelector(inputData.getInt(MAX_HEIGHT, 0), job.platform))
                addOption("--merge-output-format", "mp4")
                addOption("--concurrent-fragments", "4")
                if (job.platform == VideoPlatform.FACEBOOK.name) addOption("--remux-video", "mp4")
            }
            addOption("--output", File(tempDir, "%(title).80s.%(ext)s").absolutePath)
            if (!generic) addOption("--write-thumbnail")
            inputData.getString(USER_AGENT).orEmpty().takeIf { it.isNotBlank() }?.let {
                addOption("--user-agent", it)
            }
            inputData.getString(REFERER).orEmpty().ifBlank { job.pageUrl }.takeIf { it.isNotBlank() }?.let {
                if (job.platform != VideoPlatform.YOUTUBE.name) addOption("--referer", it)
            }
            cookiesFile(tempDir, target)?.let { addOption("--cookies", it.absolutePath) }
        }
        YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
            if (isAborted(id)) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            } else {
                update(job, DownloadState.DOWNLOADING, progress.toInt().coerceIn(0, 99))
            }
        }
    }

    private fun probeMeta(url: String, tempDir: File, job: DownloadJob) {
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

    private fun facebookWatchFallback(url: String): List<String> {
        val id = Regex("""/(?:reel|reels|videos)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Uri.parse(url).getQueryParameter("v")
            ?: return emptyList()
        return listOf(
            "https://m.facebook.com/watch/?v=$id",
            "https://m.facebook.com/reel/$id",
        ).filter { it != url }
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

    private fun update(job: DownloadJob, state: DownloadState, progress: Int) {
        if (state != DownloadState.CANCELLED && isAborted(job.id)) return
        val current = dao.find(job.id) ?: return
        if (current.state == DownloadState.CANCELLED || current.state == DownloadState.COMPLETED) return
        job.state = state
        job.progress = progress
        dao.update(job)
        setProgressAsync(androidx.work.workDataOf("progress" to progress, "state" to state.name))
    }

    private fun markCancelled(id: String, job: DownloadJob) {
        abortedIds.add(id)
        val current = dao.find(id) ?: job
        if (current.state == DownloadState.COMPLETED) return
        current.state = DownloadState.CANCELLED
        dao.update(current)
    }

    private fun isAborted(id: String): Boolean = isStopped || id in abortedIds

    private fun pickOutput(tempDir: File): File? {
        val files = tempDir.listFiles()?.filter { file ->
            file.isFile &&
                !file.name.endsWith(".part") &&
                !file.name.startsWith("cookies") &&
                !file.name.startsWith("cover") &&
                file.name != "size.txt" &&
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
        private const val TAG = "VideoDownload"
        private const val CHANNEL_ID = "video_downloads"
        private const val NOTIFICATION_ID = 717
        private const val DIRECT_FILE = "direct.mp4"
        private const val SOURCE_URL = "source.url"
        private const val RESOLVER_PREFS = "video_resolver"
        private const val RESOLVER_UPDATED_AT = "yt_dlp_updated_at"
        private const val RESOLVER_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private val RESOLVER_UPDATE_LOCK = Any()
        private val abortedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun abort(id: String) {
            abortedIds.add(id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
    }
}
