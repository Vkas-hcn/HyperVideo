package com.vi.pl.videoplayerdemo.ads

import android.util.Log

/**
 * 广告日志（logcat TAG = [TAG]）。
 *
 * 统一中文输出，覆盖：请求加载 / 被阻止 / 加载成功 / 加载失败 / 展示 / 关闭 / 点击 / 频次控制。
 * 格式为 `【广告位中文名】事件,细节`；未收录的广告位 key 会原样输出，方便和后台配置对照。
 */
object AdLogger {
    private const val TAG = "AdmobAD"
    private const val DEFAULT_SLOT = "interstitial"
    var enabled = true

    // ---------------- 加载 ----------------

    fun loadBlocked(slot: String, reason: String) =
        w("${prefix(slot)}加载被阻止,原因:${reasonText(reason)}")

    fun loadSkipped(slot: String, reason: String) =
        d("${prefix(slot)}跳过加载,原因:${reasonText(reason)}")

    fun cacheExpired(slot: String, key: String?, age: Long, ttl: Long) =
        w("${prefix(slot)}缓存已过期,已缓存 ${age / 1000} 秒,有效期 ${ttl / 1000} 秒")

    fun nativeLoadRequest(slot: String, key: String, state: String) {
        // 场景名已包含在槽位中文名里时不再重复打印（如 首页原生 / 底部横幅·普通）
        val sceneLabel = sceneName(key)
        val scene = if (slotName(slot).contains(sceneLabel)) "" else ",场景:${sceneLabel}"
        d("${prefix(slot)}开始加载,当前状态:${stateName(state)}$scene")
    }

    fun nativeWaterfallTry(slot: String, index: Int, name: String, id: String, key: String) =
        d("${prefix(slot)}尝试第 ${index + 1} 个广告单元:${name}(${id})")

    fun nativeLoaded(slot: String, name: String, id: String, key: String) =
        d("${prefix(slot)}加载成功,广告单元:${name}(${id})")

    fun nativeLoadFailed(slot: String, name: String, key: String, reason: String) =
        w("${prefix(slot)}加载失败,广告单元:${name},原因:${reasonText(reason)}")

    fun nativeAllFailed(slot: String, key: String) =
        w("${prefix(slot)}全部广告单元加载失败,本次不展示")

    fun nativeDestroyed(slot: String, inFlight: Boolean = false) =
        d("${prefix(slot)}已清理当前广告" + if (inFlight) ",在途加载继续" else "")

    fun nativePresented(slot: String) = d("${prefix(slot)}展示")

    fun bannerCollapsible(slot: String, collapsible: Boolean) =
        d("${prefix(slot)}横幅形态:${bannerShape(collapsible)}")

    fun bannerPresented(slot: String) = d("${prefix(slot)}展示")

    // ---------------- 插屏 / 开屏 ----------------

    fun interstitialLoadRequest(slot: String) = d("${prefix(slot)}开始加载")

    fun interstitialCacheHit(slot: String) = d("${prefix(slot)}命中缓存,直接复用")

    fun interstitialAutoPreload(slot: String) = d("${prefix(slot)}开始自动预加载")

    fun hotStartClosingAd() = d("【启动页】热启动关闭当前广告")
    fun hotStartCloseTimeout() = w("【启动页】热启动关闭当前广告超时,强制重置展示状态")

    fun interstitialWaterfallTry(index: Int, name: String, id: String, slot: String) =
        d("${prefix(slot)}尝试第 ${index + 1} 个广告单元:${name}(${id})")

    fun interstitialLoaded(name: String, id: String, slot: String) =
        d("${prefix(slot)}加载成功,广告单元:${name}(${id})")

    fun interstitialLoadFailed(name: String, slot: String, reason: String) =
        w("${prefix(slot)}加载失败,广告单元:${name},原因:${reasonText(reason)}")

    fun interstitialAllFailed(slot: String) =
        w("${prefix(slot)}全部广告单元加载失败,本次不展示")

    fun interstitialShowRequest(slot: String, state: String, cached: String?) =
        d("${prefix(slot)}请求展示,当前状态:${stateName(state)}")

    fun interstitialShowBlocked(reason: String, slot: String?) =
        w("${prefix(slot ?: DEFAULT_SLOT)}展示被阻止,原因:${reasonText(reason)}")

    fun interstitialPresented(slot: String) = d("${prefix(slot)}展示成功")

    fun interstitialClosed(slot: String) = d("${prefix(slot)}已关闭")

    fun interstitialShowFailed(reason: String) =
        w("${prefix(DEFAULT_SLOT)}展示失败,原因:${reasonText(reason)}")

    fun adShowFailed(slot: String, reason: String) =
        w("${prefix(slot)}展示失败,原因:${reasonText(reason)}")

    fun adRetryScheduled(slot: String, attempt: Int, delayMs: Long) =
        d("${prefix(slot)}加载失败重试已排程,第 ${attempt} 次,延迟 ${delayMs / 1000} 秒")

