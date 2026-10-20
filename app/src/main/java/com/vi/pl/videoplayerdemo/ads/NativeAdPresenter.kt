package com.vi.pl.videoplayerdemo.ads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.gms.ads.nativead.AdChoicesView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.vi.pl.videoplayerdemo.R

/**
 * 单个原生广告位的展示器：把 [AdMobManager] 预加载好的广告绑定到宿主容器。
 *
 * 约束：
 * - 同一个 [NativeAd] 实例只绑定一次，AdMob 也要求一个实例同一时刻只能挂在一个 NativeAdView 上；
 * - 宿主容器同一时刻最多展示一条广告，重复/迟到的加载回调直接丢弃；
 * - [release] 之后到达的回调全部忽略，并销毁当前实例，避免亮屏重复曝光。
 */
class NativeAdPresenter(
    private val placement: AdMobManager.NativePlacement,
    private val host: ViewGroup,
) {
    private var displayedAd: NativeAd? = null
    private var displayedView: NativeAdView? = null
    private var loading = false
    private var released = false
    private var loadToken = 0

    /** 有缓存就展示；没有则按配置请求一次加载。可重复调用，不会重复绑定。 */
    fun show() {
        if (released) return
        if (!AdMobManager.isAdEnabled()) {
            hide()
            return
        }
        render(loadIfMissing = true)
    }

    /** 释放宿主时调用：销毁当前实例并丢弃在途回调。 */
    fun release() {
        if (released) return
        released = true
        loadToken++
        loading = false
        host.removeAllViews()
        displayedView = null
        displayedAd = null
        host.visibility = View.GONE
        AdMobManager.destroyNativeAd(placement)
    }

    private fun render(loadIfMissing: Boolean) {
        if (released) return
        val current = displayedAd
        val view = displayedView
        // 已在展示同一条广告，重复回调直接跳过，避免二次曝光。
        if (current != null && view != null && view.parent === host) return

        val ad = AdMobManager.getNativeAd(placement)
        if (ad == null) {
            hide()
            if (loadIfMissing) load()
            return
        }
        bind(ad)
    }

    private fun bind(ad: NativeAd) {
        if (displayedAd === ad) return

        val view = LayoutInflater.from(host.context)
            .inflate(R.layout.item_home_native_ad, host, false) as NativeAdView

        val iconView = view.findViewById<ImageView>(R.id.ad_app_icon)
        val icon = ad.icon?.drawable
        if (icon != null) {
            iconView.setImageDrawable(icon)
            view.setIconView(iconView)
        } else {
            iconView.visibility = View.GONE
        }

        val headlineView = view.findViewById<TextView>(R.id.ad_headline)
        val headline = ad.headline
        if (!headline.isNullOrBlank()) {
            headlineView.text = headline
            view.setHeadlineView(headlineView)
        } else {
            headlineView.visibility = View.GONE
        }

        val bodyView = view.findViewById<TextView>(R.id.ad_body)
        val body = ad.body
        if (!body.isNullOrBlank()) {
            bodyView.text = body
            view.setBodyView(bodyView)
        } else {
            bodyView.visibility = View.GONE
        }

        val ctaView = view.findViewById<TextView>(R.id.ad_call_to_action)
        val callToAction = ad.callToAction
        if (!callToAction.isNullOrBlank()) {
            ctaView.text = callToAction
            view.setCallToActionView(ctaView)
        } else {
            ctaView.visibility = View.GONE
        }

        // AdChoices 图标由 SDK 渲染到右上角占位，不注册会被判为素材未完整展示。
        val adChoicesView = view.findViewById<AdChoicesView>(R.id.ad_choices)
        view.setAdChoicesView(adChoicesView)

        // 旧实例在广告位取新实例时已销毁，这里只清理视图引用，保证同一时刻只有一条有效展示。
        if (displayedAd != null) {
            host.removeAllViews()
            displayedAd = null
            displayedView = null
        }
        host.addView(view)
        host.visibility = View.VISIBLE
        displayedAd = ad
        displayedView = view
        view.setNativeAd(ad)
        AdMobManager.onNativeAdDisplayed(host.context, placement)
    }

    private fun load() {
        if (released || loading || AdMobManager.isNativeAdLoading(placement)) return
        loading = true
        val token = ++loadToken
        AdMobManager.runWhenInitialized { initialized ->
            if (released || token != loadToken) return@runWhenInitialized
            if (!initialized) {
                loading = false
                return@runWhenInitialized
            }
            AdMobManager.loadNativeAd(host.context, placement, object : NativeAdLoadListener {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    if (released || token != loadToken) return
                    loading = false
                    // 统一从广告位缓存取实例，避免回调参数与缓存各持一份导致重复绑定。
                    render(loadIfMissing = false)
                }

                override fun onAdFailedToLoad(error: String) {
                    if (released || token != loadToken) return
                    loading = false
                }
            })
        }
    }

    private fun hide() {
        if (displayedAd != null) return
        host.visibility = View.GONE
    }
}
