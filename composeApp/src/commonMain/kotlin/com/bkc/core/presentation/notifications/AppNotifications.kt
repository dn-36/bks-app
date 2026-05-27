package com.bkc.core.presentation.notifications

expect object AppNotifications {
    fun requestPermissionIfNeeded()
    fun registerPushToken()
    fun unregisterPushToken()
    fun shouldShowRealtimeNotifications(): Boolean
    fun showMessage(chatId: String, senderName: String, text: String, avatarUrl: String?, notificationKey: String?)
    fun showInfo(title: String, text: String, notificationKey: String?)
}