    fun adRetryGivenUp(slot: String) =
        w("${prefix(slot)}重试已放弃,原因:重试次数已达上限")

    fun adClicked(slot: String) = d("${prefix(slot)}被点击")

    // ---------------- 频次控制 ----------------

    fun capBlocked(slot: String, current: Int, limit: Int) =
        w("${prefix(slot)}已达展示上限,今日 ${current}/${limit} 次,本次跳过")

    fun capPresentedCount(slot: String, current: Int, limit: Int) =
        d("${prefix(slot)}展示计数更新:今日 ${current}/${limit} 次")

    // ---------------- 全局 ----------------

    fun sdkInitialized() = d("【全局】AdMob SDK 初始化完成")

    fun sdkInitFailed(error: Throwable) =
        w("【全局】AdMob SDK 初始化失败,原因:${error.message}")

    fun configRefreshed(enabled: Boolean) =
        d("【全局】广告配置已刷新,总开关:${switchText(enabled)}")

    fun configInvalid(error: Throwable) =
        w("【全局】广告配置解析失败,已按全部关闭处理,原因:${error.message}")

    // ---------------- 启动页流程 ----------------

    fun guideUmpStarted() = d("【启动页】UMP 同意流程开始")

    fun guideUmpCompleted() = d("【启动页】UMP 同意流程结束")

    fun guidePermissionRequested() = d("【启动页】请求通知权限")

    fun guidePermissionResolved(granted: Boolean) =
        d("【启动页】通知权限:${permissionText(granted)}")

    fun guideTimeoutArmed(timeoutMs: Long) =
        d("【启动页】开始等待广告,${timeoutMs / 1000} 秒内未展示则跳过")

    fun guideAdTimeout(timeoutMs: Long) =
        w("【启动页】等待广告超时(${timeoutMs / 1000} 秒),跳过广告")

    // ---------------- 文案映射 ----------------

    private fun prefix(slot: String) = "【${slotName(slot)}】"

    private fun bannerShape(collapsible: Boolean) = if (collapsible) "可折叠" else "普通"

    private fun switchText(on: Boolean) = if (on) "开" else "关"

    private fun permissionText(granted: Boolean) = if (granted) "已授权" else "未授权"

    private fun slotName(raw: String): String {
        val base = raw.substringBefore('/')
        val suffix = when {
            raw.endsWith("/regular") -> "·普通"
            raw.endsWith("/collapsible") -> "·折叠"
            else -> ""
        }
        val name = when {
            base.startsWith("native_") -> "原生广告·" + placementName(base.removePrefix("native_"))
            else -> when (base) {
                "ad" -> "广告"
                "native" -> "原生广告"
                "interstitial" -> "插屏"
                "open_back" -> "开屏广告"
                "inters_back" -> "浏览器返回插屏"
                "inters_guide_1" -> "引导页插屏1"
                "inters_guide_2" -> "引导页插屏2"
                "inters_home" -> "首页插屏"
                "search_inters_back" -> "搜索返回插屏"
                "bar_banner" -> "底部横幅"
                "home_native" -> "首页原生"
                "connect_native" -> "连接页原生"
                "guide_startup", "guide_startup_timeout" -> "启动页广告"
                else -> base
            }
        }
        return name + suffix
    }

    private fun placementName(key: String): String = when (key) {
        "HOME", "home_native" -> "首页"
        "CONNECT", "connect_native" -> "连接页"
        else -> key
    }

    private fun sceneName(key: String): String = when (key) {
        "HOME" -> "首页"
        "CONNECT" -> "连接页"
        else -> slotName(key)
    }

    private fun stateName(state: String): String = when (state) {
        "NOT_LOADED" -> "未加载"
        "LOADING" -> "加载中"
        "LOADED" -> "已加载"
        "SHOWING" -> "展示中"
        "FAILED" -> "加载失败"
        "CLOSED" -> "已关闭"
        else -> state
    }

    private fun reasonText(reason: String): String {
        if (reason.startsWith("refresh_failed")) {
            return "自动刷新失败" + reason.removePrefix("refresh_failed:")
        }
        return when (reason) {
            "SDK not initialized" -> "SDK 未初始化"
            "Ad disabled by config" -> "广告位未开启或未配置广告单元"
            "daily display cap reached", "Daily display cap reached" -> "已达每日展示上限"
            "app_background" -> "应用不在前台"
            "another_ad_showing" -> "已有其他广告正在展示"
            "invalid_activity" -> "页面已销毁"
            "Ad not loaded" -> "广告尚未加载"
            "Failed to load all ad units" -> "全部广告单元加载失败"
            "Failed to load all banner ad units" -> "全部横幅广告单元加载失败"
            "Banner ad unit missing" -> "未配置横幅广告单元"
            "already_loading" -> "已有加载任务进行中"
            "fullscreen_min_interval" -> "距上次全屏广告关闭不足最小间隔"
            "cache_discarded" -> "缓存已丢弃"
            "SDK exception" -> "SDK 调用异常"
            else -> reason
        }
    }

    private fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    private fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }
}
