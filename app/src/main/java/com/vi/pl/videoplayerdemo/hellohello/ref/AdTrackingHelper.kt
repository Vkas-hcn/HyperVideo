package com.vi.pl.videoplayerdemo.hellohello.ref

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.flux.tracksdk.utils.UsdFromAdMicros
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.ResponseInfo
import com.vi.pl.videoplayerdemo.BuildConfig
import java.util.Locale.getDefault
import java.util.TimeZone
import java.util.UUID

/**
 * 广告埋点辅助类
 * 用于统一管理广告相关的埋点上报
 */
object AdTrackingHelper {
    private const val TAG = "trackEvent"

    private val revenueLock = Any()
    private const val PREF_NAME = "ad_tracking_prefs"
    private const val KEY_TOTAL_ADS_REVENUE = "total_ads_revenue_001_accumulated"

    /**
     * 广告类型
     */
    object AdType {
        const val BANNER = "banner"
        const val NATIVE = "native"
        const val INTERSTITIAL = "interstitial"
        const val OPEN = "open"
        const val REWARDED = "rewarded"
    }
    fun trackAdRevenueAdjust(
        adValue: AdValue,
        responseInfo: ResponseInfo?,
        adUnitId: String,
    ) {
        runCatching {
            val adRevenue = AdjustAdRevenue("admob_sdk")
            val valueM = if (BuildConfig.DEBUG) {
                6000L
            } else {
                adValue.valueMicros
            }
            val usd: Double = UsdFromAdMicros.toUsd(
                valueM,
                adValue.currencyCode.uppercase(getDefault()),
                null
            )
            if (usd < 0) return
            adRevenue.setRevenue(usd, "USD")
            adRevenue.setAdRevenueNetwork("Admob")
            adRevenue.setAdRevenueUnit(adUnitId)
            Adjust.trackAdRevenue(adRevenue)
            Log.d(
                TAG,
                "trackAdRevenueAdjust usd=$usd unit=$adUnitId network=${responseInfo?.mediationAdapterClassName}"
            )
        }.onFailure {
            Log.e(TAG, "trackAdRevenueAdjust failed unit=$adUnitId", it)
        }
    }

    /**
     * 上报广告收益（AdMob OnPaidEvent，使用 micros）
     */
    fun trackAdImpressionRevenueMicros(
        context: Context,
        adValue: AdValue,
        adType: String,
        posId: String,
    ) {
        val valueM = if (BuildConfig.DEBUG) {
            6000L
        } else {
            adValue.valueMicros
        }
        val originalCurrency = adValue.currencyCode.uppercase(getDefault())
        val revenue: Double = UsdFromAdMicros.toUsd(valueM, originalCurrency, null)
        if (revenue < 0) return
        val currencyCode = "USD"
        trackRevenueEvent(
            event = "ad_impression_revenue",
            revenue = revenue,
            currencyCode = currencyCode,
            adType = adType,
            posId = posId
        )
        trackTotalAdsRevenueIfNeeded(context, revenue)
        Log.d(TAG, "BI Firebase Facebook usd=$revenue adType=$adType")
    }


    /**
     * 各平台互相独立上报：任一平台抛出异常不得影响其余平台，否则排在后面的平台会被整条丢弃。
     */
    fun trackRevenueEvent(
        event: String,
        revenue: Double,
        currencyCode: String = "USD",
        adType: String,
        posId: String
    ) {
        //BI
        runCatching {
            TrackSDK.trackAdRevenue(revenue)
            Log.d(TAG, "trackRevenueEvent BI: $event revenue=$revenue currency=$currencyCode posId=$posId")
        }.onFailure {
            Log.e(TAG, "trackRevenueEvent BI failed: $event", it)
        }

    }


    private fun trackTotalAdsRevenueIfNeeded(
        context: Context,
        revenue: Double,
    ) {
        synchronized(revenueLock) {
            val prefs = context.applicationContext.trackingPrefs()
            val totalRevenue = prefs.getDouble(KEY_TOTAL_ADS_REVENUE, 0.0) + revenue
            if (totalRevenue >= 0.01) {
                trackValueEvent(
                    context = context,
                    event = "total_ads_revenue_001",
                    revenue = totalRevenue,
                    currencyCode = "USD"
                )
                prefs.putDouble(KEY_TOTAL_ADS_REVENUE, 0.0)
            } else {
                prefs.putDouble(KEY_TOTAL_ADS_REVENUE, totalRevenue)
            }
        }
    }





    private fun Context.trackingPrefs(): SharedPreferences {
        return applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun SharedPreferences.putDouble(key: String, value: Double) {
        edit { putLong(key, value.toBits()) }
    }

    private fun SharedPreferences.getDouble(key: String, defaultValue: Double): Double {
        if (!contains(key)) return defaultValue
        return Double.fromBits(getLong(key, defaultValue.toBits()))
    }

    private fun trackValueEvent(
        context: Context,
        event: String,
        revenue: Double,
        currencyCode: String = "USD"
    ) {
        runCatching {
            TrackSDK.track(event, null, TrackPolicy.IMMEDIATE)
            Log.d(TAG, "trackValueEvent: $event revenue=$revenue currency=$currencyCode")
        }.onFailure {
            Log.e(TAG, "trackValueEvent failed: $event", it)
        }
    }


    fun adjustPoint(
        key: String,
        revenue: Double? = null,
        currencyCode: String? = null,
    ) {
        val token = adjustTokenFor(key)
        runCatching {
            val adjustEvent = AdjustEvent(token)
            if (revenue != null && currencyCode != null) {
                adjustEvent.setRevenue(revenue, currencyCode)
            }
            Adjust.trackEvent(adjustEvent)
        }.onFailure {
            Log.e(TAG, "adjustPoint failed: $key", it)
        }
    }

    private fun adjustTokenFor(key: String): String? {
        return when (key) {
            "ad_impression_revenue" -> "4rfcdo"
            "total_ads_revenue_001" -> "g06m0y"
            else -> null
        }
    }
}
