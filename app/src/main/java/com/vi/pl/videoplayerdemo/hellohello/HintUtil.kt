package com.vi.pl.videoplayerdemo.hellohello

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
import android.widget.RemoteViews
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flux.tracksdk.core.TrackSDK
import com.flux.tracksdk.model.TrackPolicy
import com.vi.pl.videoplayerdemo.HyperVideoApplication
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.ui.GuideActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 本地新闻通知核心（复刻 AnonyBrowser HintUtil，适配 HyperVideo）：
 * 三类触发（解锁/轮询/闹钟）汇聚统一发送入口 + 统一门禁 + 动态 ID + 双 channel +
 * 临时常驻 banner 会话 + 闹钟预约。无 VPN、无常驻栏、无机型/地区策略。
 * 全链路日志走 [NewsLogger]（与广告 AdLogger 同形态）。
 */
object HintUtil {
    const val CO_SHOW_TYPE = "nUBI3nLEBU"
    const val CO_NOTIFICATION_ID = "news_notification_id"
    const val CO_S_TYPE_UNLOCK = 0
    const val CO_S_TYPE_INTERVAL = 1
    const val CO_S_TYPE_ALARM = 2

    private const val NEWS_CHANNEL_ID = "browser_news_updates_v2"
    private const val NEWS_SILENT_CHANNEL_ID = "browser_news_updates_silent_v2"
    private const val NOTIFICATION_ID_PREFS = "news_notification_ids"
    private const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
    private const val CO_SP_UNLOCK_N = "ihenb83bne"
    private const val CO_SP_UNLOCK_T = "nUen4noIEBI"
    private const val CO_SP_INTERAL_N = "Un3oib(UH3f"
    private const val CO_SP_INTERAL_T = "PnowB93B(ILK"
    private const val CO_SP_ALARM_N = "d#nbefOINEFLN"
    private const val CO_SP_ALARM_T = "ONebo0#kkjfwI"
    private const val CO_SP_ALARM_NEXT_TIME = "IienOeniorgUen"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val bannerSessions = BannerNotificationSessionManager()
    private val notificationIdLock = Any()
    private var initialized = false

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
            NewsLogger.initSkipped()
            return
        }
        initialized = true
        NewsLogger.runtimeInitialized()

        scope.launch {
            delay(2_627)
            while (true) {
                NewsLogger.triggerReceived(CO_S_TYPE_INTERVAL)
                sendTone()
                delay(59_987)
            }
        }

        MessageRecv.subscribeTopic(source = "启动")
        ContextCompat.registerReceiver(
            application,
            PhoneLockRe(),
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        NewsLogger.receiverRegistered("ACTION_USER_PRESENT")
        ContextCompat.registerReceiver(
            application,
            HomeRecentsReceiver(),
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        NewsLogger.receiverRegistered("CLOSE_SYSTEM_DIALOGS")
        markLing()
    }

    fun coIntance(): Context = HyperVideoApplication.get()

    fun startJob(context: Context) {
        AssistanceLaunceJob.enqueueWork(context)
    }

    /** 保活载体已移除：launchFg 等价于补排闹钟，保证通知链路自恢复。 */
    fun launchFg(coShowOneNt: Boolean = false) {
        markLing()
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
        NotificationContentRepository.resetCopyCache()
        NewsLogger.notificationCancelled(showType, resolvedNotificationId)
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
            trackSent = false,
        )
        NewsLogger.bannerRestored(notificationId)
    }

    /** 闹钟预约：co_a 为间隔（以 AnonyBrowser 代码为准）；幂等，已排未来闹钟则跳过。 */
    fun markLing() {
        scope.launch {
            val config = NoticeConfigStore.getReceiveConfig()
            if (config.coaTime <= 0) {
                NewsLogger.alarmSkipped("alarm_interval_disabled")
                return@launch
            }

            val now = System.currentTimeMillis()
            val nextAlarmAt = NoticeConfigStore.getLong(CO_SP_ALARM_NEXT_TIME)
            if (nextAlarmAt > now) {
                NewsLogger.alarmSkipped("existing_alarm")
                return@launch
            }

            val setTime = now + config.coaTime * 60_000L
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
            NewsLogger.alarmScheduled(setTime, config.coaTime)
        }
    }

    private suspend fun canSend(showType: Int): Boolean {
        val notificationManager = NotificationManagerCompat.from(coIntance())
        val permissionGranted = coHavePermission()
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        if (!permissionGranted) {
            NewsLogger.sendBlocked(showType, "no_permission", detail = ",系统开关=" + notificationsEnabled)
            return false
        }
        if (!notificationsEnabled) {
            NewsLogger.sendBlocked(showType, "notifications_disabled")
            return false
        }
        val config = NoticeConfigStore.getReceiveConfig()
        if (!config.coIsSwitch) {
            NewsLogger.sendBlocked(showType, "switch_off")
            return false
        }
        if (HyperVideoApplication.get().isAppForeground()) {
            NewsLogger.sendBlocked(showType, "app_foreground")
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
        if (!NewsGateRules.cooldownSatisfied(lastTime, now, cooldownMinutes)) {
            NewsLogger.sendBlocked(showType, "cooldown", detail = ",冷却分钟=" + cooldownMinutes + ",上次发送=" + lastTime)
            return false
        }
        if (!NewsGateRules.dailyCountBelow(sent, limit)) {
            NewsLogger.sendBlocked(showType, "daily_limit", detail = ",今日已发=" + sent + "/" + limit)
            return false
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val allowed = NoticeConfigStore.isHourAllowed(hour, config.freeTimeOne, config.freeTimeTwo)
        NewsLogger.sendEligibility(
            showType, allowed, hour,
            min(config.freeTimeOne, config.freeTimeTwo), max(config.freeTimeOne, config.freeTimeTwo),
            sent, limit,
        )
        return allowed
    }

    private fun recordSent(showType: Int) {
        val countKey = countKey(showType)
        NoticeConfigStore.putLong(lastTimeKey(showType), System.currentTimeMillis())
        NoticeConfigStore.putLong(countKey, NoticeConfigStore.getLong(countKey) + 1)
        NewsLogger.sendRecorded(showType, NoticeConfigStore.getLong(countKey))
    }

    private fun sendLatestNewsAsync(showType: Int, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                sendLatestNews(showType)
            } catch (error: Throwable) {
                NewsLogger.asyncSendFailed(showType, error)
            } finally {
                NewsLogger.asyncSendFinished(showType)
                onComplete()
            }
        }
    }

    private suspend fun sendLatestNews(showType: Int) {
        NewsLogger.sendStarted(showType)
        if (bannerSessions.isActive()) {
            NewsLogger.sendBlocked(showType, "banner_active")
            return
        }
        if (!canSend(showType)) return
        val bannerConfig = NoticeConfigStore.bannerNotifyConfig()
        val content = NotificationContentRepository.next(coIntance())
        if (content == null) {
            NewsLogger.sendBlocked(showType, "no_content")
            return
        }
        NewsLogger.contentPicked(content.id)
        if (!canSend(showType)) {
            NewsLogger.sendAborted(showType)
            return
        }
        val notificationId = nextNotificationId()
        if (bannerConfig.enabled && bannerConfig.durationSeconds > 0 && !isDeviceLocked()) {
            sendPersistentNotification(content, showType, notificationId, bannerConfig.durationSeconds, bannerConfig.ringEnabled)
        } else {
            if (bannerConfig.enabled && bannerConfig.durationSeconds > 0) {
                NewsLogger.bannerSkipped("device_locked")
            } else {
                NewsLogger.bannerSkipped("banner_disabled")
            }
            sendStandardNotification(content, showType, notificationId, sticky = true)
            recordSent(showType)
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
            NewsLogger.bannerSkipped("session_lost")
            return
        }
        NewsLogger.bannerStarted(session.newsId, session.remainingTriggers)
        var firstNotification = true
        try {
            while (true) {
                val dispatch = bannerSessions.markShown(session.sessionId) ?: break
                // 首条固定响一次；后续刷新是否出声由 ring_on 决定，但不管出不出声都要重新弹横幅。
                val alert = if (dispatch.isFirstInSession) {
                    firstShowAlert
                } else {
                    refreshAlert(silent = !ringEnabled)
                }
                sendStandardNotification(
                    content,
                    showType,
                    session.notificationId,
                    alert = alert,
                    sticky = true,
                    trackSent = firstNotification,
                )
                if (firstNotification) {
                    recordSent(showType)
                    firstNotification = false
                }
                delay(BannerNotificationSessionManager.STAY_MS)
                val shouldRepeat = bannerSessions.finishStay(session.sessionId)
                if (!shouldRepeat) {
                    break
                }
                if (isDeviceLocked()) {
                    NewsLogger.bannerInterrupted("device_locked")
                    break
                }
                if (!canPostNewsNotifications()) {
                    NewsLogger.bannerInterrupted("permission_or_switch_changed")
                    break
                }
            }
        } finally {
            // 会话结束后通知保持常驻（置顶、划不掉），由用户点击时清掉。
            bannerSessions.cancel(session.sessionId)
            NewsLogger.bannerFinished(content.id)
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
        trackSent: Boolean = true,
    ) {
        val notification = createOnceNt(content, showType, notificationId, alert, sticky)
        getManager().notify(notificationId, notification)
        val channelId = if (alert.silent) NEWS_SILENT_CHANNEL_ID else NEWS_CHANNEL_ID
        NewsLogger.notifySubmitted(showType, notificationId, channelId, sticky)
        if (trackSent) {
            runCatching { TrackSDK.track("news_notification_sent", null, TrackPolicy.IMMEDIATE) }
        }
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
            R.string.notification_channel_news,
            NotificationManagerCompat.IMPORTANCE_HIGH,
            silent = alert.silent,
        )
        val contentIntent = createIntent(content.url, showType, notificationId)
        return NotificationCompat.Builder(coIntance(), channelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(ContextCompat.getColor(coIntance(), R.color.brand_red))
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .applyAlertBehavior(alert)
            .setContentIntent(contentIntent)
            .apply {
                if (sticky) {
                    setOngoing(true)
                    setDeleteIntent(createDismissIntent(showType, notificationId))
                }
            }
            .setShowWhen(false)
            .setCustomContentView(createCollapsedNewsView(content, contentIntent))
            .setCustomBigContentView(createLargeNewsView(content, contentIntent))
            .setCustomHeadsUpContentView(createCollapsedNewsView(content, contentIntent))
            .build()
            .apply {
                // 置顶（常驻、划不掉）：Android 14 起 FLAG_ONGOING_EVENT 已经挡不住用户划掉，
                // 只能靠 FLAG_NO_CLEAR；点击时由 GuideActivity 主动清掉本条。
                if (sticky) flags = flags or Notification.FLAG_NO_CLEAR
            }
    }

    private fun NotificationCompat.Builder.applyAlertBehavior(alert: AlertProfile): NotificationCompat.Builder {
        if (alert.silent) {
            // 只用静音渠道 + 关掉声音/振动。不要用 setSilent()/setOnlyAlertOnce()：
            // 平台会附带分组、仅提醒一次语义，后续刷新就弹不出横幅了。
            return setDefaults(0).setSound(null).setVibrate(null)
        }
        if (alert.vibrate) {
            return setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVibrate(longArrayOf(0, 300, 200, 300))
        }
        return setDefaults(NotificationCompat.DEFAULT_SOUND).setVibrate(null)
    }

    private data class AlertProfile(
        val silent: Boolean,
        val vibrate: Boolean,
    )

    private val firstShowAlert = AlertProfile(
        silent = false,
        vibrate = true,
    )

    /** banner 刷新：ring_on 只决定是否出声，不决定是否弹出。 */
    private fun refreshAlert(silent: Boolean) = AlertProfile(
        silent = silent,
        vibrate = false,
    )

    private fun createCollapsedNewsView(
        content: NotificationContent,
        contentIntent: PendingIntent,
    ): RemoteViews = NewsNotificationViewBinder.collapsed(coIntance(), content, contentIntent)

    private fun createLargeNewsView(
        content: NotificationContent,
        contentIntent: PendingIntent,
    ): RemoteViews = NewsNotificationViewBinder.expanded(coIntance(), content, contentIntent)

    /** 点击统一走启动页热启动（清栈 + 回主页），URL 非空时落地内置浏览器打开该页面。 */
    private fun createIntent(url: String, showType: Int, notificationId: Int): PendingIntent {
        val intent = GuideActivity.createNotificationClickIntent(coIntance()).apply {
            if (url.isNotBlank()) {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(url)
            }
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

    private fun createChannel(
        id: String,
        nameRes: Int,
        importance: Int,
        silent: Boolean,
    ) {
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
        val last = prefs.getInt(KEY_LAST_NOTIFICATION_ID, NewsGateRules.FIRST_DYNAMIC_NOTIFICATION_ID - 1)
        val next = NewsGateRules.nextNotificationId(last)
        prefs.edit().putInt(KEY_LAST_NOTIFICATION_ID, next).commit()
        next
    }

    private fun legacyNotificationId(showType: Int): Int = when (showType) {
        CO_S_TYPE_UNLOCK -> 60111
        CO_S_TYPE_INTERVAL -> 60101
        else -> 60121
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