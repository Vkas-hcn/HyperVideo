package com.vi.pl.videoplayerdemo.ads

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.ump.UserMessagingPlatform
import com.vi.pl.videoplayerdemo.hellohello.ref.AdTrackingHelper

/**
 * 原生广告位管理器
 * 每个广告位独立管理自己的加载状态和缓存
 */
class NativeAdSlot(
    private val slotName: String,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    companion object {
        private const val ERROR_ALL_NATIVE_AD_UNITS_FAILED = "Failed to load all native ad units"
        private const val AD_VALUE_MICROS_BASE = 1_000_000.0
    }

    @Volatile
    private var adState = AdState.NOT_LOADED
    private val cache = IpBoundSingleCache<NativeAd>() // next preloaded ad
    private var displayedWrapper: AdWrapper<NativeAd>? = null // current displayed ad
    @Volatile
    private var loadToken: Long = 0L
    @Volatile
    private var currentIndex = 0
    @Volatile
    private var refreshRunnable: Runnable? = null
    @Volatile
    private var currentRequestStartMs: Long = 0L

    @Volatile
    private var displayedShowStartMs: Long = 0L

    fun getState(): AdState = adState

    fun isLoadedForIp(ipKey: String): Boolean {
        return adState == AdState.LOADED && cache.isLoadedForIp(ipKey)
    }

    fun isLoaded(): Boolean = adState == AdState.LOADED && cache.getWrapper() != null

    fun getAdIfIpMatch(ipKey: String): NativeAd? {
        return cache.getIfIpMatch(ipKey)
    }

    fun getAnyAd(): NativeAd? = cache.getWrapper()?.ad

    /**
     * 获取可展示广告（从预加载缓存取出并转移为当前展示）
     */
    fun acquireAdForDisplay(ipKey: String): NativeAd? {
        val wrapper = cache.getWrapper() ?: return null
        displayedWrapper = wrapper
        cache.clear()
        adState = AdState.NOT_LOADED
        return wrapper.ad
    }

    fun getLoadedIp(): String? {
        return cache.getWrapper()?.slotId
    }

    fun getDisplayedIp(): String? {
        return displayedWrapper?.slotId
    }

    fun getPreloadedWrapper(): AdWrapper<NativeAd>? = cache.getWrapper()

    fun hasValidPreloadedAd(ipKey: String, nowMs: Long, ttlMs: Long): Boolean {
        return adState == AdState.LOADED && cache.isValid(ipKey, nowMs, ttlMs)
    }

    fun hasValidPreloadedAd(nowMs: Long, ttlMs: Long): Boolean {
        val wrapper = cache.getWrapper() ?: return false
        return adState == AdState.LOADED && nowMs - wrapper.loadTime < ttlMs
    }

    fun acquireAdForDisplayAny(): NativeAd? {
        val wrapper = cache.getWrapper() ?: return null
        if (displayedWrapper?.ad !== wrapper.ad) {
            displayedWrapper?.ad?.destroy()
        }
        displayedWrapper = wrapper
        cache.clear()
        // 缓存已取出，状态回到未加载，避免 LOADED+空缓存被误判为可展示
        adState = AdState.NOT_LOADED
        return wrapper.ad
    }

    fun loadAd(
        context: Context,
        ipKey: String,
        units: List<AdUnit>,
        listener: NativeAdLoadListener?,
        onFinished: (() -> Unit)? = null
    ) {
        AdLogger.nativeLoadRequest(slotName, ipKey, adState.toString())
        val token = ++loadToken
        adState = AdState.LOADING
        currentIndex = 0
        loadWaterfall(context, ipKey, units, token, listener, onFinished)
    }

    private fun loadWaterfall(
        context: Context,
        ipKey: String,
        units: List<AdUnit>,
        token: Long,
        listener: NativeAdLoadListener?,
        onFinished: (() -> Unit)?
    ) {
        if (currentIndex >= units.size) {
            adState = AdState.FAILED
            cache.clear()
            AdLogger.nativeAllFailed(slotName, ipKey)
            onFinished?.invoke()
            postMain { listener?.onAdFailedToLoad(ERROR_ALL_NATIVE_AD_UNITS_FAILED) }
            return
        }
        val unit = units[currentIndex++]
        val adUnitId = unit.id
        currentRequestStartMs = System.currentTimeMillis()
        AdLogger.nativeWaterfallTry(slotName, currentIndex - 1, unit.name, adUnitId, ipKey)

        val loader = com.google.android.gms.ads.AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                if (token != loadToken) return@forNativeAd

                // 设置广告收益监听
                ad.setOnPaidEventListener { adValue ->
//                    AdTrackingHelper.trackAdRevenueAdjust(adValue, ad.responseInfo, adUnitId)
                    runCatching {
                        AdTrackingHelper.trackAdImpressionRevenueMicros(
                            context,
                            adValue,
                            AdTrackingHelper.AdType.NATIVE,
                            slotName
                        )
                    }.onFailure {
                        AdLogger.loadBlocked("native_$slotName", "paid value upload failed: ${it.message}")
                    }
                }
                
                cache.getWrapper()?.ad?.destroy()
                cache.put(
                    ad = ad,
                    slotId = slotName,
                    loadTime = System.currentTimeMillis(),
                )
                adState = AdState.LOADED

                AdLogger.nativeLoaded(slotName, unit.name, adUnitId, ipKey)
                onFinished?.invoke()
                postMain { listener?.onNativeAdLoaded(ad) }
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdImpression() {
                    displayedShowStartMs = System.currentTimeMillis()
                    AdLogger.nativePresented(slotName)
                }

                override fun onAdClicked() {
                    AdLogger.adClicked(slotName)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // 记录详细的错误信息，包括设备信息
                    val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                            "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

                    AdLogger.nativeLoadFailed(
                        slotName, 
                        unit.name, 
                        ipKey, 
                        "${error.message} (code: ${error.code}, domain: ${error.domain}) | ${error.cause?.message ?: ""} | $deviceInfo"
                    )
                    loadWaterfall(
                        context,
                        ipKey,
                        units,
                        token,
                        listener,
                        onFinished
                    )
                }
            })
            .build()
        loader.loadAd(nativeRequest(context))
    }

    fun destroy() {
        val wasLoading = adState == AdState.LOADING
        refreshRunnable?.let { mainHandler.removeCallbacks(it) }
        refreshRunnable = null
        cache.getWrapper()?.ad?.destroy()
        displayedWrapper?.ad?.destroy()
        cache.clear()
        displayedWrapper = null
        adState = AdState.NOT_LOADED
        // 不取消在途 loadToken：槽位是进程级共享，release 可能来自旧页面，
        // 而新页面刚发起的加载仍需完成；这里只清理当前缓存/展示。
        AdLogger.nativeDestroyed(slotName, wasLoading)
    }

    fun clear() {
        loadToken++
        cache.getWrapper()?.ad?.destroy()
        cache.clear()
        adState = AdState.NOT_LOADED
    }

    fun destroyDisplayedAd() {

        displayedWrapper?.ad?.destroy()
        displayedWrapper = null
        displayedShowStartMs = 0L
    }

    /** UMP 未授权时按非个性化请求加载（对齐 Efile applyNonPersonalizedOnly）。 */
    private fun nativeRequest(context: Context): AdRequest {
        val nonPersonalized = runCatching {
            !UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        }.getOrDefault(false)
        if (!nonPersonalized) return AdRequest.Builder().build()
        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply { putString("npa", "1") })
            .build()
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
