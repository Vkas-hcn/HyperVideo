package com.vi.pl.videoplayerdemo.hellohello

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.vi.pl.videoplayerdemo.HyperVideoApplication
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.download.DownloadBarLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.max
import kotlin.random.Random

object HintUtil {
    private const val TAG = "NewsNotification"
    const val CO_SHOW_TYPE = "nUBI3nLEBU"
    const val CO_NOTIFICATION_ID = "news_notification_id"
    const val CO_S_TYPE_UNLOCK = 0
    const val CO_S_TYPE_INTERVAL = 1
    const val CO_S_TYPE_ALARM = 2

    private const val NEWS_CHANNEL_ID = "hypervideo_news_v1"
    private const val NEWS_SILENT_CHANNEL_ID = "hypervideo_news_silent_v1"
    private const val NOTIFICATION_ID_PREFS = "news_notification_ids"
    private const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
    private const val FIRST_DYNAMIC_NOTIFICATION_ID = 70_000
    private const val CO_SP_UNLOCK_N = "ihenb83bne"
    private const val CO_SP_UNLOCK_T = "nUen4noIEBI"
    private const val CO_SP_INTERAL_N = "Un3oib(UH3f"
    private const val CO_SP_INTERAL_T = "PnowB93B(ILK"
    private const val CO_SP_ALARM_N = "d#nbefOINEFLN"
    private const val CO_SP_ALARM_T = "ONebo0#kkjfwI"
    private const val CO_SP_ALARM_NEXT_TIME = "IienOeniorgUen"
    private const val FCM_TOPIC = "hypervideo"

    var contentSource: NotificationContentSource = PlaceholderContentSource

    private val scope = CoroutineScope(Dispatchers.IO)
    private val bannerSessions = BannerNotificationSessionManager()
    private val notificationIdLock = Any()
    private var initialized = false
    private var isForeground = false
    private var activityCount = 0

