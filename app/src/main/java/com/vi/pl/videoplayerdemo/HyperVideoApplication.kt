package com.vi.pl.videoplayerdemo

import android.app.Application
import com.vi.pl.videoplayerdemo.ui.DownloadQualityCoordinator

class HyperVideoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(DownloadQualityCoordinator)
    }
}
