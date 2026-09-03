package com.vi.pl.videoplayerdemo.hellohello

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessageRecv : FirebaseMessagingService() {
    // Future FCM data payloads should map to NotificationContent and go through HintUtil.
    override fun onMessageReceived(message: RemoteMessage) = Unit
}
