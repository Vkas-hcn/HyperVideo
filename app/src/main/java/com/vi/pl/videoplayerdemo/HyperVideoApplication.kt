package com.vi.pl.videoplayerdemo

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.hellohello.HintUtil
import com.vi.pl.videoplayerdemo.hellohello.NoticeConfigFetcher
import com.vi.pl.videoplayerdemo.hellohello.NoticeConfigStore
import com.vi.pl.videoplayerdemo.ui.DownloadQualityCoordinator

class HyperVideoApplication : Application() {
    override fun onCreate() {
        instance = this
        super.onCreate()
        VideoDownloadRepository.migrateLegacyDirectories(this)
        VideoDownloadRepository.recoverInterrupted(this)
        registerActivityLifecycleCallbacks(DownloadQualityCoordinator)
        NoticeConfigStore.init(this)
        NoticeConfigFetcher.init(this)
        HintUtil.init(this)
        HintUtil.startJob(this)
        DownloadBarLauncher.ensureRunning(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                DownloadBarLauncher.ensureRunning(this@HyperVideoApplication)
                HintUtil.startJob(this@HyperVideoApplication)
            }
        })
    }

    companion object {
        private lateinit var instance: HyperVideoApplication
        fun get(): HyperVideoApplication = instance
    }
}
