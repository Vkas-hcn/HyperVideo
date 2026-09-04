package com.vi.pl.videoplayerdemo.hellohello

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.flux.tracksdk.core.TrackSDK
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.vi.pl.videoplayerdemo.BuildConfig
import com.vi.pl.videoplayerdemo.hellohello.ref.RefTool
import com.vi.pl.videoplayerdemo.ads.AdConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
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
    private val CLOAK_URL = if (BuildConfig.DEBUG) {
        "https://testserver.googletogoogle.com/api/cloak"
    } else {
        "https://api.realtictools.cc/api/cloak"
    }
    private const val PREFS_NAME = "notice_hub_prefs"
    private const val KEY_RAW = "notice_raw"
    private const val KEY_DISTINCT_ID = "distinct_id"
    private const val REFERRER_READ_INTERVAL_MS = 1_000L
    private const val REFERRER_RETRY_INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PERIODIC_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private val requestFieldMapping = linkedMapOf(
        "appName" to "0ws3y6",
        "version" to "7p1ag9",
        "distinctId" to "xut8z4",
        "refer" to "g3anc0",
        "language" to "kudlvl",
        "osVersion" to "6zbzgm",
        "gaid" to "u0yxxt",
        "phoneModel" to "anotf5",
    )

    fun init(context: Context) {
        val app = context.applicationContext
        loadCached(app)
        launchRefresh(app, "init", shouldRequestCloak = true)
        startPeriodicRefresh(app)
    }

    fun refreshAsync(context: Context) {
        launchRefresh(context.applicationContext, "refreshAsync", shouldRequestCloak = false)
    }

    private fun startPeriodicRefresh(context: Context) {
        synchronized(refreshLock) {
            if (periodicRefreshJob?.isActive == true) return
            periodicRefreshJob = scope.launch {
                while (true) {
                    delay(PERIODIC_REFRESH_INTERVAL_MS.milliseconds)
                    Log.d(TAG, "periodic refresh: tick")
                    runCatching {
                        launchRefresh(context, "periodic", shouldRequestCloak = false)
                    }.onFailure {
                        Log.w(TAG, "periodic refresh schedule failed: ${it.message}", it)
                    }
                }
            }
        }
    }

    private fun launchRefresh(context: Context, source: String, shouldRequestCloak: Boolean) {
        val app = context.applicationContext
        synchronized(refreshLock) {
            if (refreshJob?.isActive == true) {
                Log.d(TAG, "refresh: already running, skip source=$source")
                return
            }
            refreshJob = scope.launch {
                try {
                    refresh(app, shouldRequestCloak)
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
        }
        if (AdConfigStore.cacheFromDispatch(cached)) {
            Log.d(TAG, "cache: valid ad config loaded")
        } else if (!hasNotice(cached)) {
            Log.w(TAG, "cache: invalid notice payload")
        }
    }

    private suspend fun refresh(context: Context, shouldRequestCloak: Boolean) {
        runCatching {
            Log.d(TAG, "referrer: resolving")
            val referrer = awaitReferrer(context)
            Log.d(TAG, "referrer: resolved value=$referrer")

            val payload = buildRequestPayload(context, referrer)
            Log.d(TAG, "request: payload=$payload")

            if (shouldRequestCloak) {
                requestCloak(payload)
            }

            val response = postDispatch(payload)
            Log.d(TAG, "response: body=$response")
            if (!hasNotice(response) && !hasAdConfig(response)) {
                Log.w(TAG, "refresh: notice/ad config missing, keep last cache")
                return
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_RAW, response) }
            NoticeConfigStore.cacheNotice(response)
            AdConfigStore.cacheFromDispatch(response)
            Log.d(TAG, "refresh: success")
        }.onFailure {
            Log.w(TAG, "refresh failed: ${it.message}", it)
        }
    }

    private suspend fun awaitReferrer(context: Context): String {
        RefTool.readSavedReferrer(context)?.let {
            Log.d(TAG, "referrer: local hit")
            return it
        }

        Log.d(TAG, "referrer: local miss, start install referrer request")
        RefTool.getRefData(context)

        var elapsedSinceRequestMs = 0L
        var pollCount = 0
        var requestCount = 1
        while (true) {
            delay(REFERRER_READ_INTERVAL_MS.milliseconds)
            elapsedSinceRequestMs += REFERRER_READ_INTERVAL_MS
            pollCount += 1
            Log.d(TAG, "referrer: polling count=$pollCount")

            RefTool.readSavedReferrer(context)?.let {
                Log.d(
                    TAG,
                    "referrer: acquired after polling count=$pollCount, requestCount=$requestCount",
                )
                return it
            }

            if (elapsedSinceRequestMs >= REFERRER_RETRY_INTERVAL_MS) {
                requestCount += 1
                elapsedSinceRequestMs = 0L
                Log.d(TAG, "referrer: retry getRefData, requestCount=$requestCount")
                RefTool.getRefData(context)
            }
        }
    }

    internal fun hasNotice(raw: String): Boolean = runCatching {
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: root
        data.optJSONObject("notice") != null
    }.getOrDefault(false)

    internal fun hasAdConfig(raw: String): Boolean = AdConfigStore.extract(raw) != null

    private suspend fun buildRequestPayload(context: Context, referrer: String): String {
        val gaid = readGoogleAdvertisingId(context)
        val distinctId = runCatching { TrackSDK.getInstance().effectiveDistinctId }
            .getOrNull()
            .orEmpty()
            .ifBlank { persistedDistinctId(context) }
        val raw = mapOf(
            "appName" to context.packageName,
            "version" to BuildConfig.VERSION_NAME,
            "distinctId" to distinctId,
            "refer" to referrer,
            "language" to Locale.getDefault().language.orEmpty(),
            "osVersion" to Build.VERSION.RELEASE.orEmpty(),
            "gaid" to gaid,
            "phoneModel" to Build.MODEL.orEmpty(),
        )
        val payload = JSONObject().apply {
            raw.forEach { (field, value) ->
                put(requestFieldMapping[field] ?: field, value)
            }
        }.toString()
        Log.d(TAG, "request: rawFields=$raw, mapping=$requestFieldMapping")
        return payload
    }

    private fun persistedDistinctId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISTINCT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DISTINCT_ID, it) }
        }
    }

    private suspend fun readGoogleAdvertisingId(context: Context): String =
        withContext(Dispatchers.IO) {
            runCatching {
                AdvertisingIdClient.getAdvertisingIdInfo(context.applicationContext).id.orEmpty()
            }.onFailure {
                Log.w(TAG, "Read Google Advertising ID failed: ${it.message}", it)
            }.getOrDefault("")
        }

    private suspend fun requestCloak(payload: String) {
        runCatching {
            val response = postCloak(payload)
            Log.d(TAG, "cloak: response body=$response")
            when (val validation = validateCloakResponse(response)) {
                ValidationResult.Valid -> Log.d(TAG, "cloak: success")
                is ValidationResult.Invalid -> {
                    Log.w(TAG, "cloak: invalid response, reason=${validation.reason}")
                }
            }
        }.onFailure {
            Log.w(TAG, "cloak request failed: ${it.message}", it)
        }
    }

    private suspend fun postCloak(payload: String): String =
        postJson(CLOAK_URL, "cloak", payload)

    private suspend fun postDispatch(payload: String): String =
        postJson(API_URL, "dispatch", payload)

    private suspend fun postJson(url: String, label: String, payload: String): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "$label request: open url=$url method=POST")
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
                Log.d(
                    TAG,
                    "$label request: headers={Content-Type=application/json; charset=UTF-8, Accept=application/json}, " +
                            "connectTimeout=$CONNECT_TIMEOUT_MS, readTimeout=$READ_TIMEOUT_MS",
                )
                Log.d(TAG, "$label request: writing body")
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
                Log.d(TAG, "$label request: body written")

                val statusCode = connection.responseCode
                Log.d(TAG, "$label response: statusCode=$statusCode")
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)
                    ?.use(BufferedReader::readText)
                    .orEmpty()

                if (statusCode !in 200..299) {
                    error("$label server error: HTTP $statusCode, body=$body")
                }
                body
            } finally {
                Log.d(TAG, "$label request: disconnect")
                connection.disconnect()
            }
        }

    private fun validateCloakResponse(raw: String): ValidationResult = runCatching {
        val root = JSONObject(raw)
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code == 200) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(reason = "code=$code, raw=$raw")
        }
    }.getOrElse { error ->
        ValidationResult.Invalid(reason = "Invalid JSON: ${error.message}, raw=$raw")
    }

    private sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}
