package com.vi.pl.videoplayerdemo.ads

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.View
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.ump.UserMessagingPlatform
import com.vi.pl.videoplayerdemo.hellohello.ref.AdTrackingHelper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide AdMob facade. It deliberately has no VPN or network-model dependency. */
object AdMobManager {
    enum class NativePlacement { HOME, CONNECT }
    enum class InterstitialPlacement(val id: String) {
        GUIDE_1("inters_guide_1"), GUIDE_2("inters_guide_2"),
        HOME("inters_home"), BACK("inters_back"), SEARCH_BACK("search_inters_back")
    }

    private const val CACHE_TTL_MS = 3_000_000L
    private const val OPEN_SLOT = "open_back"
    private const val LOAD_FAILED = "Failed to load all ad units"
    private const val NOT_LOADED = "Ad not loaded"
    private const val INVALID_ACTIVITY = "Invalid Activity"
    private val main = Handler(Looper.getMainLooper())
    private val initCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val slots = InterstitialPlacement.entries.associateWith { Slot() }
    private var openAd: AppOpenAd? = null
    private var openLoadedAt = 0L
    private var openLoading = false
    private var openShowing = false
    private var sdkInitialized = false
    private var appForeground = true
    private var appContext: Context? = null
    private var config = AdConfig()
    private var configJson = ""
    private var guideShown = false
    private val retryTasks = mutableMapOf<String, Runnable>()
    private val retryCounts = mutableMapOf<String, Int>()
    private val bannerSlot = BannerAdSlot("bar_banner", main)
    private val bannerObservers = BannerAdSlot.BannerMode.entries.associateWith { CopyOnWriteArrayList<AdLoadListener>() }

    enum class NativeContainerState { HIDDEN, LOADING, DISPLAYED }
    enum class BannerContainerState { HIDDEN, LOADING, DISPLAYED }
    private val nativeSlots = mapOf(
        NativePlacement.HOME to NativeAdSlot("home_native"),
        NativePlacement.CONNECT to NativeAdSlot("connect_native"),
    )

    fun loadNativeAd(context: Context, placement: NativePlacement, listener: NativeAdLoadListener? = null) = onMain {
        val type = if (placement == NativePlacement.HOME) config.homeNativeConfig else config.nativeCommonConfig
        val slot = "native_${placement.name}"
        if (!canLoad(type, listener, slot)) return@onMain
        if (!NativeDailyCapStore.canLoadOrShow(context, placement.name, config.nativeDailyLimit)) {
            listener?.onAdFailedToLoad("Daily display cap reached")
            return@onMain
        }
        nativeSlots.getValue(placement).loadAd(context.applicationContext, placement.name, type!!.adUnits, listener)
    }

    fun isNativeAdLoaded(placement: NativePlacement): Boolean = nativeSlots.getValue(placement).isLoaded()
    fun isNativeAdLoading(placement: NativePlacement): Boolean = nativeSlots.getValue(placement).getState() == AdState.LOADING
    fun getNativeAd(placement: NativePlacement): NativeAd? = nativeSlots.getValue(placement).acquireAdForDisplayAny()
    fun destroyNativeAd(placement: NativePlacement) = nativeSlots.getValue(placement).destroy()
    fun releaseNativeAdContainer(container: ViewGroup, placement: NativePlacement) { container.removeAllViews(); destroyNativeAd(placement) }
    /** 原生广告真实曝光后计数，供 ad_frequency_control.max_native_display_count 频控使用。 */
    fun onNativeAdDisplayed(context: Context, placement: NativePlacement) =
        NativeDailyCapStore.onPresented(context, placement.name, config.nativeDailyLimit)
    fun updateNativeAdContainer(container: ViewGroup, placement: NativePlacement, loadWhenMissing: Boolean = true, preloadAfterDisplay: Boolean = true, forceRefresh: Boolean = false): NativeContainerState {
        if (!isAdEnabled()) { container.visibility = View.GONE; return NativeContainerState.HIDDEN }
        if (container.childCount > 0 && !forceRefresh) return NativeContainerState.DISPLAYED
        val ad = getNativeAd(placement)
        if (ad != null) { container.visibility = View.VISIBLE; return NativeContainerState.DISPLAYED }
        container.visibility = View.VISIBLE
        if (loadWhenMissing && !isNativeAdLoading(placement)) loadNativeAd(container.context, placement)
        return NativeContainerState.LOADING
    }

