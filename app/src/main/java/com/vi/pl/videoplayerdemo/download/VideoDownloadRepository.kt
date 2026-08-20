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
            DownloadState.QUEUED, 0, null, null, null, now, 0,
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
            WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun saveQualityMetadata(context: Context, id: String, sourceHeight: Int, durationSec: Double) {
        File(jobDir(context, id), QUALITY_META_FILE).writeText("$sourceHeight,$durationSec")
    }

    fun setAwaitingQuality(context: Context, id: String, awaiting: Boolean) {
        val marker = File(jobDir(context, id), AWAITING_QUALITY_FILE)
        if (awaiting) marker.writeText("") else marker.delete()
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
        File(context.applicationContext.cacheDir, "video-downloads/$id")

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
                    dao.update(job)
                }
            }
        }
    }

    fun deleteFailed(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(id)
        executor.execute {
            DownloadDatabase.get(context).jobs().delete(id)
            jobDir(context, id).deleteRecursively()
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
