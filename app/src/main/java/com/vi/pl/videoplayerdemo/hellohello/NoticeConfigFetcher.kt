package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.vi.pl.videoplayerdemo.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

object NoticeConfigFetcher {
    private const val TAG = "NoticeConfigFetcher"
    private val API_URL = if (BuildConfig.DEBUG) {
        "https://testserver.googletogoogle.com/api/dispatch"
    } else {
        "https://api.realtictools.cc/api/dispatch"
    }
    private const val PREFS_NAME = "notice_hub_prefs"
    private const val KEY_RAW = "notice_raw"
    private const val KEY_DISTINCT_ID = "distinct_id"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PERIODIC_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private val requestFieldMapping = linkedMapOf(
        "appName" to "cka5mb",
        "version" to "4w9m5t",
        "distinctId" to "uf1qbb",
        "refer" to "5jed9k",
        "language" to "y96k0h",
        "osVersion" to "yd7yhk",
        "gaid" to "cwecbt",
        "phoneModel" to "mhjytr",
    )

    fun init(context: Context) {
        val app = context.applicationContext
        loadCached(app)
        launchRefresh(app)
        startPeriodicRefresh(app)
    }

    fun refreshAsync(context: Context) {
        launchRefresh(context.applicationContext)
    }

    private fun startPeriodicRefresh(context: Context) {
        synchronized(refreshLock) {
            if (periodicRefreshJob?.isActive == true) return
            periodicRefreshJob = scope.launch {
                while (true) {
                    delay(PERIODIC_REFRESH_INTERVAL_MS.milliseconds)
                    runCatching { launchRefresh(context) }
                        .onFailure { Log.w(TAG, "periodic refresh schedule failed: ${it.message}", it) }
                }
            }
        }
    }

    private fun launchRefresh(context: Context) {
        val app = context.applicationContext
        synchronized(refreshLock) {
            if (refreshJob?.isActive == true) {
                Log.d(TAG, "refresh: already running")
                return
            }
            refreshJob = scope.launch {
                try {
                    refresh(app)
                } finally {
                    synchronized(refreshLock) {
                        if (refreshJob == coroutineContext[Job]) refreshJob = null
                    }
                }
            }
        }
    }

    private fun loadCached(context: Context) {
        val cached = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RAW, null)
        if (cached.isNullOrBlank()) return
        if (hasNotice(cached)) {
            NoticeConfigStore.cacheNotice(cached)
            Log.d(TAG, "cache: valid notice loaded")
        } else {
            Log.w(TAG, "cache: invalid notice payload")
        }
    }

    private suspend fun refresh(context: Context) {
        runCatching {
            val payload = buildRequestPayload(context)
            val response = postJson(API_URL, payload)
            Log.d(TAG, "response: body=$response")
            if (!hasNotice(response)) {
                Log.w(TAG, "refresh: notice missing, keep last cache")
                return
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_RAW, response) }
            NoticeConfigStore.cacheNotice(response)
            Log.d(TAG, "refresh: success")
        }.onFailure {
            Log.w(TAG, "refresh failed: ${it.message}", it)
        }
    }

    internal fun hasNotice(raw: String): Boolean = runCatching {
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: root
        data.optJSONObject("notice") != null
    }.getOrDefault(false)

    private fun buildRequestPayload(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val distinctId = prefs.getString(KEY_DISTINCT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DISTINCT_ID, it) }
        }
        val raw = mapOf(
            "appName" to context.packageName,
            "version" to BuildConfig.VERSION_NAME,
            "distinctId" to distinctId,
            "refer" to "",
            "language" to Locale.getDefault().language.orEmpty(),
            "osVersion" to Build.VERSION.RELEASE.orEmpty(),
            "gaid" to "",
            "phoneModel" to Build.MODEL.orEmpty(),
        )
        return JSONObject().apply {
            raw.forEach { (field, value) ->
                put(requestFieldMapping[field] ?: field, value)
            }
        }.toString()
    }

    private suspend fun postJson(url: String, payload: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (statusCode !in 200..299) error("dispatch server error: HTTP $statusCode, body=$body")
            body
        } finally {
            connection.disconnect()
        }
    }
}
