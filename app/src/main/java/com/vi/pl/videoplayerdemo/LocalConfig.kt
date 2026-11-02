package com.vi.pl.videoplayerdemo

import android.util.Log
import com.vi.pl.videoplayerdemo.ads.AdConfigStore
import com.vi.pl.videoplayerdemo.hellohello.NoticeConfigStore

/**
 * 本地硬编码的下发配置，替代已移除的 `/api/dispatch` 请求。
 *
 * 结构保持与服务端 dispatch 响应一致（顶层 `notice` + `ad_config`），
 * 因此 [NoticeConfigStore] / [AdConfigStore] 的解析逻辑无需改动。
 */
object LocalConfig {
    private const val TAG = "LocalConfig"

    /**
     * `track.enable.firebase`（接入需求 §3.2，缺省 true）。
     *
     * RC 未接入（§11 T01 待定）前先由本地配置兜底；将来接上 Remote Config 后，
     * 只需在拉取成功后覆盖这一个字段，调用方不用改。
     */
    @Volatile
    var firebaseTrackingEnabled: Boolean = true

    /** 启动时应用一次；重复调用只是覆盖同一份配置。 */
    fun init() {
        runCatching { NoticeConfigStore.cacheNotice(RAW) }
            .onFailure { Log.w(TAG, "notice config apply failed", it) }
        val adApplied = runCatching { AdConfigStore.cacheFromDispatch(RAW) }
            .onFailure { Log.w(TAG, "ad config apply failed", it) }
            .getOrDefault(false)
        Log.d(TAG, "local config applied: ad_config=$adApplied")
    }

    const val RAW = """
{
  "notice": {
    "copop_config": {
      "co_a": 10,
      "co_t": 1,
      "co_u": 0,
      "co_on": 0,
      "nopop_end": 6,
      "co_a_limit": 140,
      "co_t_limit": 240,
      "co_u_limit": 140,
      "nopop_start": 23
    },
    "banner_notify": {
      "ring_on": 0,
      "banner_on": 0,
      "banner_time": 10
    },
    "samsung_co_pop": {
      "co_a": 10,
      "co_t": 1,
      "co_u": 0,
      "co_on": 0,
      "nopop_end": 6,
      "co_a_limit": 140,
      "co_t_limit": 240,
      "co_u_limit": 140,
      "nopop_start": 23
    }
  },
  "ad_config": {
    "ad_unit_ids": {
      "open_back": "ca-app-pub-1725671068291893/2356051586",
      "bar_banner": "",
      "home_native": "ca-app-pub-1725671068291893/4475782613",
      "inters_back": "",
      "inters_home": "",
      "connect_native": "",
      "inters_guide_1": "",
      "inters_guide_2": "",
      "search_inters_back": ""
    },
    "ad_debug_settings": {
      "is_debug_mode": false
    },
    "ad_switch_control": {
      "enable_open_back": true,
      "enable_bar_banner": false,
      "enable_home_native": true,
      "enable_inters_back": false,
      "enable_inters_home": false,
      "enable_connect_native": false,
      "enable_inters_guide_1": false,
      "enable_inters_guide_2": false,
      "enable_search_inters_back": false
    },
    "ad_frequency_control": {
      "inters_open_loadtime": 10000,
      "max_open_display_count": 1000,
      "max_banner_display_count": 10,
      "max_native_display_count": 5000,
      "max_interstitial_display_count": 10
    }
  }
}
"""
}
