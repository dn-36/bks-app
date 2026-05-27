package com.bkc.core.domain.chat

import com.bkc.core.domain.PlatformUser

data class Chat(
    val id: String,
    val type: String,
    val title: String = "",
    val photoUrl: String? = null,
    val ownerId: String = "",
    val otherUser: PlatformUser? = null,
    val members: List<PlatformUser> = emptyList(),
    val lastMessage: ChatMessage? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class ChatAttachment(
    val id: String,
    val messageId: String,
    val chatId: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val url: String,
    val createdAtMillis: Long,
    val localBytes: ByteArray? = null,
    val isUploading: Boolean = false
)

data class ChatMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long,
    val isPinned: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList()
) {
    val isDeleted: Boolean get() = deletedAtMillis > 0L || status == "DELETED"
}

data class PendingChatAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    val id: String = "$fileName-${bytes.size}"
}
