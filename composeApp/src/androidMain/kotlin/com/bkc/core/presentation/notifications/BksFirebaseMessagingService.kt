package com.bkc.core.presentation.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BksFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmTokenRegistrar.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AppNotifications.init(this)
        val data = message.data
        val chatId = data["chatId"].orEmpty()
        val title = data["title"]
            ?: message.notification?.title
            ?: "Новое сообщение"
        val body = data["body"]
            ?: message.notification?.body
            ?: "Откройте чат, чтобы посмотреть сообщение"
        val avatarUrl = data["avatarUrl"].orEmpty().ifBlank { null }
        val notificationKey = data["messageId"]?.takeIf { it.isNotBlank() }?.let { "chat:$it" }
            ?: data["eventId"]?.takeIf { it.isNotBlank() }?.let { "${data["type"].orEmpty()}:$it" }
            ?: message.messageId?.takeIf { it.isNotBlank() }?.let { "fcm:$it" }

        if (chatId.isBlank()) {
            AppNotifications.showInfo(title, body, notificationKey)
        } else {
            AppNotifications.showMessage(chatId, title, body, avatarUrl, notificationKey)
        }
    }
}
