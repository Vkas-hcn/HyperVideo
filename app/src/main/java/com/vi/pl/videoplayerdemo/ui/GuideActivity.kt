package com.vi.pl.videoplayerdemo.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.vi.pl.videoplayerdemo.HyperVideoApplication
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.ads.AdLoadListener
import com.vi.pl.videoplayerdemo.ads.AdLogger
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.AdShowListener
import com.vi.pl.videoplayerdemo.ads.StartupPlan
import com.vi.pl.videoplayerdemo.hellohello.HintUtil

class GuideActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var splashAnimator: ObjectAnimator? = null
    private var umpDone = false
    private var adFlowStarted = false
    private var adShowing = false
    private var adFinished = false
    private var openLoadIssued = false
    private var adTimeout: Runnable? = null
    private var watchdog: Runnable? = null
    private var intervalRetried = false
    private var guideReplenished = false
    private var notifRequested = false
    private var leaving = false
    private var startedAtElapsed = 0L
    private var notificationClickReported = false

    /** 进程刚创建后第一次进入启动页为冷启动（对齐 Efile SplashActivity.isColdStart）。 */
    private var isColdStart = false

    private val isHotStart: Boolean
        get() = intent.getBooleanExtra(EXTRA_HOT_START, false)

    private val isReturnToMain: Boolean
        get() = intent.getBooleanExtra(EXTRA_RETURN_TO_MAIN, false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AdLogger.guidePermissionResolved(granted)
            startUmp()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportNotificationClickIfNeeded(intent)
        isColdStart = !processAlreadyEntered
        processAlreadyEntered = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        onBackPressedDispatcher.addCallback(this) { }
        // 首帧前预热开屏：不看通知权限、不等 UMP（对齐 Efile warmupSplashAd）
        AdMobManager.warmupStartupOpen()
        showSplash()
        requestNotificationThenUmp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportNotificationClickIfNeeded(intent)
        if (intent.getBooleanExtra(EXTRA_HOT_START, false)) restartStartupFlow()
    }

    override fun onResume() {
        super.onResume()
        continueStartup()
    }

    private fun showSplash() {
        setContentView(R.layout.activity_guide_splash)
        applyInsets(findViewById(android.R.id.content))
        val progress = findViewById<ProgressBar>(R.id.splash_progress)
        splashAnimator = ObjectAnimator.ofInt(progress, "progress", 0, progress.max).apply {
            duration = AdMobManager.getIntersOpenLoadTimeoutMs().coerceAtLeast(1L)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    continueStartup()
                }
            })
            start()
        }
    }

    private fun restartStartupFlow() {
        splashAnimator?.cancel()
        clearTimeout()
        clearWatchdog()
        handler.removeCallbacksAndMessages(null)
        umpDone = false
        adFlowStarted = false
        adShowing = false
        adFinished = false
        openLoadIssued = false
        intervalRetried = false
        guideReplenished = false
        leaving = false
        showSplash()
        requestNotificationThenUmp()
    }

    /** 通知点击埋点：本地新闻通知（CO_SHOW_TYPE）或 FCM（EXTRA_FROM_FCM）来源，实例内只上报一次。 */
    private fun reportNotificationClickIfNeeded(intent: Intent) {
        if (notificationClickReported) return
        val fromNotification = intent.hasExtra(HintUtil.CO_SHOW_TYPE) ||
            intent.getBooleanExtra(EXTRA_FROM_FCM, false)
        if (!fromNotification) return
        notificationClickReported = true
        runCatching { TrackSDK.track("news_notification_click", null, TrackPolicy.IMMEDIATE) }
            .onFailure { Log.w(TAG, "news_notification_click track failed", it) }
    }

    /** 通知权限 → UMP（对齐 Efile startSplashConsentFlow：同意/拒绝/已授权都继续走 UMP）。 */
    private fun requestNotificationThenUmp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startUmp()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || notifRequested) {
            if (granted) AdLogger.guidePermissionResolved(true)
            startUmp()
            return
        }
        notifRequested = true
        AdLogger.guidePermissionRequested()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startUmp() {
        AdLogger.guideUmpStarted()
        if (getSharedPreferences(UMP_PREFS, MODE_PRIVATE).getBoolean(UMP_DONE, false)) {
            finishUmp(); return
        }
        runCatching {
            val debug = ConsentDebugSettings.Builder(this)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .addTestDeviceHashedId("65363D8B2D35B2D3B4361B43C8E86327")
                .build()
            val parameters = ConsentRequestParameters.Builder()
                .setConsentDebugSettings(debug)
                .build()
            val consentInformation = UserMessagingPlatform.getConsentInformation(this)
            consentInformation.requestConsentInfoUpdate(
                this, parameters,
                {
                    runCatching { UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { finishUmp() } }
                        .onFailure { Log.w(TAG, "UMP form failed", it); finishUmp() }
                },
                {
                    Log.w(TAG, "UMP update failed: ${it.message}"); finishUmp()
                }
            )
        }
            .onFailure { Log.w(TAG, "UMP unavailable", it); finishUmp() }
    }

    private fun finishUmp() {
        if (umpDone) return
        umpDone = true
        AdLogger.guideUmpCompleted()
        getSharedPreferences(UMP_PREFS, MODE_PRIVATE).edit().putBoolean(UMP_DONE, true).apply()
        startAds()
        startAdTimeout()
        armWatchdog()
    }

    private fun startAds() {
        if (adFlowStarted) return
        adFlowStarted = true
        AdMobManager.runWhenInitialized { initialized ->
            if (!initialized || isFinishing || isDestroyed) return@runWhenInitialized continueStartup()
            AdMobManager.resetGuideShowState()
            if (isColdStart) {
                // 冷启动：只加载开屏；guide1/2 在展示关闭后补货
                issueOpenLoad()
            } else if (AdMobManager.startupCachedSlot() != null) {
                // 热启动：三路有缓存，直接进入展示，不发起新加载
                continueStartup()
            } else {
                // 热启动无缓存：回落冷启动路径（只加载开屏）
                issueOpenLoad()
            }
        }
    }

    private fun issueOpenLoad() {
        if (openLoadIssued) return
        openLoadIssued = true
        AdMobManager.loadOpenAd(applicationContext, object : AdLoadListener {
            override fun onAdLoaded() = continueStartup()
            override fun onAdFailedToLoad(error: String) = continueStartup()
        })
    }

    private fun continueStartup() {
        if (!umpDone || isFinishing || isDestroyed) return
        if (adShowing) return
        if (adFinished) {
            leaveToMain(); return
        }
        // 退后台不推进：等 onResume 重入（对齐 Efile AdResult.Cancelled 语义）
        if (!HyperVideoApplication.get().isAppForeground()) return
        startAdTimeout()
        // 全屏最小间隔：≤2s 短等重试一次；>2s 放弃本次启动广告（对齐 Efile startupIntervalWaitMillis）
        when (val wait = StartupPlan.startupIntervalWaitMillis(AdMobManager.fullscreenIntervalRemainingMillis())) {
            null -> {
                AdLogger.interstitialShowBlocked("fullscreen_min_interval", null)
                finishAds()
                return
            }

            else -> if (wait > 0L) {
                if (intervalRetried) {
                    finishAds(); return
                }
                intervalRetried = true
                handler.postDelayed({ continueStartup() }, wait)
                return
            }
        }
        val hasOpen = AdMobManager.isOpenAdLoaded()
        val hasGuide = AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_1) ||
            AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_2)
        if (!hasOpen && !hasGuide) {
            if (isColdStart || openLoadIssued) {
                // 加载中或等待重试：200ms 复查（加载窗口由 startAdTimeout 截止）
                handler.postDelayed({ continueStartup() }, POLL_INTERVAL_MS)
            }
            return
        }
        var failureReported = false
        val shown = AdMobManager.showGuideAdIfReady(this, object : AdShowListener {
            override fun onAdShowed() = Unit

            override fun onAdShowFailed(error: String) {
                failureReported = true; adShowing = false; continueStartup()
            }

            override fun onAdClosed() {
                adShowing = false
                if (!HyperVideoApplication.get().isAppForeground()) {
                    // 退后台被强关：视为取消，重置单次门闩，回前台重试
                    AdMobManager.resetGuideShowState()
                    return
                }
                scheduleGuideReplenish()
                finishAds()
            }

            override fun onAdClicked() = Unit
        })
        if (shown && !failureReported) {
            adShowing = true
        } else if (!failureReported) {
            // 展示被内部拦截（上限/后台竞态等）：本次启动不再有广告，正常离场
            finishAds()
        }
    }

    /** 看门狗兜底：开屏加载窗口 + 5s 余量，仅防流程卡死；广告展示中轮询不强制离场。 */
    private fun armWatchdog(delayMs: Long = AdMobManager.getIntersOpenLoadTimeoutMs() + WATCHDOG_MARGIN_MS) {
        clearWatchdog()
        watchdog = Runnable {
            watchdog = null
            if (adShowing) {
                armWatchdog(WATCHDOG_AD_POLL_MILLIS)
            } else {
                finishAds()
            }
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun clearWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
    }

    private fun startAdTimeout() {
        if (!umpDone || adFinished || adTimeout != null) return
        val timeoutMs = AdMobManager.getIntersOpenLoadTimeoutMs()
        AdLogger.guideTimeoutArmed(timeoutMs)
        adTimeout = Runnable {
            if (!adShowing) {
                AdLogger.guideAdTimeout(timeoutMs)
            }
            finishAds()
        }.also { handler.postDelayed(it, timeoutMs) }
    }

    /** 开屏/启动插屏展示关闭后，延迟补货 guide1/2（对齐 Efile §7.8-1）。 */
    private fun scheduleGuideReplenish() {
        if (guideReplenished) return
        guideReplenished = true
        handler.postDelayed({
            AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.GUIDE_1)
            AdMobManager.loadInterstitial(applicationContext, AdMobManager.InterstitialPlacement.GUIDE_2)
        }, StartupPlan.STARTUP_REPLENISH_DELAY_MILLIS)
    }

    private fun finishAds() {
        if (adFinished || adShowing) return
        adFinished = true
        clearTimeout()
        continueStartup()
    }

    private fun clearTimeout() {
        adTimeout?.let(handler::removeCallbacks)
        adTimeout = null
    }

    /** 启动页最短可见 300ms，避免开屏命中即跳、Logo 一闪而过（对齐 Efile §7.5-4）。 */
    private fun leaveToMain() {
        if (leaving) return
        leaving = true
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
        val remaining = MIN_VISIBLE_MILLIS - elapsed
        if (remaining > 0L) {
            handler.postDelayed({ leaveNow() }, remaining)
        } else {
            leaveNow()
        }
    }

    private fun leaveNow() {
        clearTimeout()
        clearWatchdog()
        if (isFinishing || isDestroyed) return
        // 通知点击带 URL：落地内置浏览器打开该页面；无 URL 默认首页
        val targetUrl = intent.data?.toString().orEmpty()
        if (targetUrl.isNotBlank()) {
            goBrowser(targetUrl)
            return
        }
        // 清栈重置与冷启动都落首页；普通热启动（仅 CLEAR_TASK 失败回退可达）回原页面
        if (isHotStart && !isReturnToMain) {
            finish(); return
        }
        goMain()
    }

    /** 通知文案配置了 URL 时跳转任意页面（内置浏览器打开）；不填默认走 goMain 首页。 */
    private fun goBrowser(url: String) {
        if (isFinishing || isDestroyed) return
        startActivity(VideoBrowserActivity.intent(this, url))
        finish()
    }

    private fun goMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            insets
        }
    }

    override fun onDestroy() {
        splashAnimator?.cancel()
        clearTimeout()
        clearWatchdog()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val UMP_PREFS = "ump"
        const val UMP_DONE = "completed"
        const val TAG = "GuideActivity"
        const val EXTRA_HOT_START = "extra_hot_start"
        const val EXTRA_RETURN_TO_MAIN = "extra_return_to_main"
        const val EXTRA_FROM_FCM = "extra_from_fcm"
        private const val MIN_VISIBLE_MILLIS = 300L
        private const val WATCHDOG_MARGIN_MS = 5_000L
        private const val WATCHDOG_AD_POLL_MILLIS = 1_000L
        private const val POLL_INTERVAL_MS = 200L

        /** 进程被系统新建时为 false；第一次进入启动页后置 true，用于区分冷/热启动（对齐 Efile）。 */
        private var processAlreadyEntered = false

        /** 普通热启动（仅 SINGLE_TOP，不清栈；当前仅作为 CLEAR_TASK 失败的回退路径）。 */
        fun createHotStartIntent(context: android.content.Context): Intent =
            Intent(context, GuideActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_HOT_START, true)
            }

        /** 回前台清栈重置（NEW_TASK|CLEAR_TASK），广告结束后落首页 MainActivity。 */
        fun createReturnToMainIntent(context: android.content.Context): Intent =
            Intent(context, GuideActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_HOT_START, true)
                putExtra(EXTRA_RETURN_TO_MAIN, true)
            }

        /**
         * 通知点击入口：清栈重走启动页广告流程（对齐 AnonyBrowser GuideActivity.createHotStartIntent 的
         * NEW_TASK|CLEAR_TOP|SINGLE_TOP 语义），带 EXTRA_RETURN_TO_MAIN 保证广告结束后落主页。
         */
        fun createNotificationClickIntent(context: android.content.Context): Intent =
            Intent(context, GuideActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_HOT_START, true)
                putExtra(EXTRA_RETURN_TO_MAIN, true)
            }
    }
}
