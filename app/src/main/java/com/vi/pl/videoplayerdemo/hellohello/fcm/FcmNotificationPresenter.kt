package com.vi.pl.videoplayerdemo.hellohello.fcm

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vi.pl.videoplayerdemo.HyperVideoApplication
import com.vi.pl.videoplayerdemo.R
import com.vi.pl.videoplayerdemo.hellohello.NewsLogger
import com.vi.pl.videoplayerdemo.ui.GuideActivity

internal object FcmNotificationPresenter {
    internal const val CHANNEL_ID = "fcm_news_channel_v1"
    internal const val NOTIFICATION_ID = 90_001
    private const val TAG = "FcmNews"

    fun show(content: FcmNewsContent, soundMode: Int): Boolean {
        val context = HyperVideoApplication.get()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            NewsLogger.fcmPermissionCancelled(content.id)
            return false
        }
        createChannel()
        val silent = soundMode == 1
        val clickIntent = GuideActivity.createNotificationClickIntent(context).apply {
            action = Intent.ACTION_VIEW
            content.link.takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
            putExtra(GuideActivity.EXTRA_FROM_FCM, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.onFailure { error ->
            Log.e(TAG, "FCM submit exception", error)
        }.getOrDefault(false)
    }

    private fun createChannel() {
        val context = HyperVideoApplication.get()
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_news))
                .build(),
        )
    }
}