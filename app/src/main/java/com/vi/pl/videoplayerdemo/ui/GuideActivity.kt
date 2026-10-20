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
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.ads.AdLoadListener
import com.vi.pl.videoplayerdemo.ads.AdLogger
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.AdShowListener
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher

class GuideActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var splashAnimator: ObjectAnimator? = null
    private var umpDone = false
    private var permissionDone = false
    private var permissionInFlight = false
    private var adFlowStarted = false
    private var adShowing = false
    private var adFinished = false
    private var openReady = false
    private var guide1Ready = false
    private var guide2Ready = false
    private var adTimeout: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionInFlight = false
            permissionDone = true
            AdLogger.guidePermissionResolved(it)
            startAdTimeout()
            if (it) DownloadBarLauncher.ensureRunning(this)
            continueStartup()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { }
        showSplash()
        startUmp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_HOT_START, false)) restartStartupFlow()
    }

    override fun onResume() {
        super.onResume()
        if (!permissionDone && umpDone) requestNotificationPermission()
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
                    permissionDone =
                        !shouldRequestPermission(); requestNotificationPermission(); continueStartup()
                }
            })
            start()
        }
    }

    private fun restartStartupFlow() {
        splashAnimator?.cancel()
        clearTimeout()
        handler.removeCallbacksAndMessages(null)
        umpDone = false
        permissionDone = false
        permissionInFlight = false
        adFlowStarted = false
        adShowing = false
        adFinished = false
        openReady = false
        guide1Ready = false
        guide2Ready = false
        showSplash()
        startUmp()
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
        requestNotificationPermission()
        if (permissionDone) startAdTimeout()
    }

    private fun shouldRequestPermission() = Build.VERSION.SDK_INT >= 33 && !isPermissionGranted() &&
            !getSharedPreferences(PERM_PREFS, MODE_PRIVATE).getBoolean(PERM_REQUESTED, false)

    private fun isPermissionGranted() =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (!umpDone || permissionDone || permissionInFlight || !shouldRequestPermission()) {
            if (umpDone && !permissionDone && !shouldRequestPermission()) {
                permissionDone = true
                AdLogger.guidePermissionResolved(true)
                startAdTimeout()
            }; return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        getSharedPreferences(PERM_PREFS, MODE_PRIVATE).edit().putBoolean(PERM_REQUESTED, true)
            .apply()
        permissionInFlight = true
        AdLogger.guidePermissionRequested()
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startAds() {
        if (adFlowStarted) return
        adFlowStarted = true
        AdMobManager.runWhenInitialized { initialized ->
            if (!initialized || isFinishing || isDestroyed) return@runWhenInitialized continueStartup()
            AdMobManager.resetGuideShowState()
            AdMobManager.loadOpenAd(applicationContext, result { openReady = it })
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    AdMobManager.loadInterstitial(
                        applicationContext,
                        AdMobManager.InterstitialPlacement.GUIDE_1,
                        result { guide1Ready = it }); AdMobManager.loadInterstitial(
                        applicationContext,
                        AdMobManager.InterstitialPlacement.GUIDE_2,
                        result { guide2Ready = it })
                }
            }, GUIDE_LOAD_DELAY_MS)
        }
    }

    private fun result(update: (Boolean) -> Unit) = object : AdLoadListener {
        override fun onAdLoaded() {
            update(true); continueStartup()
        }

        override fun onAdFailedToLoad(error: String) {
            update(false); continueStartup()
        }
    }

    private fun continueStartup() {
        if (!umpDone || !permissionDone || isFinishing || isDestroyed) return
        startAdTimeout()
        if (adShowing) return
        if (adFinished) {
            goMain(); return
        }
        if (!openReady && !guide1Ready && !guide2Ready) return
        var failureReported = false
        val shown = AdMobManager.showGuideAdIfReady(this, object : AdShowListener {
            override fun onAdShowed() = AdLogger.interstitialPresented("guide_startup")
            override fun onAdShowFailed(error: String) {
                failureReported = true; adShowing = false; continueStartup()
            }

            override fun onAdClosed() {
                adShowing = false; finishAds()
            }

            override fun onAdClicked() = Unit
        })
        if (shown && !failureReported) adShowing = true
    }

    private fun startAdTimeout() {
        if (!umpDone || !permissionDone || adFinished || adTimeout != null) return
        val timeoutMs = AdMobManager.getIntersOpenLoadTimeoutMs()
        AdLogger.interstitialLoadRequest("guide_startup_timeout", "${timeoutMs}ms")
        adTimeout = Runnable {
            if (!adShowing) {
                AdLogger.guideAdTimeout(timeoutMs)
            }
            finishAds()
        }.also { handler.postDelayed(it, timeoutMs) }
    }

    private fun finishAds() {
        if (adFinished || adShowing) return; adFinished = true; clearTimeout(); continueStartup()
    }

    private fun clearTimeout() {
        adTimeout?.let(handler::removeCallbacks); adTimeout = null
    }

    private fun goMain() {
        if (isFinishing || isDestroyed) return; if (isPermissionGranted()) DownloadBarLauncher.ensureRunning(
            this
        ); startActivity(Intent(this, MainActivity::class.java)); finish()
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(
            bars.left,
            bars.top,
            bars.right,
            bars.bottom
        ); insets
        }
    }

    override fun onDestroy() {
        splashAnimator?.cancel(); clearTimeout(); handler.removeCallbacksAndMessages(null); super.onDestroy()
    }

    companion object {
        const val GUIDE_LOAD_DELAY_MS = 500L
        const val UMP_PREFS = "ump"
        const val UMP_DONE = "completed"
        const val PERM_PREFS = "notice_permission"
        const val PERM_REQUESTED = "requested"
        const val TAG = "GuideActivity"
        const val EXTRA_HOT_START = "extra_hot_start"

        fun createHotStartIntent(context: android.content.Context): Intent =
            Intent(context, GuideActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_HOT_START, true)
            }
    }
}
