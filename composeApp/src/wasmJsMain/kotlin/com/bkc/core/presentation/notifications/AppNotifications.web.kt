package com.bkc.core.presentation.notifications

actual object AppNotifications {
    actual fun requestPermissionIfNeeded() = Unit
    actual fun registerPushToken() = Unit
    actual fun unregisterPushToken() = Unit
    actual fun shouldShowRealtimeNotifications(): Boolean = false
    actual fun showMessage(
        chatId: String,
        senderName: String,
        text: String,
        avatarUrl: String?,
        notificationKey: String?
    ) = Unit
    actual fun showInfo(title: String, text: String, notificationKey: String?) = Unit
}
