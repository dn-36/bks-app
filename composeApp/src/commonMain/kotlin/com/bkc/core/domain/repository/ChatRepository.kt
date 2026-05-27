package com.bkc.core.domain.repository

import com.bkc.core.domain.chat.Chat
import com.bkc.core.domain.chat.ChatMessage
import com.bkc.core.domain.chat.PendingChatAttachment
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun startRealtime()
    fun observeChats(): Flow<List<Chat>>
    fun observeMessages(chatId: String): Flow<List<ChatMessage>>
    fun observeUnreadChatCounts(): Flow<Map<String, Int>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun refreshChats(query: String = "")
    suspend fun refreshMessages(chatId: String)
    fun markChatRead(chatId: String)
    suspend fun createDirectChat(peerUserId: String): Chat
    suspend fun createGroupChat(
        title: String,
        memberUserIds: List<String>,
        photoFileName: String?,
        photoBytes: ByteArray?
    ): Chat
    suspend fun getChat(chatId: String): Chat
    suspend fun updateGroupChat(chatId: String, title: String, photoFileName: String?, photoBytes: ByteArray?): Chat
    suspend fun addGroupMembers(chatId: String, userIds: List<String>): Chat
    suspend fun removeGroupMember(chatId: String, userId: String): Chat
    suspend fun getMessages(chatId: String): List<ChatMessage>
    suspend fun sendMessage(chatId: String, text: String, attachments: List<PendingChatAttachment> = emptyList()): ChatMessage
    suspend fun editMessage(messageId: String, text: String): ChatMessage
    suspend fun deleteMessage(messageId: String): ChatMessage
    suspend fun deleteMessageForMe(messageId: String, chatId: String)
    suspend fun pinMessage(messageId: String, pinned: Boolean): ChatMessage
    suspend fun deleteChat(chatId: String)
}
