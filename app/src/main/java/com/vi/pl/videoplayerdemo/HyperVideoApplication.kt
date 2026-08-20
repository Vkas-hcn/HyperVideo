package com.vi.pl.videoplayerdemo

import android.app.Application
import com.vi.pl.videoplayerdemo.ui.DownloadQualityCoordinator
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository

class HyperVideoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VideoDownloadRepository.migrateLegacyDirectories(this)
        VideoDownloadRepository.recoverInterrupted(this)
        registerActivityLifecycleCallbacks(DownloadQualityCoordinator)
    }
}
