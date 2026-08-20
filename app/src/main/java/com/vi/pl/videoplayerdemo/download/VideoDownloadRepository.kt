package com.vi.pl.videoplayerdemo.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vi.pl.videoplayerdemo.search.VideoPlatform
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object VideoDownloadRepository {
    fun enqueue(
        context: Context,
        pageUrl: String,
        sourcePageUrl: String = "",
        title: String = "Downloaded video",
        coverUrl: String = "",
        userAgent: String = "",
        cookies: String = "",
        referer: String = "",
        fallbackUrl: String = "",
        maxHeight: Int = 0,
    ): String {
        val id = prepare(context, pageUrl, sourcePageUrl, title, coverUrl, userAgent, cookies, referer, fallbackUrl)
        startPrepared(context, id, maxHeight)
        return id
    }

    fun prepare(
        context: Context,
        pageUrl: String,
        sourcePageUrl: String = "",
        title: String = "Downloaded video",
        coverUrl: String = "",
        userAgent: String = "",
        cookies: String = "",
        referer: String = "",
        fallbackUrl: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sourcePage = sourcePageUrl.trim()
        val job = DownloadJob(
            id, pageUrl, VideoPlatform.fromUrl(sourcePage.ifBlank { pageUrl })?.name.orEmpty(),
            title.ifBlank { "Downloaded video" },
            DownloadState.PREPARING, 0, null, null, null, now, 0,
        )
        executor.submit {
            DownloadDatabase.get(context).jobs().insert(job)
            val dir = jobDir(context, id)
            dir.mkdirs()
            if (cookies.isNotBlank()) File(dir, "cookies.header").writeText(cookies)
            if (fallbackUrl.isNotBlank()) File(dir, "fallback.url").writeText(fallbackUrl)
            if (sourcePage.isNotBlank() && sourcePage != pageUrl) File(dir, "source.url").writeText(sourcePage)
            if (userAgent.isNotBlank()) File(dir, USER_AGENT_FILE).writeText(userAgent)
            if (referer.isNotBlank()) File(dir, REFERER_FILE).writeText(referer)
        }.get()
        if (coverUrl.isNotBlank()) coverExecutor.execute {
            runCatching { saveCover(coverUrl, File(jobDir(context, id), COVER_NAME), userAgent) }
        }
        return id
    }

    fun startPrepared(context: Context, id: String, maxHeight: Int, qualityResolved: Boolean = false) {
        executor.execute {
            val dir = jobDir(context, id)
            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(
                    workDataOf(
                        VideoDownloadWorker.JOB_ID to id,
                        VideoDownloadWorker.MAX_HEIGHT to maxHeight,
                        VideoDownloadWorker.QUALITY_RESOLVED to qualityResolved,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(id)
                .build()
            val dao = DownloadDatabase.get(context).jobs()
            dao.find(id)?.let { job ->
                job.selectedHeight = maxHeight
                job.parseStage = if (qualityResolved) "FORMATS" else job.parseStage
                job.updatedAt = System.currentTimeMillis()
                if (job.state != DownloadState.COMPLETED) {
                    job.state = if (qualityResolved) DownloadState.READY else DownloadState.PREPARING
                    dao.update(job)
                }
            }
            WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun markResolvingFormats(context: Context, id: String) {
        executor.execute {
            DownloadDatabase.get(context).jobs().find(id)?.let { job ->
                if (job.state != DownloadState.COMPLETED) {
                    job.state = DownloadState.RESOLVING_FORMATS
                    job.parseStage = "PAGE"
                    job.updatedAt = System.currentTimeMillis()
                    DownloadDatabase.get(context).jobs().update(job)
                }
            }
        }
    }

    fun saveQualityMetadata(context: Context, id: String, sourceHeight: Int, durationSec: Double) {
        File(jobDir(context, id), QUALITY_META_FILE).writeText("$sourceHeight,$durationSec")
    }

    fun setAwaitingQuality(context: Context, id: String, awaiting: Boolean) {
        val marker = File(jobDir(context, id), AWAITING_QUALITY_FILE)
        if (awaiting) marker.writeText("") else marker.delete()
        executor.execute {
            DownloadDatabase.get(context).jobs().find(id)?.let { job ->
                if (job.state != DownloadState.COMPLETED) {
                    job.state = if (awaiting) DownloadState.AWAITING_QUALITY else DownloadState.PREPARING
                    job.updatedAt = System.currentTimeMillis()
                    DownloadDatabase.get(context).jobs().update(job)
                }
            }
        }
    }

    fun isAwaitingQuality(context: Context, id: String): Boolean =
        File(jobDir(context, id), AWAITING_QUALITY_FILE).isFile

    fun failPrepared(context: Context, id: String, reason: String) {
        executor.execute {
            DownloadDatabase.get(context).jobs().find(id)?.let { job ->
                job.state = DownloadState.FAILED
                job.failureReason = reason
                DownloadDatabase.get(context).jobs().update(job)
            }
        }
    }

    fun jobDir(context: Context, id: String): File =
        File(context.applicationContext.filesDir, "video-downloads/$id")

    private fun saveCover(url: String, dest: File, userAgent: String) {
        dest.parentFile?.mkdirs()
        val connection = java.net.URL(url).openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            if (userAgent.isNotBlank()) setRequestProperty("User-Agent", userAgent)
        }
        connection.getInputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    fun cancel(context: Context, id: String) {
        VideoDownloadWorker.abort(id)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(id)
        executor.execute {
            val dao = DownloadDatabase.get(context).jobs()
            dao.find(id)?.let { job ->
                if (job.state != DownloadState.COMPLETED) {
                    job.state = DownloadState.CANCELLED
                    job.updatedAt = System.currentTimeMillis()
                    dao.update(job)
                }
            }
        }
    }

    fun pause(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(id)
    }

    fun deleteFailed(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(id)
        executor.execute {
            DownloadDatabase.get(context).jobs().delete(id)
            DownloadDatabase.get(context).segments().deleteForJob(id)
            jobDir(context, id).deleteRecursively()
        }
    }

    /** Worker interruption is recoverable; only this explicit action deletes local bytes. */
    fun resume(context: Context, id: String) {
        executor.execute {
            val dao = DownloadDatabase.get(context).jobs()
            val job = dao.find(id) ?: return@execute
            if (job.state !in setOf(DownloadState.PAUSED, DownloadState.RETRY_REQUIRED)) return@execute
            if (job.state == DownloadState.RETRY_REQUIRED) {
                // A Continue tap is the required user confirmation to discard only unsafe bytes.
                File(job.resumeFilePath).takeIf { it.path.isNotBlank() }?.delete()
                File(jobDir(context, id), "direct.mp4").delete()
                DownloadDatabase.get(context).segments().deleteForJob(id)
                job.downloadedBytes = 0L
                job.totalBytes = 0L
                job.etag = ""
                job.lastModified = ""
                job.resourceIdentity = ""
            }
            job.failureReason = null
            job.updatedAt = System.currentTimeMillis()
            job.state = DownloadState.PREPARING
            dao.update(job)
            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(workDataOf(VideoDownloadWorker.JOB_ID to id, VideoDownloadWorker.MAX_HEIGHT to job.selectedHeight, VideoDownloadWorker.QUALITY_RESOLVED to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(id).build()
            WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun recoverInterrupted(context: Context) {
        executor.execute {
            val dao = DownloadDatabase.get(context).jobs()
            dao.all().filter { it.state in setOf(
                DownloadState.PREPARING, DownloadState.RESOLVING, DownloadState.RESOLVING_PAGE,
                DownloadState.RESOLVING_FORMATS, DownloadState.RESOLVING_MANIFEST, DownloadState.READY,
                DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.VALIDATING,
            ) }.forEach { job ->
                job.state = DownloadState.PAUSED
                job.failureReason = "Download paused. Tap Continue to resume."
                job.updatedAt = System.currentTimeMillis()
                dao.update(job)
            }
        }
    }

    fun migrateLegacyDirectories(context: Context) {
        executor.execute {
            val oldRoot = File(context.applicationContext.cacheDir, "video-downloads")
            val newRoot = File(context.applicationContext.filesDir, "video-downloads")
            if (!oldRoot.isDirectory) return@execute
            newRoot.mkdirs()
            oldRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach { old ->
                val target = File(newRoot, old.name)
                if (!target.exists()) old.renameTo(target)
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val coverExecutor = Executors.newSingleThreadExecutor()
    private const val COVER_NAME = "cover.jpg"
    const val USER_AGENT_FILE = "user_agent.txt"
    const val REFERER_FILE = "referer.txt"
    const val QUALITY_META_FILE = "quality_meta.txt"
    private const val AWAITING_QUALITY_FILE = "awaiting_quality"
}
