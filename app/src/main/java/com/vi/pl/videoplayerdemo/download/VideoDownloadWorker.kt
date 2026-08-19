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

class VideoDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val dao = DownloadDatabase.get(context).jobs()

    override fun doWork(): Result {
        val id = inputData.getString(JOB_ID) ?: return Result.failure()
        val job = dao.find(id) ?: return Result.failure()
        val tempDir = File(applicationContext.cacheDir, "video-downloads/$id").apply { mkdirs() }
        return try {
            runCatching { setForegroundAsync(getForegroundInfo()).get() }
            update(job, DownloadState.RESOLVING, 0)
            initializeResolver()
            update(job, DownloadState.DOWNLOADING, 0)
            val sourceUrl = VideoUrlDetector.facebookDownloadUrl(job.pageUrl) ?: job.pageUrl
            val fallbacks = File(tempDir, "fallback.url").takeIf { it.isFile }?.readLines().orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { if (VideoUrlDetector.isManifestUrl(it)) it else VideoUrlDetector.stripByteRange(it) }
            val attempts = buildList {
                add(sourceUrl)
                facebookWatchFallback(sourceUrl)?.let(::add)
                addAll(fallbacks)
            }.distinct()
            var lastError: Throwable? = null
            for (attempt in attempts) {
                lastError = runCatching {
                    executeDownload(attempt, id, tempDir, job)
                    val produced = pickOutput(tempDir) ?: error("No video file was produced")
                    require(isPlayable(produced, job)) { "Downloaded file has no decodable video track" }
                }.exceptionOrNull()
                if (lastError == null) break
                Log.w(TAG, "download attempt failed for $attempt", lastError)
            }
            if (lastError != null) throw lastError
            val output = pickOutput(tempDir) ?: error("No video file was produced")
            update(job, DownloadState.VALIDATING, 99)
            require(isPlayable(output, job)) { "Downloaded file has no decodable video track" }
            update(job, DownloadState.MERGING, 99)
            val uri = publish(output, output.nameWithoutExtension)
            job.mediaUri = uri.toString()
            job.title = output.nameWithoutExtension.ifBlank { job.title }
            job.completedAt = System.currentTimeMillis()
            update(job, DownloadState.COMPLETED, 100)
            tempDir.deleteRecursively()
            Result.success()
        } catch (error: Throwable) {
            Log.e(TAG, "download failed for ${job.pageUrl}", error)
            job.failureReason = failureMessage(error)
            update(job, DownloadState.FAILED, job.progress)
            tempDir.deleteRecursively()
            Result.failure()
        }
    }

    override fun onStopped() {
        inputData.getString(JOB_ID)?.let { id ->
            dao.find(id)?.let { job -> update(job, DownloadState.CANCELLED, job.progress) }
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
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

    private fun initializeResolver() {
        YoutubeDL.getInstance().init(applicationContext)
        FFmpeg.getInstance().init(applicationContext)
        // Bundled yt-dlp in 0.18.1 is too old for current YouTube. Nightly is required.
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        }.onFailure { Log.w(TAG, "yt-dlp update skipped", it) }
    }

    private fun executeDownload(url: String, id: String, tempDir: File, job: DownloadJob) {
        val generic = VideoUrlDetector.isFacebookCdnUrl(url) && !VideoUrlDetector.isManifestUrl(url)
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            if (!generic) {
                addOption("--format", "bv*+ba/b")
                addOption("--merge-output-format", "mp4")
                addOption("--concurrent-fragments", "4")
            }
            addOption("--output", File(tempDir, "%(title).80s.%(ext)s").absolutePath)
            inputData.getString(USER_AGENT).orEmpty().takeIf { it.isNotBlank() }?.let {
                addOption("--user-agent", it)
            }
            inputData.getString(REFERER).orEmpty().ifBlank { job.pageUrl }.takeIf { it.isNotBlank() }?.let {
                if (job.platform != VideoPlatform.YOUTUBE.name) addOption("--referer", it)
            }
            cookiesFile(tempDir, url)?.let { addOption("--cookies", it.absolutePath) }
        }
        YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
            update(job, DownloadState.DOWNLOADING, progress.toInt().coerceIn(0, 99))
        }
    }

    private fun facebookWatchFallback(url: String): String? {
        val id = Regex("""/(?:reel|reels|videos)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Uri.parse(url).getQueryParameter("v")
            ?: return null
        val watch = "https://m.facebook.com/watch/?v=$id"
        return watch.takeIf { it != url }
    }

    private fun cookiesFile(dir: File, pageUrl: String): File? {
        val header = File(dir, "cookies.header").takeIf { it.isFile }?.readText().orEmpty()
        if (header.isBlank()) return null
        val host = Uri.parse(pageUrl).host?.removePrefix("www.") ?: return null
        val domain = when {
            host.contains("facebook") || host.contains("fbcdn") || host == "fb.com" || host.contains("fb.watch") ->
                ".facebook.com"
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
        job.state = state
        job.progress = progress
        dao.update(job)
        setProgressAsync(androidx.work.workDataOf("progress" to progress, "state" to state.name))
    }

    private fun pickOutput(tempDir: File): File? {
        val files = tempDir.listFiles()?.filter { file ->
            file.isFile &&
                !file.name.endsWith(".part") &&
                !file.name.startsWith("cookies") &&
                !file.name.endsWith(".url") &&
                !file.name.endsWith(".header") &&
                !file.name.endsWith(".json")
        }.orEmpty()
        val media = files.filterNot { it.name.endsWith(".m3u8", true) || isPlaylistFile(it) }
        return media.maxByOrNull(File::length) ?: files.maxByOrNull(File::length)
    }

    private fun isPlayable(file: File, job: DownloadJob): Boolean =
        hasVideoTrack(file) ||
            (job.platform == VideoPlatform.FACEBOOK.name && file.length() > 200_000 && !isPlaylistFile(file))

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

    private fun publish(file: File, title: String): android.net.Uri {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Shorts_${System.currentTimeMillis()}" } + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Shorts")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create media file")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to write media file")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

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
        private const val TAG = "VideoDownload"
        private const val CHANNEL_ID = "video_downloads"
        private const val NOTIFICATION_ID = 717
    }
}