    fun bannerModeRegular() = BannerAdSlot.BannerMode.REGULAR
    fun bannerModeCollapsible() = BannerAdSlot.BannerMode.COLLAPSIBLE
    fun loadBanner(context: Context, mode: BannerAdSlot.BannerMode = bannerModeRegular(), listener: AdLoadListener? = null, attachTo: ViewGroup? = null) = onMain {
        val type = config.barBannerConfig
        if (!canLoad(type, listener, "bar_banner")) return@onMain
        if (!InterstitialDailyCapStore.canLoadOrShow(context, config.bannerDailyLimit, "bar_banner")) return@onMain
        bannerSlot.load(context.applicationContext, type!!.adUnits, mode, object : AdLoadListener {
            override fun onAdLoaded() { listener?.onAdLoaded(); bannerObservers.getValue(mode).forEach { it.onAdLoaded() } }
            override fun onAdFailedToLoad(error: String) { listener?.onAdFailedToLoad(error); bannerObservers.getValue(mode).forEach { it.onAdFailedToLoad(error) } }
        }, attachTo)
    }
    fun hasValidBannerCache(mode: BannerAdSlot.BannerMode) = bannerSlot.hasValidCache(mode, System.currentTimeMillis(), CACHE_TTL_MS)
    fun isBannerLoading(mode: BannerAdSlot.BannerMode) = bannerSlot.isLoading(mode)
    fun addBannerLoadObserver(mode: BannerAdSlot.BannerMode, listener: AdLoadListener) { bannerObservers.getValue(mode).add(listener) }
    fun removeBannerLoadObserver(mode: BannerAdSlot.BannerMode, listener: AdLoadListener) { bannerObservers.getValue(mode).remove(listener) }
    fun updateBannerContainer(container: ViewGroup, mode: BannerAdSlot.BannerMode): BannerContainerState {
        if (!isAdEnabled()) { bannerSlot.hide(container); return BannerContainerState.HIDDEN }
        if (bannerSlot.isDisplayedIn(container)) return BannerContainerState.DISPLAYED
        val record = bannerSlot.acquireForDisplay(mode, System.currentTimeMillis(), CACHE_TTL_MS) ?: return BannerContainerState.LOADING
        bannerSlot.attach(container, record); return BannerContainerState.DISPLAYED
    }
    fun releaseBannerHost(container: ViewGroup) = bannerSlot.detachFrom(container)
    fun hideBanner(container: ViewGroup) = bannerSlot.hide(container)
    fun isBannerDisplayedInMode(container: ViewGroup, mode: BannerAdSlot.BannerMode) = bannerSlot.isDisplayedIn(container) && bannerSlot.isDisplayedMode(mode)
    fun discardBannerCache(mode: BannerAdSlot.BannerMode) = bannerSlot.discardCache(mode)

    private data class Slot(var ad: InterstitialAd? = null, var loadedAt: Long = 0L, var loading: Boolean = false, var showing: Boolean = false)

    fun initialize(context: Context, onComplete: (Boolean) -> Unit = {}) {
        onMain {
            if (sdkInitialized) return@onMain onComplete(true)
            initCallbacks += onComplete
            if (appContext != null) return@onMain
            appContext = context.applicationContext
            refreshConfig()
            runCatching {
                MobileAds.initialize(appContext!!) {
                    onMain {
                        sdkInitialized = true
                        AdLogger.sdkInitialized()
                        initCallbacks.toList().also { initCallbacks.clear() }.forEach { runCatching { it(true) } }
                    }
                }
            }.onFailure {
                AdLogger.sdkInitFailed(it)
                initCallbacks.toList().also { initCallbacks.clear() }.forEach { runCatching { it(false) } }
            }
        }
    }

    fun runWhenInitialized(callback: (Boolean) -> Unit) = onMain {
        if (sdkInitialized) callback(true) else initialize(appContext ?: return@onMain, callback)
    }

