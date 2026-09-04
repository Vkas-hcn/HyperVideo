package com.vi.pl.videoplayerdemo.ads

import android.util.Log

object AdLogger {
    private const val TAG = "AdmobAD"
    var enabled = true
    fun loadBlocked(slot: String, reason: String) = w("$slot load blocked: $reason")
    fun loadSkipped(slot: String, reason: String) = d("$slot load skipped: $reason")
    fun cacheExpired(slot: String, key: String?, age: Long, ttl: Long) = w("$slot cache expired age=$age ttl=$ttl")
    fun nativeLoadRequest(slot: String, key: String, state: String) = d("$slot load state=$state")
    fun nativeWaterfallTry(slot: String, index: Int, name: String, id: String, key: String) = d("$slot unit=$name index=$index")
    fun nativeLoaded(slot: String, name: String, id: String, key: String) = d("$slot loaded unit=$name")
    fun nativeLoadFailed(slot: String, name: String, key: String, reason: String) = w("$slot failed: $reason")
    fun nativeAllFailed(slot: String, key: String) = w("$slot all units failed")
    fun nativeDestroyed(slot: String) = d("$slot destroyed")
    fun bannerCollapsible(slot: String, collapsible: Boolean) = d("$slot collapsible=$collapsible")
    fun interstitialLoadRequest(slot: String, state: String) = d("$slot load state=$state")
    fun interstitialCacheHit(slot: String) = d("$slot cache hit")
    fun interstitialWaterfallTry(index: Int, name: String, id: String, slot: String) = d("$slot unit=$name index=$index")
    fun interstitialLoaded(name: String, id: String, slot: String) = d("$slot loaded unit=$name")
    fun interstitialLoadFailed(name: String, slot: String, reason: String) = w("$slot failed: $reason")
    fun interstitialAllFailed(slot: String) = w("$slot all units failed")
    fun interstitialShowRequest(slot: String, state: String, cached: String?) = d("$slot show state=$state")
    fun interstitialShowBlocked(reason: String, slot: String?) = w("${slot ?: "ad"} show blocked: $reason")
    fun interstitialPresented(slot: String) = d("$slot shown")
    fun interstitialClosed(slot: String) = d("$slot closed")
    fun interstitialAutoPreload(slot: String) = d("$slot preload")
    fun interstitialShowFailed(reason: String) = w("show failed: $reason")
    fun capBlocked(slot: String, action: String, current: Int, limit: Int) = w("$slot cap blocked")
    fun capPresentedCount(slot: String, current: Int, limit: Int) = d("$slot count=$current")
    fun sdkInitialized() = d("sdk initialized")
    fun sdkInitFailed(error: Throwable) = w("sdk initialization failed: ${error.message}")
    fun configRefreshed(enabled: Boolean) = d("config refreshed enabled=$enabled")
    fun adClicked(slot: String) = d("$slot clicked")
    fun adShowFailed(slot: String, reason: String) = w("$slot show failed: $reason")
    fun guideUmpStarted() = d("guide UMP started")
    fun guideUmpCompleted() = d("guide UMP completed")
    fun guidePermissionRequested() = d("guide notification permission requested")
    fun guidePermissionResolved(granted: Boolean) = d("guide notification permission resolved granted=$granted")
    fun guideAdTimeout(timeoutMs: Long) = w("guide ad timeout after ${timeoutMs}ms")
    fun nativePresented(slot: String) = d("$slot native shown")
    fun bannerPresented(slot: String) = d("$slot banner shown")
    private fun d(message: String) { if (enabled) Log.d(TAG, message) }
    private fun w(message: String) { if (enabled) Log.w(TAG, message) }
}
