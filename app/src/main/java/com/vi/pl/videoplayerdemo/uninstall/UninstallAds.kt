package com.vi.pl.videoplayerdemo.uninstall

import android.app.Activity
import com.vi.pl.videoplayerdemo.ads.AdMobManager
import com.vi.pl.videoplayerdemo.ads.AdShowListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 卸载挽留页插屏选择/展示：复用现有 AdMobManager，不新建广告栈。
 * 先预加载 guide1/guide2；点击时只展示已有缓存，不等待网络。
 */
internal object UninstallAds {

    fun preload(activity: Activity) {
        AdMobManager.runWhenInitialized {
            if (activity.isFinishing || activity.isDestroyed) return@runWhenInitialized
            AdMobManager.loadInterstitial(activity, AdMobManager.InterstitialPlacement.GUIDE_1)
            AdMobManager.loadInterstitial(activity, AdMobManager.InterstitialPlacement.GUIDE_2)
        }
    }

    /** 优先 guide1，其次 guide2；都没有返回 null。 */
    fun pick(
        guide1Loaded: Boolean,
        guide2Loaded: Boolean,
    ): AdMobManager.InterstitialPlacement? = when {
        guide1Loaded -> AdMobManager.InterstitialPlacement.GUIDE_1
        guide2Loaded -> AdMobManager.InterstitialPlacement.GUIDE_2
        else -> null
    }

    /** 只展示缓存；无缓存/展示被拦截时立即回调，回调只触发一次。 */
    fun showCached(activity: Activity, onDone: () -> Unit) {
        val placement = pick(
            AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_1),
            AdMobManager.isInterstitialLoaded(AdMobManager.InterstitialPlacement.GUIDE_2),
        )
        if (placement == null) {
            onDone()
            return
        }
        val done = AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) onDone() }
        val shown = AdMobManager.showInterstitial(
            activity,
            placement,
            object : AdShowListener {
                override fun onAdShowed() = Unit
                override fun onAdShowFailed(error: String) = finish()
                override fun onAdClosed() = finish()
                override fun onAdClicked() = Unit
            },
        )
        if (!shown) finish()
    }
}
