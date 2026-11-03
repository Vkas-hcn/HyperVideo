package com.vi.pl.videoplayerdemo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.vi.pl.videoplayerdemo.download.VideoDownloadRepository
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.AdConfigStore
import com.vi.pl.videoplayerdemo.hellohello.HintUtil
import com.vi.pl.videoplayerdemo.hellohello.NoticeConfigStore
import com.vi.pl.videoplayerdemo.hellohello.fcm.FcmNotificationDecision
import com.vi.pl.videoplayerdemo.ui.DownloadQualityCoordinator
import com.vi.pl.videoplayerdemo.ui.GuideActivity
import java.lang.ref.WeakReference

class HyperVideoApplication : Application(), DefaultLifecycleObserver {
    private var hasSeenForeground = false
    private var hotStartArmed = false
    private var pendingArmRunnable: Runnable? = null
    private val contentActivities = LinkedHashSet<Activity>()
    private var topActivity: WeakReference<Activity>? = null
    private var adActivity: WeakReference<Activity>? = null
    private var pendingHotStartRelaunch: Runnable? = null
    @Volatile
    private var processInForeground = true
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        instance = this
        super<Application>.onCreate()
        VideoDownloadRepository.migrateLegacyDirectories(this)
        VideoDownloadRepository.recoverInterrupted(this)
        registerActivityLifecycleCallbacks(DownloadQualityCoordinator)
        registerActivityLifecycleCallbacks(HotStartActivityCallbacks())
        NoticeConfigStore.init(this)
        // 通知运行时：动态广播注册 + 轮询协程 + FCM 订阅 + 闹钟补排（各自兜底，失败不阻断启动）。
        runCatching { HintUtil.init(this) }
            .onFailure { Log.w(TAG, "notification runtime init failed", it) }
        runCatching { HintUtil.startJob(this) }
            .onFailure { Log.w(TAG, "notification job enqueue failed", it) }
        runCatching { FcmNotificationDecision.onRuntimeReady() }
            .onFailure { Log.w(TAG, "FCM runtime ready failed", it) }
        AdConfigStore.init(this)
        // 下发接口已移除，改用本地硬编码配置：配置必须在 SDK 初始化前就绪。
        LocalConfig.init()
        AdMobManager.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        initTrackSdk()
    }

    override fun onStart(owner: LifecycleOwner) {
        cancelPendingArm()
        processInForeground = true
        AdMobManager.updateAppForegroundState(true)
        val shouldLaunch = hotStartArmed && hasSeenForeground
        hotStartArmed = false
        hasSeenForeground = true
        if (!shouldLaunch) return
        mainHandler.post {
            if (!processInForeground) return@post
            launchReturnToGuide()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        processInForeground = false
        AdMobManager.updateAppForegroundState(false)
        dismissCurrentAdActivity()
        // 冷启动尚未进过前台不武装（对齐 Efile HotStartState.canArmOnBackground）
        if (!hasSeenForeground) {
            Log.d(TAG, "启动广告判定=进入后台 热启动不武装 原因=冷启动尚未进过前台")
            return
        }
        scheduleArm()
    }

    /** 退后台 300ms 防抖后再武装；期间回前台则取消（对齐 Efile §7.9-1）。 */
    private fun scheduleArm() {
        cancelPendingArm()
        pendingArmRunnable = Runnable {
            pendingArmRunnable = null
            if (processInForeground) return@Runnable
            hotStartArmed = true
            finishNonWhitelisted()
            Log.d(TAG, "启动广告判定=热启动已武装 防抖毫秒=$HOT_START_DEBOUNCE_MS")
        }.also { mainHandler.postDelayed(it, HOT_START_DEBOUNCE_MS) }
    }

    private fun cancelPendingArm() {
        pendingArmRunnable?.let(mainHandler::removeCallbacks)
        pendingArmRunnable = null
    }

    /** 回前台重置：结束启动链以外的页面（白名单：MainActivity、GuideActivity）。 */
    private fun finishNonWhitelisted() {
        contentActivities.toList().forEach { activity ->
            if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) {
                return@forEach
            }
            if (activity is MainActivity || activity is GuideActivity) {
                return@forEach
            }
            runCatching { activity.finish() }
                .onSuccess { Log.d(TAG, "回前台重置：结束页面=" + activity.javaClass.simpleName) }
                .onFailure { error ->
                    Log.w(TAG, "回前台重置：结束页面失败 " + activity.javaClass.simpleName, error)
                }
        }
    }

    /** 回前台清栈重置：CLEAR_TASK 打开启动页，广告结束落首页（无条件生效，无留存策略开关）。 */
    private fun launchReturnToGuide() {
        // 屏幕未点亮不重开屏（对齐 Efile §7.9-5）
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) {
            Log.d(TAG, "热启动跳过 原因=屏幕未点亮")
            return
        }
        val current = topActivity?.get()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (current is GuideActivity) {
            Log.d(TAG, "热启动跳过 原因=当前已是启动页")
            return
        }
        runCatching {
            startActivity(GuideActivity.createReturnToMainIntent(this))
            Log.d(TAG, "回前台重置：CLEAR_TASK 打开启动页")
        }.onFailure { error ->
            Log.w(TAG, "回前台重置启动失败，回退普通热启动", error)
            launchPlainHotStart()
        }
    }

    /** 普通热启动（回退路径）：仅 SINGLE_TOP 压栈，广告结束后回原页面。 */
    private fun launchPlainHotStart() {
        val current = topActivity?.get()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (current is GuideActivity) return
        val intent = GuideActivity.createHotStartIntent(this)
        runCatching {
            if (current != null) current.startActivity(intent)
            else startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "热启动启动页打开失败", it) }
    }

    fun isAppForeground(): Boolean = processInForeground

    /**
     * 旧启动页已在离场时，延迟清栈拉起新的启动页。
     *
     * 不能立即 startActivity：旧流程可能刚把 MainActivity 入栈，两个启动顺序竞争时
     * 新 GuideActivity 会被 MainActivity 盖住，导致广告流程没展示就回首页。
     */
    fun relaunchGuideForHotStart(source: Intent) {
        val intent = Intent(source).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        pendingHotStartRelaunch?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            pendingHotStartRelaunch = null
            runCatching { startActivity(intent) }
                .onFailure { Log.w(TAG, "hot start relaunch failed", it) }
        }
        pendingHotStartRelaunch = runnable
        mainHandler.postDelayed(runnable, HOT_START_RELAUNCH_DELAY_MS)
    }

    /** 关闭当前 GMA 全屏广告 Activity；通知热启动需要先收起正在展示的开屏/插屏。 */
    fun dismissCurrentAdActivity(): Boolean {
        val activity = adActivity?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        return runCatching {
            activity.finish()
            true
        }.getOrDefault(false)
    }

    private inner class HotStartActivityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            update(activity)

        override fun onActivityStarted(activity: Activity) = update(activity)
        override fun onActivityResumed(activity: Activity) = update(activity)
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            contentActivities.remove(activity)
            if (topActivity?.get() === activity) topActivity = null
            if (adActivity?.get() === activity) adActivity = null
        }

        private fun update(activity: Activity) {
            if (activity is AdActivity) {
                adActivity = WeakReference(activity)
            } else {
                topActivity = WeakReference(activity)
                contentActivities.add(activity)
            }
        }
    }

    fun initTrackSdk() {
        val baseUrl = if (BuildConfig.DEBUG) {
            "https://testbi.googletogoogle.com/"
        } else {
            "https://bi.googletogoogle.com"
        }
        val trackConfig = TrackConfig.Builder(this)
            .baseUrl(baseUrl)
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
        private const val HOT_START_DEBOUNCE_MS = 300L
        private const val HOT_START_RELAUNCH_DELAY_MS = 300L
        private lateinit var instance: HyperVideoApplication
        fun get(): HyperVideoApplication = instance
    }
}
