package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object NoticeConfigStore {
    private const val PREFS_NAME = "notice_config"
    private const val KEY_POP_CONFIG = "copop_config"
    private const val KEY_POP_CONFIG_SAMSUNG = "samsung_co_pop"
    private const val KEY_BANNER_NOTIFY = "banner_notify"

    private const val DEFAULT_POP_CONFIG = """
        {"nopop_start":23,"nopop_end":6,"co_on":1,"co_t":1,"co_t_limit":240,"co_u":0,"co_u_limit":140,"co_a":10,"co_a_limit":140}
    """

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun cacheNotice(raw: String) {
        val notice = runCatching {
            val root = JSONObject(raw)
            (root.optJSONObject("data") ?: root).optJSONObject("notice")
        }.getOrNull() ?: return

        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            notice.optJSONObject(KEY_POP_CONFIG)?.let { putString(KEY_POP_CONFIG, it.toString()) }
            notice.optJSONObject(KEY_POP_CONFIG_SAMSUNG)?.let { putString(KEY_POP_CONFIG_SAMSUNG, it.toString()) }
            notice.optJSONObject(KEY_BANNER_NOTIFY)?.let { putString(KEY_BANNER_NOTIFY, it.toString()) }
        }
    }

    fun getReceiveConfig(): UserPop {
        val key = if (isSamsung()) KEY_POP_CONFIG_SAMSUNG else KEY_POP_CONFIG
        val fallback = DEFAULT_POP_CONFIG
        val json = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, fallback)
            ?: fallback
        return parsePopConfig(json, fallback)
    }

    fun bannerNotifyConfig(): BannerNotifyConfig = read(KEY_BANNER_NOTIFY) { json ->
        BannerNotifyConfig(
            enabled = json.optInt("banner_on") == 1,
            durationSeconds = json.optInt("banner_time", 0).coerceAtLeast(0),
            ringEnabled = parseBannerRingEnabled(json.opt("ring_on")),
        )
    } ?: BannerNotifyConfig(false, 0)

    private fun <T> read(key: String, mapper: (JSONObject) -> T): T? = runCatching {
        val value = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null) ?: return null
        mapper(JSONObject(value))
    }.getOrNull()

    internal fun parseBannerRingEnabled(value: Any?): Boolean =
        value is Number && value.toDouble() == 1.0

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(key, defaultValue)

    fun putLong(key: String, value: Long) {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong(key, value) }
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun isKoreanCountry(): Boolean = isKoreanCountryCode(phoneCountryCode())

    internal fun isKoreanCountryCode(countryCode: String?): Boolean =
        countryCode?.trim()?.equals("KR", ignoreCase = true) == true

    internal fun isHourAllowed(hour: Int, start: Int, end: Int): Boolean {
        val from = min(start, end)
        val to = max(start, end)
        return hour in from..to
    }

    private fun phoneCountryCode(): String? =
        Locale.getDefault().country.trim().uppercase(Locale.US).takeIf { it.matches(Regex("[A-Z]{2}")) }

    internal fun parsePopConfig(json: String, fallback: String = DEFAULT_POP_CONFIG): UserPop = runCatching {
        JSONObject(json).toUserPop()
    }.getOrElse {
        JSONObject(fallback).toUserPop()
    }

    private fun JSONObject.toUserPop(): UserPop = UserPop(
        coIsSwitch = optInt("co_on") == 1,
        cotTime = optInt("co_t"),
        cotCount = optInt("co_t_limit"),
        couTime = optInt("co_u"),
        couCount = optInt("co_u_limit"),
        coaTime = optInt("co_a"),
        coaCount = optInt("co_a_limit"),
        freeTimeOne = optInt("nopop_start"),
        freeTimeTwo = optInt("nopop_end"),
    )
}

data class BannerNotifyConfig(
    val enabled: Boolean,
    val durationSeconds: Int,
    val ringEnabled: Boolean = false,
)
