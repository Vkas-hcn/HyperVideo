package com.vi.pl.videoplayerdemo.download

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vi.pl.videoplayerdemo.MainActivity
import com.vi.pl.videoplayerdemo.R

class DownloadBarService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lifecycle onCreate: pid=${Process.myPid()}")
        runCatching { startForegroundCompat(buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground on create failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "lifecycle onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")
        runCatching { startForegroundCompat(buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground on start failed", it) }
        refreshBar()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "lifecycle onTaskRemoved: pid=${Process.myPid()}; scheduling service restart")
        runCatching {
            val restartIntent = Intent(applicationContext, DownloadBarService::class.java)
                .setAction(ACTION_SHOW_BAR)
            val pendingIntent = PendingIntent.getForegroundService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 1500L, pendingIntent)
        }.onFailure { Log.w(TAG, "schedule restart failed", it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (started === this) started = null
        super.onDestroy()
    }

    private fun refreshBar() {
        notifyBar(this)
    }

    private fun buildNotification(): Notification = createNotification(this)

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        started = this
    }

    companion object {
        private const val TAG = "DownloadBarService"
        const val NOTIFICATION_ID = 9801
        const val ACTION_SHOW_BAR = "com.vi.pl.videoplayerdemo.download.action.SHOW_BAR"
        private const val CHANNEL_ID = "download_persistent_bar"

        @Volatile private var started: DownloadBarService? = null
        @Volatile private var barTitle = ""
        @Volatile private var barProgress = 0
        @Volatile private var barDownloading = false
        @Volatile private var barIndeterminate = false

        fun updateProgress(context: Context, title: String, progress: Int, state: DownloadState) {
            val active = state == DownloadState.DOWNLOADING ||
                state == DownloadState.PREPARING ||
                state == DownloadState.READY ||
                state == DownloadState.QUEUED ||
                state == DownloadState.RESOLVING_FORMATS ||
                state == DownloadState.RESOLVING ||
                state == DownloadState.RESOLVING_PAGE ||
                state == DownloadState.RESOLVING_MANIFEST ||
                state == DownloadState.MERGING ||
                state == DownloadState.VALIDATING
            barTitle = title
            barProgress = progress.coerceIn(0, 100)
            barDownloading = active
            barIndeterminate = active && state != DownloadState.DOWNLOADING && state != DownloadState.COMPLETED
            if (!active) {
                barTitle = ""
                barProgress = 0
                barIndeterminate = false
            }
            notifyBar(context)
        }

        fun restoreIdle(context: Context) {
            updateProgress(context, "", 0, DownloadState.PAUSED)
            DownloadBarLauncher.ensureRunning(context)
        }

        fun createNotification(context: Context): Notification {
            val app = context.applicationContext
            ensureChannel(app)
            val title = if (barDownloading) {
                barTitle.ifBlank { app.getString(R.string.download_notification_title) }
            } else {
                app.getString(R.string.app_name)
            }
            val status = if (barDownloading) {
                app.getString(R.string.download_notification_progress, barProgress)
            } else {
                app.getString(R.string.download_bar_idle)
            }
            val click = PendingIntent.getActivity(
                app,
                NOTIFICATION_ID,
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val dismiss = PendingIntent.getBroadcast(
                app,
                NOTIFICATION_ID,
                Intent(app, DownloadBarDismissReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_btn)
                .setContentTitle(title)
                .setContentText(status)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(click)
                .setDeleteIntent(dismiss)
                .apply {
                    if (barDownloading) setProgress(100, barProgress, barIndeterminate)
                    else setProgress(0, 0, false)
                }
                .build()
        }

        private fun notifyBar(context: Context) {
            val app = context.applicationContext
            if (!canPost(app)) return
            runCatching {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, createNotification(app))
            }.onFailure { Log.w(TAG, "refreshBar failed", it) }
        }

        fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.download_bar_channel))
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .setShowBadge(false)
                    .build(),
            )
        }

        private fun canPost(context: Context): Boolean {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