    fun refreshConfig(json: String? = AdConfigStore.get()) = onMain {
        val raw = json.orEmpty()
        if (raw == configJson) return@onMain
        val parsed = runCatching { if (raw.isBlank()) AdConfig() else AdConfigSerializer.fromJson(raw) }
            .onFailure { AdLogger.configInvalid(it) }.getOrDefault(AdConfig())
        val old = config
        configJson = raw
        config = parsed
        AdLogger.configRefreshed(parsed.enabled)
        if (old.intersBackConfig != parsed.intersBackConfig) { openAd = null; openLoadedAt = 0L; openLoading = false; clearRetry(OPEN_SLOT) }
        InterstitialPlacement.entries.forEach { placement ->
            if (old.configFor(placement) != parsed.configFor(placement)) {
                slots.getValue(placement).apply { ad = null; loadedAt = 0L; loading = false; showing = false }
                clearRetry(placement.id)
            }
        }
        if (old.nativeCommonConfig != parsed.nativeCommonConfig) nativeSlots[NativePlacement.CONNECT]?.clear()
        if (old.homeNativeConfig != parsed.homeNativeConfig) nativeSlots[NativePlacement.HOME]?.clear()
        if (old.barBannerConfig != parsed.barBannerConfig) bannerSlot.clear()
    }

    fun updateAppForegroundState(isForeground: Boolean) { appForeground = isForeground }
    fun isAppInForeground(): Boolean = appForeground
    fun isAdEnabled(): Boolean = sdkInitialized && config.enabled
    fun isAnyAdShowing(): Boolean = openShowing || slots.values.any { it.showing }
    fun getIntersOpenLoadTimeoutMs(): Long = config.intersOpenLoadTime.coerceAtLeast(0).toLong()
    fun resetGuideShowState() = synchronized(this) { guideShown = false }

    /**
     * 热启动兜底：广告 Activity 已被 finish 但 SDK 未在超时内回调 onAdDismissed 时，
     * 强制清掉“正在展示”状态，允许新的启动广告展示。仅应在确认已关闭广告 Activity 后调用。
     */
    fun forceClearFullscreenShowingState() = synchronized(this) {
        openShowing = false
        slots.values.forEach { it.showing = false }
    }

    fun loadOpenAd(context: Context, listener: AdLoadListener? = null) = onMain {
        if (!canLoad(config.intersBackConfig, listener, OPEN_SLOT)) return@onMain
        if (fullscreenCapBlocked(context, OPEN_SLOT)) return@onMain
        if (validOpen()) {
            AdLogger.interstitialCacheHit(OPEN_SLOT)
            listener?.onAdLoaded()
            return@onMain
        }
        if (openLoading) {
            AdLogger.loadSkipped(OPEN_SLOT, "already_loading")
            return@onMain
        }
        AdLogger.interstitialLoadRequest(OPEN_SLOT)
        openLoading = true
        loadOpenWaterfall(context.applicationContext, 0, listener)
    }

