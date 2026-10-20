package com.vi.pl.videoplayerdemo.ads

import org.json.JSONObject

data class AdConfig(
    val enabled: Boolean = false,
    val adsDebugMode: Boolean = false,
    val nativeDailyLimit: Int = 0,
    val interstitialDailyLimit: Int = 0,
    val openDailyLimit: Int = 0,
    val bannerDailyLimit: Int = 0,
    val intersOpenLoadTime: Int = 10000,
    val intersBackConfig: AdTypeConfig? = null,
    val intersGuide1Config: AdTypeConfig? = null,
    val intersGuide2Config: AdTypeConfig? = null,
    val intersHomeConfig: AdTypeConfig? = null,
    val intersBrowserBackConfig: AdTypeConfig? = null,
    val searchIntersBackConfig: AdTypeConfig? = null,
    val barBannerConfig: AdTypeConfig? = null,
    val nativeCommonConfig: AdTypeConfig? = null,
    val homeNativeConfig: AdTypeConfig? = null,
)

data class AdTypeConfig(val enabled: Boolean = false, val adUnits: List<AdUnit> = emptyList())
data class AdUnit(val id: String, val name: String)

enum class AdState { NOT_LOADED, LOADING, LOADED, SHOWING, FAILED, CLOSED }

object AdConfigSerializer {
    fun toJson(config: AdConfig): String = JSONObject().apply {
        put("ad_switch_control", JSONObject().apply {
            put("enable_open_back", config.intersBackConfig?.enabled == true)
            put("enable_home_native", config.homeNativeConfig?.enabled == true)
            put("enable_connect_native", config.nativeCommonConfig?.enabled == true)
            put("enable_inters_guide_1", config.intersGuide1Config?.enabled == true)
            put("enable_inters_guide_2", config.intersGuide2Config?.enabled == true)
            put("enable_inters_home", config.intersHomeConfig?.enabled == true)
            put("enable_inters_back", config.intersBrowserBackConfig?.enabled == true)
            put("enable_search_inters_back", config.searchIntersBackConfig?.enabled == true)
            put("enable_bar_banner", config.barBannerConfig?.enabled == true)
        })
        put("ad_unit_ids", JSONObject().apply {
            put("open_back", joinAdIds(config.intersBackConfig))
            put("home_native", joinAdIds(config.homeNativeConfig))
            put("connect_native", joinAdIds(config.nativeCommonConfig))
            put("inters_guide_1", joinAdIds(config.intersGuide1Config))
            put("inters_guide_2", joinAdIds(config.intersGuide2Config))
            put("inters_home", joinAdIds(config.intersHomeConfig))
            put("inters_back", joinAdIds(config.intersBrowserBackConfig))
            put("search_inters_back", joinAdIds(config.searchIntersBackConfig))
            put("bar_banner", joinAdIds(config.barBannerConfig))
        })
        put("ad_frequency_control", JSONObject().apply {
            put("max_native_display_count", config.nativeDailyLimit)
            put("max_interstitial_display_count", config.interstitialDailyLimit)
            put("max_open_display_count", config.openDailyLimit)
            put("inters_open_loadtime", config.intersOpenLoadTime)
            put("max_banner_display_count", config.bannerDailyLimit)
        })
        put("ad_debug_settings", JSONObject().put("is_debug_mode", config.adsDebugMode))
    }.toString()

    fun fromJson(json: String): AdConfig {
        val root = JSONObject(json)
        val switches = root.optJSONObject("ad_switch_control") ?: JSONObject()
        val ids = root.optJSONObject("ad_unit_ids") ?: JSONObject()
        val frequency = root.optJSONObject("ad_frequency_control") ?: JSONObject()
        val debug = root.optJSONObject("ad_debug_settings") ?: JSONObject()
        val open = switches.optBoolean("enable_open_back")
        val homeNative = switches.optBoolean("enable_home_native")
        val commonNative = switches.optBoolean("enable_connect_native")
        val guide1 = switches.optBoolean("enable_inters_guide_1")
        val guide2 = switches.optBoolean("enable_inters_guide_2")
        val home = switches.optBoolean("enable_inters_home")
        val back = switches.optBoolean("enable_inters_back")
        val searchBack = switches.optBoolean("enable_search_inters_back")
        val banner = switches.optBoolean("enable_bar_banner")
        val interstitialLimit = frequency.optInt("max_interstitial_display_count", 0).coerceAtLeast(0)
        return AdConfig(
            enabled = open || homeNative || commonNative || guide1 || guide2 || home || back || searchBack || banner,
            adsDebugMode = debug.optBoolean("is_debug_mode"),
            nativeDailyLimit = frequency.optInt("max_native_display_count", 0).coerceAtLeast(0),
            interstitialDailyLimit = interstitialLimit,
            openDailyLimit = frequency.optInt("max_open_display_count", interstitialLimit).coerceAtLeast(0),
            bannerDailyLimit = frequency.optInt("max_banner_display_count", 0).coerceAtLeast(0),
            intersOpenLoadTime = frequency.optInt("inters_open_loadtime", 10000).coerceAtLeast(0),
            intersBackConfig = type(open, ids, "open_back"),
            intersGuide1Config = type(guide1, ids, "inters_guide_1"),
            intersGuide2Config = type(guide2, ids, "inters_guide_2"),
            intersHomeConfig = type(home, ids, "inters_home"),
            intersBrowserBackConfig = type(back, ids, "inters_back"),
            searchIntersBackConfig = type(searchBack, ids, "search_inters_back"),
            barBannerConfig = type(banner, ids, "bar_banner"),
            nativeCommonConfig = type(commonNative, ids, "connect_native"),
            homeNativeConfig = type(homeNative, ids, "home_native"),
        )
    }

    private fun type(enabled: Boolean, ids: JSONObject, key: String) =
        AdTypeConfig(enabled, parseCsvAdUnits(ids.optString(key), key))

    private fun joinAdIds(type: AdTypeConfig?): String = type?.adUnits?.joinToString(",") { it.id }.orEmpty()

    private fun parseCsvAdUnits(csv: String, prefix: String): List<AdUnit> = csv.split(",")
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapIndexed { index, id -> AdUnit(id, "${prefix}_${index + 1}") }
}
