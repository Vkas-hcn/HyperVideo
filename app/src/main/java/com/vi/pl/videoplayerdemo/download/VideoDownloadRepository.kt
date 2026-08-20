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
        title: String = "Downloaded video",
        coverUrl: String = "",
        userAgent: String = "",
        cookies: String = "",
        referer: String = "",
        fallbackUrl: String = "",
        maxHeight: Int = 0,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = DownloadJob(
            id, pageUrl, VideoPlatform.fromUrl(pageUrl)?.name.orEmpty(),
            title.ifBlank { "Downloaded video" },
            DownloadState.QUEUED, 0, null, null, null, now, 0,
        )
        executor.execute {
            DownloadDatabase.get(context).jobs().insert(job)
            val dir = jobDir(context, id)
            if (cookies.isNotBlank() || fallbackUrl.isNotBlank() || coverUrl.isNotBlank()) dir.mkdirs()
            if (cookies.isNotBlank()) File(dir, "cookies.header").writeText(cookies)
            if (fallbackUrl.isNotBlank()) File(dir, "fallback.url").writeText(fallbackUrl)
            if (coverUrl.isNotBlank()) runCatching { saveCover(coverUrl, File(dir, COVER_NAME), userAgent) }
            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(
                    workDataOf(
                        VideoDownloadWorker.JOB_ID to id,
                        VideoDownloadWorker.USER_AGENT to userAgent,
                        VideoDownloadWorker.REFERER to referer,
                        VideoDownloadWorker.MAX_HEIGHT to maxHeight,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(id)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request)
        }
        return id
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
        executor.execute {
            WorkManager.getInstance(context).cancelUniqueWork(id)
            val dao = DownloadDatabase.get(context).jobs()
            dao.find(id)?.let { job ->
                job.state = DownloadState.CANCELLED
                dao.update(job)
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private const val COVER_NAME = "cover.jpg"
}