    private fun loadOpenWaterfall(context: Context, index: Int, listener: AdLoadListener?) {
        val units = config.intersBackConfig?.adUnits.orEmpty()
        if (index >= units.size) return failOpen(listener)
        val unit = units[index]
        AdLogger.interstitialWaterfallTry(index, unit.name, unit.id, OPEN_SLOT)
        runCatching {
            AppOpenAd.load(context, unit.id, adRequest(), object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    onMain {
                        ad.onPaidEventListener = paidListener(
                            context, unit.id, AdTrackingHelper.AdType.OPEN, OPEN_SLOT,
                        ) { ad.responseInfo }
                        openAd = ad
                        openLoadedAt = System.currentTimeMillis()
                        openLoading = false
                        clearRetry(OPEN_SLOT)
                        AdLogger.interstitialLoaded(unit.name, unit.id, OPEN_SLOT)
                        listener?.onAdLoaded()
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) { onMain { AdLogger.interstitialLoadFailed(unit.name, OPEN_SLOT, error.message); loadOpenWaterfall(context, index + 1, listener) } }
            })
        }.onFailure { error ->
            AdLogger.interstitialLoadFailed(unit.name, OPEN_SLOT, error.message ?: "SDK exception")
            onMain { loadOpenWaterfall(context, index + 1, listener) }
        }
    }

    private fun failOpen(listener: AdLoadListener?) {
        openLoading = false
        openAd = null
        AdLogger.interstitialAllFailed(OPEN_SLOT)
        listener?.onAdFailedToLoad(LOAD_FAILED)
        scheduleRetry(OPEN_SLOT) { appContext?.let { loadOpenAd(it) } }
    }
    fun isOpenAdLoaded(): Boolean = validOpen()

    fun showOpenAd(activity: Activity, listener: AdShowListener? = null): Boolean {
        AdLogger.interstitialShowRequest(OPEN_SLOT, if (validOpen()) "LOADED" else "NOT_LOADED", null)
        if (!validOpen() || !canShow(activity, listener, OPEN_SLOT) || fullscreenCapBlocked(activity, OPEN_SLOT)) return false
        val ad = openAd ?: return false
        openShowing = true; openAd = null; openLoadedAt = 0L
        ad.fullScreenContentCallback = callback(
            OPEN_SLOT, listener,
            onShow = { openShowing = true; FullscreenLimitStore.recordFullscreenImpression(activity) },
            onClose = {
                openShowing = false
                FullscreenLimitStore.recordFullscreenClosed(activity)
                AdLogger.interstitialAutoPreload(OPEN_SLOT)
                loadOpenAd(activity.applicationContext)
            },
            onFail = { openShowing = false; FullscreenLimitStore.recordFullscreenClosed(activity) },
        )
        return runCatching { ad.show(activity); true }.getOrElse { openShowing = false; AdLogger.adShowFailed(OPEN_SLOT, it.message ?: NOT_LOADED); listener?.onAdShowFailed(it.message ?: NOT_LOADED); false }
    }

    fun loadInterstitial(context: Context, placement: InterstitialPlacement, listener: AdLoadListener? = null) = onMain {
        val slot = slots.getValue(placement)
        if (!canLoad(configFor(placement), listener, placement.id)) return@onMain
        if (fullscreenCapBlocked(context, placement.id)) return@onMain
        if (valid(slot)) {
            AdLogger.interstitialCacheHit(placement.id)
            listener?.onAdLoaded()
            return@onMain
        }
        if (slot.loading) {
            AdLogger.loadSkipped(placement.id, "already_loading")
            return@onMain
        }
        AdLogger.interstitialLoadRequest(placement.id)
        slot.loading = true
        loadInterstitialWaterfall(context.applicationContext, placement, 0, listener)
    }

    private fun loadInterstitialWaterfall(context: Context, placement: InterstitialPlacement, index: Int, listener: AdLoadListener?) {
        val units = configFor(placement)?.adUnits.orEmpty()
        val slot = slots.getValue(placement)
        if (index >= units.size) {
            slot.loading = false
            AdLogger.interstitialAllFailed(placement.id)
            listener?.onAdFailedToLoad(LOAD_FAILED)
            scheduleRetry(placement.id) { appContext?.let { loadInterstitial(it, placement) } }
            return
        }
        val unit = units[index]
        AdLogger.interstitialWaterfallTry(index, unit.name, unit.id, placement.id)
        runCatching {
            InterstitialAd.load(context, unit.id, adRequest(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    onMain {
                        if (placement == InterstitialPlacement.GUIDE_1 || placement == InterstitialPlacement.GUIDE_2) {
                            ad.onPaidEventListener = paidListener(
                                context, unit.id, AdTrackingHelper.AdType.INTERSTITIAL, placement.id,
                            ) { ad.responseInfo }
                        }
                        slot.ad = ad
                        slot.loadedAt = System.currentTimeMillis()
                        slot.loading = false
                        clearRetry(placement.id)
                        AdLogger.interstitialLoaded(unit.name, unit.id, placement.id)
                        listener?.onAdLoaded()
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) { onMain { AdLogger.interstitialLoadFailed(unit.name, placement.id, error.message); loadInterstitialWaterfall(context, placement, index + 1, listener) } }
            })
        }.onFailure { error ->
            AdLogger.interstitialLoadFailed(unit.name, placement.id, error.message ?: "SDK exception")
            onMain { loadInterstitialWaterfall(context, placement, index + 1, listener) }
        }
    }

    fun isInterstitialLoaded(placement: InterstitialPlacement): Boolean = valid(slots.getValue(placement))
    fun showInterstitial(activity: Activity, placement: InterstitialPlacement, listener: AdShowListener? = null): Boolean {
        val slot = slots.getValue(placement); val ad = slot.ad
        AdLogger.interstitialShowRequest(placement.id, if (valid(slot)) "LOADED" else "NOT_LOADED", null)
        if (!valid(slot) || ad == null || !canShow(activity, listener, placement.id) || fullscreenCapBlocked(activity, placement.id)) return false
        slot.ad = null; slot.loadedAt = 0L; slot.showing = true
        ad.fullScreenContentCallback = callback(
            placement.id, listener,
            onShow = { FullscreenLimitStore.recordFullscreenImpression(activity) },
            onClose = {
                slot.showing = false
                FullscreenLimitStore.recordFullscreenClosed(activity)
                AdLogger.interstitialAutoPreload(placement.id)
                loadInterstitial(activity.applicationContext, placement)
            },
            onFail = { slot.showing = false; FullscreenLimitStore.recordFullscreenClosed(activity) },
        )
        return runCatching { ad.show(activity); true }.getOrElse { slot.showing = false; AdLogger.adShowFailed(placement.id, it.message ?: NOT_LOADED); listener?.onAdShowFailed(it.message ?: NOT_LOADED); false }
    }

    fun showGuideAdIfReady(activity: Activity, listener: AdShowListener): Boolean = synchronized(this) {
        if (guideShown) return false
        val shown = when { validOpen() -> showOpenAd(activity, listener); isInterstitialLoaded(InterstitialPlacement.GUIDE_1) -> showInterstitial(activity, InterstitialPlacement.GUIDE_1, listener); isInterstitialLoaded(InterstitialPlacement.GUIDE_2) -> showInterstitial(activity, InterstitialPlacement.GUIDE_2, listener); else -> false }
        if (shown) guideShown = true
        shown
    }

    private fun configFor(p: InterstitialPlacement) = when (p) {
        InterstitialPlacement.GUIDE_1 -> config.intersGuide1Config
        InterstitialPlacement.GUIDE_2 -> config.intersGuide2Config
        InterstitialPlacement.HOME -> config.intersHomeConfig
        InterstitialPlacement.BACK -> config.intersBrowserBackConfig
        InterstitialPlacement.SEARCH_BACK -> config.searchIntersBackConfig
    }
    private fun canLoad(type: AdTypeConfig?, listener: AdLoadListener?, slot: String): Boolean {
        if (!sdkInitialized) { AdLogger.loadBlocked(slot, "SDK not initialized"); listener?.onAdFailedToLoad("SDK not initialized"); return false }
        if (!config.enabled || type?.enabled != true || type.adUnits.isEmpty()) { AdLogger.loadBlocked(slot, "Ad disabled by config"); listener?.onAdFailedToLoad("Ad disabled by config"); return false }
        return true
    }
    private fun canLoad(type: AdTypeConfig?, listener: NativeAdLoadListener?, slot: String): Boolean {
        if (!sdkInitialized) { AdLogger.loadBlocked(slot, "SDK not initialized"); listener?.onAdFailedToLoad("SDK not initialized"); return false }
        if (!config.enabled || type?.enabled != true || type.adUnits.isEmpty()) { AdLogger.loadBlocked(slot, "Ad disabled by config"); listener?.onAdFailedToLoad("Ad disabled by config"); return false }
        return true
    }
    private fun canShow(activity: Activity, listener: AdShowListener?, slot: String): Boolean {
        if (!appForeground) {
            AdLogger.interstitialShowBlocked("app_background", slot)
            return false
        }
        if (fullscreenIntervalRemainingMillis() > 0L) {
            AdLogger.interstitialShowBlocked("fullscreen_min_interval", slot)
            return false
        }
        if (isAnyAdShowing()) {
            AdLogger.interstitialShowBlocked("another_ad_showing", slot)
            return false
        }
        if (activity.isFinishing || activity.isDestroyed) {
            AdLogger.interstitialShowBlocked("invalid_activity", slot)
            return false
        }
        return true
    }
    private fun validOpen() = openAd != null && System.currentTimeMillis() - openLoadedAt < CACHE_TTL_MS
    private fun valid(slot: Slot) = slot.ad != null && System.currentTimeMillis() - slot.loadedAt < CACHE_TTL_MS
    private fun AdConfig.configFor(placement: InterstitialPlacement) = when (placement) {
        InterstitialPlacement.GUIDE_1 -> intersGuide1Config
        InterstitialPlacement.GUIDE_2 -> intersGuide2Config
        InterstitialPlacement.HOME -> intersHomeConfig
        InterstitialPlacement.BACK -> intersBrowserBackConfig
        InterstitialPlacement.SEARCH_BACK -> searchIntersBackConfig
    }
    private fun callback(slot: String, listener: AdShowListener?, onShow: () -> Unit, onClose: () -> Unit, onFail: (String) -> Unit) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() { AdLogger.interstitialPresented(slot); onShow(); listener?.onAdShowed() }
        override fun onAdClicked() { AdLogger.adClicked(slot); listener?.onAdClicked() }
        override fun onAdDismissedFullScreenContent() { AdLogger.interstitialClosed(slot); onClose(); listener?.onAdClosed() }
        override fun onAdFailedToShowFullScreenContent(error: AdError) { AdLogger.adShowFailed(slot, error.message); onFail(error.message); listener?.onAdShowFailed(error.message) }
    }

    private fun paidListener(
        context: Context,
        adUnitId: String,
        adType: String,
        posId: String,
        responseInfo: () -> ResponseInfo?,
    ) = OnPaidEventListener { adValue: AdValue ->
//        AdTrackingHelper.trackAdRevenueAdjust(adValue, responseInfo(), adUnitId)
        runCatching {
            AdTrackingHelper.trackAdImpressionRevenueMicros(
                context.applicationContext, adValue, adType, posId,
            )
        }
    }

    /**
     * 启动页关闭后的 guide1/2 补货。
     *
     * 必须使用 AdMobManager 的进程级 Handler：GuideActivity 关闭后通常会在 1 秒内 finish，
     * 其 onDestroy 会清空 Activity Handler，导致原来的延迟补货 Runnable 被取消。
     */
    fun preloadGuideInterstitials(delayMs: Long = 0L) {
        main.postDelayed({
            val context = appContext ?: return@postDelayed
            loadInterstitial(context, InterstitialPlacement.GUIDE_1)
            loadInterstitial(context, InterstitialPlacement.GUIDE_2)
        }, delayMs.coerceAtLeast(0L))
    }

    /** 启动页预热：首帧前即可发起开屏加载，不等待 UMP 完成（对齐 Efile warmupSplashAd）。 */
    fun warmupStartupOpen() = onMain {
        runWhenInitialized { initialized ->
            if (initialized) appContext?.let { loadOpenAd(it) }
        }
    }

    /** 热启动三路缓存查询：返回 开屏→GUIDE_1→GUIDE_2 中第一份有效缓存（未达共享上限）的 slot，无则 null。 */
    fun startupCachedSlot(): String? {
        if (fullscreenCapReached()) return null
        return when {
            validOpen() -> OPEN_SLOT
            valid(slots.getValue(InterstitialPlacement.GUIDE_1)) -> InterstitialPlacement.GUIDE_1.id
            valid(slots.getValue(InterstitialPlacement.GUIDE_2)) -> InterstitialPlacement.GUIDE_2.id
            else -> null
        }
    }

    fun fullscreenCapReached(): Boolean =
        appContext?.let { FullscreenLimitStore.isFullscreenReached(it, config.fullscreenDailyLimit) } ?: false

    /** 距下一次允许展示全屏还差多少毫秒；0 表示已满足间隔。 */
    fun fullscreenIntervalRemainingMillis(): Long {
        val ctx = appContext ?: return 0L
        return FullscreenLimitStore.fullscreenIntervalRemainingMillis(
            ctx, System.currentTimeMillis(), config.fullscreenMinIntervalMs,
        )
    }

    private fun fullscreenCapBlocked(context: Context?, slot: String): Boolean {
        if (context == null) return false
        if (!FullscreenLimitStore.isFullscreenReached(context, config.fullscreenDailyLimit)) return false
        AdLogger.capBlocked(slot, FullscreenLimitStore.getTodayCount(context), config.fullscreenDailyLimit)
        return true
    }

    /** 加载失败按配置的重试序列（30/60/120s）最多重试 3 次；成功或配置变更时取消。 */
    private fun scheduleRetry(slot: String, reload: () -> Unit) {
        val delays = config.adRetryDelaysMs
        val attempt = retryCounts[slot] ?: 0
        if (attempt >= delays.size) {
            retryCounts.remove(slot)
            AdLogger.adRetryGivenUp(slot)
            return
        }
        cancelRetryTask(slot)
        val task = Runnable {
            retryTasks.remove(slot)
            if (!isAdEnabled()) return@Runnable
            reload()
        }
        retryTasks[slot] = task
        main.postDelayed(task, delays[attempt])
        retryCounts[slot] = attempt + 1
        AdLogger.adRetryScheduled(slot, attempt + 1, delays[attempt])
    }

    private fun cancelRetryTask(slot: String) {
        retryTasks.remove(slot)?.let(main::removeCallbacks)
    }

    private fun clearRetry(slot: String) {
        retryCounts.remove(slot)
        cancelRetryTask(slot)
    }

    /** UMP 未授权时按非个性化请求加载（对齐 Efile applyNonPersonalizedOnly）。 */
    private fun adRequest(): AdRequest {
        val context = appContext ?: return AdRequest.Builder().build()
        val canRequestAds = runCatching {
            UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        }.getOrDefault(true)
        if (canRequestAds) return AdRequest.Builder().build()
        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply { putString("npa", "1") })
            .build()
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
}
