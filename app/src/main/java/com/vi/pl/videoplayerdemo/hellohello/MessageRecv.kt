package com.vi.pl.videoplayerdemo.hellohello

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vi.pl.videoplayerdemo.hellohello.fcm.FcmNotificationDecision

/**
 * FCM 入口（Topic [FCM_TOPIC]）。
 * 展示与判断见 [FcmNotificationDecision]，与本地新闻通知互不影响。
 *
 * 自定义样式前提：服务端必须发 data-only（不要带 notification 节点），
 * 否则系统默认样式接管、不进 [onMessageReceived]。
 */
class MessageRecv : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        NewsLogger.fcmTokenRefreshed()
        subscribeTopic(source = "令牌刷新")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val dataKeys = message.data.keys.sorted()
        val hasNotification = message.notification != null
        NewsLogger.fcmMessageArrived(dataKeys, hasNotification)
        val data = LinkedHashMap<String, String>()
        message.data.forEach { (key, value) -> data[key] = value }
        message.notification?.let { notification ->
            if (!data.containsKey("title")) notification.title?.let { data["title"] = it }
            if (!data.containsKey("body")) notification.body?.let { data["body"] = it }
        }
        if (data.isEmpty()) {
            NewsLogger.fcmDropped("empty_payload")
            return
        }
        NewsLogger.fcmPayloadParsed(data.size, data.keys.sorted())
        FcmNotificationDecision.onMessage(data)
    }

    companion object {
        const val FCM_TOPIC = "hypervideo"

        fun subscribeTopic(source: String = "启动") {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC)
                    .addOnSuccessListener {
                        NewsLogger.fcmTopicSubscribe(started = true, success = true, source = source)
                    }
                    .addOnFailureListener {
                        NewsLogger.fcmTopicSubscribe(started = true, success = false, source = source)
                    }
            }.onFailure {
                NewsLogger.fcmTopicSubscribe(started = false, success = false, source = source)
            }
        }
    }
}