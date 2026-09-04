package com.vi.pl.videoplayerdemo.ads

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Stores only the ad_config part of dispatch data, keeping ads independent of VPN models. */
object AdConfigStore {
    private const val PREFS = "ad_config"
    private const val KEY_RAW = "raw"
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var raw = ""
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RAW, "").orEmpty()
        addListener { AdMobManager.refreshConfig() }
    }

    fun get(): String = raw

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun cacheFromDispatch(dispatch: String): Boolean {
        val extracted = extract(dispatch) ?: return false
        runCatching { AdConfigSerializer.fromJson(extracted) }.getOrElse { return false }
        if (extracted == raw) return true
        raw = extracted
        if (::app.isInitialized) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_RAW, extracted) }
        listeners.forEach { runCatching { it() } }
        return true
    }

    internal fun extract(dispatch: String): String? = runCatching {
        val root = JSONObject(dispatch)
        val data = root.optJSONObject("data") ?: root
        when (val value = data.opt("ad_config") ?: root.opt("ad_config")) {
            is JSONObject -> value.toString()
            is String -> value.trim().takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() }
            else -> null
        }
    }.getOrNull()
}
