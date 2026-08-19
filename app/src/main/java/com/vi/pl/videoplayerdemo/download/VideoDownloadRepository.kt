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
        userAgent: String = "",
        cookies: String = "",
        referer: String = "",
        fallbackUrl: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = DownloadJob(
            id, pageUrl, VideoPlatform.fromUrl(pageUrl)?.name.orEmpty(), title,
            DownloadState.QUEUED, 0, null, null, null, now, 0,
        )
        executor.execute {
            DownloadDatabase.get(context).jobs().insert(job)
            val dir = File(context.applicationContext.cacheDir, "video-downloads/$id")
            if (cookies.isNotBlank() || fallbackUrl.isNotBlank()) dir.mkdirs()
            if (cookies.isNotBlank()) File(dir, "cookies.header").writeText(cookies)
            if (fallbackUrl.isNotBlank()) File(dir, "fallback.url").writeText(fallbackUrl)
            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(
                    workDataOf(
                        VideoDownloadWorker.JOB_ID to id,
                        VideoDownloadWorker.USER_AGENT to userAgent,
                        VideoDownloadWorker.REFERER to referer,
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
}