    fun coHavePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                coIntance(),
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(coIntance()).areNotificationsEnabled()
        }

    fun init(application: Application) {
        if (initialized) {
            Log.d(TAG, "init skipped: already initialized")
            return
        }
        initialized = true
        Log.i(TAG, "init: manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}")
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activityCount++ == 0) {
                    isForeground = true
                    Log.d(TAG, "app entered foreground: ${activity.javaClass.simpleName}")
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount = max(0, activityCount - 1)
                if (activityCount == 0) {
                    isForeground = false
                    Log.d(TAG, "app entered background: ${activity.javaClass.simpleName}")
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        scope.launch {
            delay(2_627)
            while (true) {
                Log.d(TAG, "interval trigger received")
                sendTone()
                delay(59_987)
            }
        }

        subscribeFcmTopic()
        ContextCompat.registerReceiver(
            application,
            PhoneLockRe(),
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            application,
            HomeRecentsReceiver(),
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        markLing()
    }

    fun coIntance(): Context = HyperVideoApplication.get()

    fun startJob(context: Context) {
        AssistanceLaunceJob.enqueueWork(context)
    }

    fun launchFg(coShowOneNt: Boolean = false) {
        DownloadBarLauncher.ensureRunning(coIntance())
        markLing()
    }

    private fun subscribeFcmTopic() {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC).addOnCompleteListener {
                Log.d(TAG, "FCM topic subscription completed: success=${it.isSuccessful}", it.exception)
            }
        }.onFailure {
            Log.w(TAG, "FCM topic subscription skipped: ${it.message}")
        }
    }

    private suspend fun sendTone() {
        sendLatestNews(CO_S_TYPE_INTERVAL)
    }

    fun sendTtwo(onComplete: () -> Unit = {}) =
        sendLatestNewsAsync(CO_S_TYPE_UNLOCK, onComplete)

    fun sendTthree(onComplete: () -> Unit = {}) {
        sendLatestNewsAsync(CO_S_TYPE_ALARM, onComplete)
    }

    fun cancelNewsNotification(showType: Int, notificationId: Int? = null) {
        val resolvedNotificationId = notificationId ?: legacyNotificationId(showType)
        bannerSessions.snapshot()
            ?.takeIf { it.notificationId == resolvedNotificationId }
            ?.let { bannerSessions.cancel(it.sessionId) }
        NotificationManagerCompat.from(coIntance()).cancel(resolvedNotificationId)
        Log.d(TAG, "notification cancelled: type=${showTypeName(showType)}, id=$resolvedNotificationId")
    }

    fun restorePersistentBannerIfActive(notificationId: Int, showType: Int) {
        val session = bannerSessions.snapshot()?.takeIf { it.notificationId == notificationId } ?: return
        if (isDeviceLocked() || !canPostNewsNotifications()) return
        sendStandardNotification(
            session.content,
            showType,
            notificationId,
            alert = refreshAlert(silent = !NoticeConfigStore.bannerNotifyConfig().ringEnabled),
            sticky = true,
        )
        Log.d(TAG, "persistent banner restored after dismiss: id=$notificationId")
    }

    fun markLing() {
        scope.launch {
            if (NoticeConfigStore.isKoreanCountry() && NoticeConfigStore.isSamsung()) {
                Log.d(TAG, "alarm scheduling skipped: Korean Samsung policy")
                return@launch
            }
            val config = NoticeConfigStore.getReceiveConfig()
            if (config.coaTime <= 0) {
                Log.d(TAG, "alarm scheduling skipped: alarm interval=${config.coaTime}")
                return@launch
            }

            val now = System.currentTimeMillis()
            val nextAlarmAt = NoticeConfigStore.getLong(CO_SP_ALARM_NEXT_TIME)
            if (nextAlarmAt > now) {
                Log.d(TAG, "alarm scheduling skipped: existing alarm at=$nextAlarmAt, now=$now")
                return@launch
            }

            val setTime = now + config.cotTime * 60_000L
            val alarm = coIntance().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(coIntance(), TimeWorkerRe::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                coIntance(),
                randomRequestCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NoticeConfigStore.putLong(CO_SP_ALARM_NEXT_TIME, setTime)
            AlarmManagerCompat.setAndAllowWhileIdle(alarm, AlarmManager.RTC_WAKEUP, setTime, pendingIntent)
            Log.i(TAG, "alarm scheduled: triggerAt=$setTime, intervalMinutes=${config.cotTime}")
        }
    }

    private suspend fun canSend(showType: Int): Boolean {
        val type = showTypeName(showType)
        val notificationManager = NotificationManagerCompat.from(coIntance())
        val permissionGranted = coHavePermission()
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        if (!permissionGranted || !notificationsEnabled) {
            Log.w(TAG, "send blocked [$type]: permissionGranted=$permissionGranted, notificationsEnabled=$notificationsEnabled")
            return false
        }
        val config = NoticeConfigStore.getReceiveConfig()
        if (!config.coIsSwitch) {
            Log.d(TAG, "send blocked [$type]: notification switch is off")
            return false
        }
        if (NoticeConfigStore.isKoreanCountry() && NoticeConfigStore.isSamsung()) {
            Log.d(TAG, "send blocked [$type]: Korean Samsung policy")
            return false
        }
        if (isForeground) {
            Log.d(TAG, "send blocked [$type]: app is foreground")
            return false
        }

        val lastTimeKey = lastTimeKey(showType)
        val countKey = countKey(showType)
        val lastTime = NoticeConfigStore.getLong(lastTimeKey)
        if (!lastTime.isToday()) NoticeConfigStore.putLong(countKey, 0)

        val sent = NoticeConfigStore.getLong(countKey)
        val limit = when (showType) {
            CO_S_TYPE_UNLOCK -> config.couCount
            CO_S_TYPE_INTERVAL -> config.cotCount
            else -> config.coaCount
        }
        val cooldownMinutes = when (showType) {
            CO_S_TYPE_UNLOCK -> config.couTime
            CO_S_TYPE_INTERVAL -> config.cotTime
            else -> config.coaTime
        }
        val now = System.currentTimeMillis()
        if (cooldownMinutes * 60_000L > now - lastTime) {
            Log.d(TAG, "send blocked [$type]: cooldownMinutes=$cooldownMinutes, lastSent=$lastTime, now=$now")
            return false
        }
        if (sent >= limit) {
            Log.d(TAG, "send blocked [$type]: dailySent=$sent, dailyLimit=$limit")
            return false
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val allowed = NoticeConfigStore.isHourAllowed(hour, config.freeTimeOne, config.freeTimeTwo)
        Log.d(TAG, "send eligibility [$type]: allowed=$allowed, hour=$hour, dailySent=$sent/$limit")
        return allowed
    }

    private fun recordSent(showType: Int) {
        val countKey = countKey(showType)
        NoticeConfigStore.putLong(lastTimeKey(showType), System.currentTimeMillis())
        NoticeConfigStore.putLong(countKey, NoticeConfigStore.getLong(countKey) + 1)
        Log.i(TAG, "send recorded [${showTypeName(showType)}]: dailySent=${NoticeConfigStore.getLong(countKey)}")
    }

    private fun sendLatestNewsAsync(showType: Int, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                sendLatestNews(showType)
            } catch (error: Throwable) {
                Log.e(TAG, "async send failed [${showTypeName(showType)}]", error)
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun sendLatestNews(showType: Int) {
        val type = showTypeName(showType)
        Log.i(TAG, "send started [$type]")
        if (bannerSessions.isActive()) {
            Log.d(TAG, "send blocked [$type]: persistent banner is active")
            return
        }
        if (!canSend(showType)) return
        val bannerConfig = NoticeConfigStore.bannerNotifyConfig()
        val content = contentSource.load(coIntance(), showType)
        if (content == null) {
            Log.w(TAG, "send blocked [$type]: no notification content")
            return
        }
        if (!canSend(showType)) {
            Log.d(TAG, "send aborted [$type]: eligibility changed after content fetch")
            return
        }
        val notificationId = nextNotificationId()
        if (bannerConfig.enabled && bannerConfig.durationSeconds > 0 && !isDeviceLocked()) {
            sendPersistentNotification(content, showType, notificationId, bannerConfig.durationSeconds, bannerConfig.ringEnabled)
        } else {
            if (bannerConfig.enabled && bannerConfig.durationSeconds > 0) {
                Log.d(TAG, "persistent banner skipped [$type]: device is locked")
            }
            if (sendStandardNotification(content, showType, notificationId)) {
                recordSent(showType)
            }
        }
    }

    private suspend fun sendPersistentNotification(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        durationSeconds: Int,
        ringEnabled: Boolean,
    ) {
        val session = bannerSessions.tryStart(content, durationSeconds, notificationId) ?: run {
            Log.d(TAG, "persistent banner skipped: another session became active")
            return
        }
        var firstNotification = true
        try {
            while (true) {
                val dispatch = bannerSessions.markShown(session.sessionId) ?: break
                val posted = sendStandardNotification(
                    content,
                    showType,
                    session.notificationId,
                    alert = if (dispatch.isFirstInSession) {
                        firstShowAlert
                    } else {
                        refreshAlert(silent = !ringEnabled)
                    },
                    sticky = true,
                )
                if (firstNotification) {
                    if (posted) recordSent(showType)
                    firstNotification = false
                }
                delay(BannerNotificationSessionManager.STAY_MS)
                val shouldRepeat = bannerSessions.finishStay(session.sessionId)
                if (!shouldRepeat) break
                if (isDeviceLocked()) {
                    Log.d(TAG, "persistent banner stopped: device locked")
                    break
                }
                if (!canPostNewsNotifications()) {
                    Log.w(TAG, "persistent banner stopped: notification permission or switch changed")
                    break
                }
            }
        } finally {
            val ended = bannerSessions.cancel(session.sessionId)
            if (ended != null) {
                sendStandardNotification(
                    content,
                    showType,
                    session.notificationId,
                    alert = refreshAlert(silent = true),
                    sticky = false,
                )
            }
        }
    }

    private fun canPostNewsNotifications(): Boolean =
        coHavePermission() && NotificationManagerCompat.from(coIntance()).areNotificationsEnabled()

    private fun isDeviceLocked(): Boolean =
        coIntance().getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun sendStandardNotification(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        alert: AlertProfile = firstShowAlert,
        sticky: Boolean = false,
    ): Boolean {
        return runCatching {
            val notification = createOnceNt(content, showType, notificationId, alert, sticky)
            getManager().notify(notificationId, notification)
            Log.i(TAG, "notify submitted [${showTypeName(showType)}]: id=$notificationId, sticky=$sticky")
            true
        }.onFailure {
            Log.e(TAG, "notify failed [${showTypeName(showType)}]", it)
        }.getOrDefault(false)
    }

    private fun createOnceNt(
        content: NotificationContent,
        showType: Int,
        notificationId: Int,
        alert: AlertProfile,
        sticky: Boolean,
    ): Notification {
        val channelId = if (alert.silent) NEWS_SILENT_CHANNEL_ID else NEWS_CHANNEL_ID
        createChannel(
            channelId,
            if (alert.silent) R.string.notification_channel_news_silent else R.string.notification_channel_news,
            NotificationManagerCompat.IMPORTANCE_HIGH,
            silent = alert.silent,
        )
        val contentIntent = createIntent(showType, notificationId)
        return NotificationCompat.Builder(coIntance(), channelId)
            .setSmallIcon(R.drawable.ic_download_btn)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(alert.onlyAlertOnce)
            .setSilent(alert.silent)
            .applyAlertBehavior(alert)
            .setContentIntent(contentIntent)
            .apply {
                if (sticky) {
                    setOngoing(true)
                    setDeleteIntent(createDismissIntent(showType, notificationId))
                }
            }
            .setCustomContentView(createCollapsedView(content, contentIntent))
            .setCustomBigContentView(createExpandedView(content, contentIntent))
            .setCustomHeadsUpContentView(createCollapsedView(content, contentIntent))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
    }

    private fun NotificationCompat.Builder.applyAlertBehavior(alert: AlertProfile): NotificationCompat.Builder {
        if (alert.silent) {
            return setDefaults(0).setVibrate(null)
        }
        if (alert.vibrate) {
            return setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVibrate(longArrayOf(0, 300, 200, 300))
        }
        return setDefaults(NotificationCompat.DEFAULT_SOUND).setVibrate(null)
    }

    private data class AlertProfile(
        val onlyAlertOnce: Boolean,
        val silent: Boolean,
        val vibrate: Boolean,
    )

    private val firstShowAlert = AlertProfile(
        onlyAlertOnce = false,
        silent = false,
        vibrate = true,
    )

    private fun refreshAlert(silent: Boolean) = AlertProfile(
        onlyAlertOnce = silent,
        silent = silent,
        vibrate = false,
    )

    private fun createCollapsedView(content: NotificationContent, contentIntent: PendingIntent): RemoteViews {
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            R.layout.notification_collapsed
        } else {
            R.layout.notification_collapsed_legacy
        }
        return RemoteViews(coIntance().packageName, layout).also { bindCollapsed(it, content, contentIntent) }
    }

    private fun createExpandedView(content: NotificationContent, contentIntent: PendingIntent): RemoteViews =
        RemoteViews(coIntance().packageName, R.layout.notification_expanded).also {
            bindExpanded(it, content, contentIntent)
        }

    private fun bindCollapsed(view: RemoteViews, content: NotificationContent, contentIntent: PendingIntent) {
        val textColor = ContextCompat.getColor(coIntance(), R.color.notification_text)
        view.setTextViewText(R.id.news_title, content.title)
        view.setTextViewText(R.id.news_action, content.action)
        view.setTextColor(R.id.news_title, textColor)
        view.setOnClickPendingIntent(R.id.news_action, contentIntent)
    }

    private fun bindExpanded(view: RemoteViews, content: NotificationContent, contentIntent: PendingIntent) {
        val textColor = ContextCompat.getColor(coIntance(), R.color.notification_text)
        view.setTextViewText(R.id.news_title, content.title)
        view.setTextViewText(R.id.news_caption, content.summary)
        view.setTextViewText(R.id.news_action, content.action)
        view.setTextColor(R.id.news_title, textColor)
        view.setTextColor(R.id.news_caption, textColor)
        view.setOnClickPendingIntent(R.id.news_action, contentIntent)
    }

    private fun createIntent(showType: Int, notificationId: Int): PendingIntent {
        val intent = Intent(coIntance(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CO_SHOW_TYPE, showType)
            putExtra(CO_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getActivity(
            coIntance(),
            randomRequestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createDismissIntent(showType: Int, notificationId: Int): PendingIntent {
        val intent = Intent(coIntance(), NewsBannerDismissReceiver::class.java).apply {
            putExtra(CO_SHOW_TYPE, showType)
            putExtra(CO_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            coIntance(),
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel(id: String, nameRes: Int, importance: Int, silent: Boolean) {
        NotificationManagerCompat.from(coIntance()).createNotificationChannel(
            NotificationChannelCompat.Builder(id, importance)
                .setName(coIntance().getString(nameRes))
                .setSound(if (silent) null else android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                .setLightsEnabled(!silent)
                .setVibrationEnabled(!silent)
                .setShowBadge(!silent)
                .build(),
        )
    }

    private fun getManager(): NotificationManager =
        coIntance().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun lastTimeKey(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> CO_SP_UNLOCK_T
        CO_S_TYPE_INTERVAL -> CO_SP_INTERAL_T
        else -> CO_SP_ALARM_T
    }

    private fun countKey(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> CO_SP_UNLOCK_N
        CO_S_TYPE_INTERVAL -> CO_SP_INTERAL_N
        else -> CO_SP_ALARM_N
    }

    private fun nextNotificationId(): Int = synchronized(notificationIdLock) {
        val prefs = coIntance().getSharedPreferences(NOTIFICATION_ID_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_NOTIFICATION_ID, FIRST_DYNAMIC_NOTIFICATION_ID - 1)
        val next = if (last in FIRST_DYNAMIC_NOTIFICATION_ID until Int.MAX_VALUE) last + 1 else FIRST_DYNAMIC_NOTIFICATION_ID
        prefs.edit().putInt(KEY_LAST_NOTIFICATION_ID, next).commit()
        next
    }

    private fun legacyNotificationId(showType: Int): Int = when (showType) {
        CO_S_TYPE_UNLOCK -> 60111
        CO_S_TYPE_INTERVAL -> 60101
        else -> 60121
    }

    private fun showTypeName(showType: Int): String = when (showType) {
        CO_S_TYPE_UNLOCK -> "unlock"
        CO_S_TYPE_INTERVAL -> "interval"
        CO_S_TYPE_ALARM -> "alarm"
        else -> "unknown($showType)"
    }

    private fun Long.isToday(): Boolean {
        if (this <= 0) return false
        val calendar = Calendar.getInstance()
        val today = LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        calendar.timeInMillis = this
        return today == LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun randomRequestCode(): Int = Random.nextInt(1, Int.MAX_VALUE)
}
