package com.vi.pl.videoplayerdemo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.LogLevel
import com.flux.tracksdk.core.TrackConfig
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.google.android.gms.ads.AdActivity
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.AdConfigStore
import com.vi.pl.videoplayerdemo.hellohello.HintUtil
import com.vi.pl.videoplayerdemo.hellohello.NoticeConfigStore
import com.vi.pl.videoplayerdemo.ui.DownloadQualityCoordinator
import com.vi.pl.videoplayerdemo.ui.GuideActivity
import java.lang.ref.WeakReference

class HyperVideoApplication : Application(), DefaultLifecycleObserver {
    private var hotStartReady = false
    private var topActivity: WeakReference<Activity>? = null
    private var adActivity: WeakReference<Activity>? = null
    @Volatile private var processInForeground = true

    override fun onCreate() {
        instance = this
        super<Application>.onCreate()
        VideoDownloadRepository.migrateLegacyDirectories(this)
        VideoDownloadRepository.recoverInterrupted(this)
        registerActivityLifecycleCallbacks(DownloadQualityCoordinator)
        registerActivityLifecycleCallbacks(HotStartActivityCallbacks())
        NoticeConfigStore.init(this)
        AdConfigStore.init(this)
        // 下发接口已移除，改用本地硬编码配置：配置必须在 SDK 初始化前就绪。
        LocalConfig.init()
        AdMobManager.initialize(this)
        HintUtil.init(this)
        HintUtil.startJob(this)
        DownloadBarLauncher.ensureRunning(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        processInForeground = true
        AdMobManager.updateAppForegroundState(true)
        DownloadBarLauncher.ensureRunning(this)
        HintUtil.startJob(this)
        if (!hotStartReady) return
        hotStartReady = false
        launchGuideForHotStart()
    }

    override fun onStop(owner: LifecycleOwner) {
        processInForeground = false
        AdMobManager.updateAppForegroundState(false)
        hotStartReady = true
        adActivity?.get()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?.finish()
        Log.d(TAG, "Hot start is ready")
    }

    private fun launchGuideForHotStart() {
        val intent = GuideActivity.createHotStartIntent(this)
        val currentActivity = topActivity?.get()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
        runCatching {
            if (currentActivity != null) currentActivity.startActivity(intent)
            else startActivity(intent)
            Log.d(TAG, "Hot start launched GuideActivity")
        }.onFailure { Log.w(TAG, "Hot start GuideActivity launch failed", it) }
    }

    fun isAppForeground(): Boolean = processInForeground

    private inner class HotStartActivityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = update(activity)
        override fun onActivityStarted(activity: Activity) = update(activity)
        override fun onActivityResumed(activity: Activity) = update(activity)
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (topActivity?.get() === activity) topActivity = null
            if (adActivity?.get() === activity) adActivity = null
        }

        private fun update(activity: Activity) {
            if (activity is AdActivity) adActivity = WeakReference(activity)
            else topActivity = WeakReference(activity)
        }
    }
    fun initTrackSdk() {
        val trackConfig = TrackConfig.Builder(this)
            .baseUrl("https://bi.googletogoogle.com")
            .debug(BuildConfig.DEBUG)
            .appId(packageName)
            .channel("google_play")
            .batchSize(20)
            .defaultUploadPolicy(TrackPolicy.BATCH)
            .uploadThreadCount(2)
            .maxRetryCount(5)
            .connectTimeout(10_000)
            .readTimeout(10_000)
            .enablePiggyback(true)
            .enableAppList(false)
            .build()
        TrackSDK.init(trackConfig)
    }

    fun flushTrackEvents() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities) ?: return
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            TrackSDK.flush()
        }
    }

    @SuppressLint("HardwareIds")
    private fun initAdJust(application: Application) {
        val deviceId = TrackSDK.getInstance().effectiveDistinctId
        val appToken = ""
        val environment: String = if (BuildConfig.DEBUG) {
            AdjustConfig.ENVIRONMENT_SANDBOX
        } else {
            AdjustConfig.ENVIRONMENT_PRODUCTION
        }
        val config = AdjustConfig(application, appToken, environment)
        config.setLogLevel(LogLevel.WARN)
        config.setExternalDeviceId(deviceId)
        config.enableFirstSessionDelay()
        config.enableSendingInBackground()
        Adjust.initSdk(config)
        Adjust.endFirstSessionDelay()
    }
    companion object {
        private const val TAG = "HyperVideoApp"
        private lateinit var instance: HyperVideoApplication
        fun get(): HyperVideoApplication = instance
    }
}
