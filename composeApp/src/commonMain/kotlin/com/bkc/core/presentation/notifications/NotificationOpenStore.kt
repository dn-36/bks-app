package com.bkc.core.presentation.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatNotificationTarget(
    val chatId: String,
    val title: String,
    val avatarUrl: String? = null
)

object NotificationOpenStore {
    private val _pendingChat = MutableStateFlow<ChatNotificationTarget?>(null)
    val pendingChat: StateFlow<ChatNotificationTarget?> = _pendingChat.asStateFlow()

    fun openChat(chatId: String, title: String, avatarUrl: String? = null) {
        if (chatId.isBlank()) return
        _pendingChat.value = ChatNotificationTarget(
            chatId = chatId,
            title = title.ifBlank { "Чат" },
            avatarUrl = avatarUrl
        )
    }

    fun openChat(target: ChatNotificationTarget) {
        openChat(target.chatId, target.title, target.avatarUrl)
    }

    fun consume(target: ChatNotificationTarget) {
        if (_pendingChat.value == target) {
            _pendingChat.value = null
        }
    }
}
