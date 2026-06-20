package com.bkc.core.data

import com.bkc.core.domain.PlatformUser
import com.bkc.core.domain.chat.Chat
import com.bkc.core.domain.chat.ChatAttachment
import com.bkc.core.domain.chat.ChatMessage
import com.bkc.core.domain.chat.PendingChatAttachment
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.network.ApiConfig
import com.bkc.core.presentation.notifications.AppNotifications
import com.bkc.core.presentation.platform.isDesktop
import com.bkc.core.presentation.requests.MaterialRequestsRealtimeStore
import com.bkc.core.presentation.registration.RegistrationRequestsStore
import com.bkc.core.presentation.reports.ShiftReportNotificationsStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Clock

class ServerChatRepository(
    private val userSessionStore: UserSessionStore
) : ChatRepository {

    private val networkJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(networkJson)
        }
        install(WebSockets)
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chats = MutableStateFlow<List<Chat>>(emptyList())
    private val unreadChatCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()
    private val knownLastMessageIds = mutableMapOf<String, String>()
    private val clientId = "client-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"
    private var socketStarted = false
    private var pollingStarted = false
    private var currentQuery = ""

    override fun startRealtime() {
        ensureSocketStarted()
        ensureDesktopPollingStarted()
    }

    override fun observeChats(): Flow<List<Chat>> {
        startRealtime()
        scope.launch { refreshChats() }
        return chats
    }

    override fun observeMessages(chatId: String): Flow<List<ChatMessage>> {
        startRealtime()
        val flow = messageFlow(chatId)
        scope.launch { refreshMessages(chatId) }
        return flow
    }

    override fun observeUnreadChatCounts(): Flow<Map<String, Int>> {
        startRealtime()
        return unreadChatCounts
    }

    override fun observeUnreadCount(): Flow<Int> {
        startRealtime()
        return unreadChatCounts.map { counts -> counts.values.sum() }
    }

    override suspend fun refreshChats(query: String) {
        currentQuery = query
        val loadedChats = fetchChatDtos(query)
        rememberLastMessages(loadedChats)
        chats.value = loadedChats.map { it.toDomain() }
    }

    override suspend fun refreshMessages(chatId: String) {
        messageFlow(chatId).value = fetchMessages(chatId)
    }

    override fun markChatRead(chatId: String) {
        unreadChatCounts.value = unreadChatCounts.value - chatId
    }

    override suspend fun createDirectChat(peerUserId: String): Chat {
        val response = client.post("${ApiConfig.BASE_URL}/chats/direct") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(CreateDirectChatRequest(peerUserId))
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain().also {
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun createGroupChat(
        title: String,
        memberUserIds: List<String>,
        photoFileName: String?,
        photoBytes: ByteArray?
    ): Chat {
        val response = client.post("${ApiConfig.BASE_URL}/chats/groups") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateGroupChatRequest(
                    title = title,
                    memberUserIds = memberUserIds,
                    photoFileName = photoFileName.orEmpty(),
                    photoBase64 = photoBytes.toBase64OrEmpty()
                )
            )
        }
        if (response.status.value == 404) {
            throw IllegalStateException("Групповые чаты пока недоступны на сервере. Обновите серверную часть приложения.")
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain().also {
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun getChat(chatId: String): Chat {
        val response = client.get("${ApiConfig.BASE_URL}/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain()
    }

    override suspend fun updateGroupChat(
        chatId: String,
        title: String,
        photoFileName: String?,
        photoBytes: ByteArray?
    ): Chat {
        val response = client.put("${ApiConfig.BASE_URL}/chats/$chatId/group") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                UpdateGroupChatRequest(
                    title = title,
                    photoFileName = photoFileName.orEmpty(),
                    photoBase64 = photoBytes.toBase64OrEmpty()
                )
            )
        }
        if (response.status.value == 404) {
            throw IllegalStateException("Настройки группы пока недоступны на сервере. Обновите серверную часть приложения.")
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain().also {
            upsertChat(it)
        }
    }

    override suspend fun addGroupMembers(chatId: String, userIds: List<String>): Chat {
        val response = client.post("${ApiConfig.BASE_URL}/chats/$chatId/members") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(UpdateChatMembersRequest(userIds))
        }
        if (response.status.value == 404) {
            throw IllegalStateException("Управление участниками пока недоступно на сервере. Обновите серверную часть приложения.")
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain().also {
            upsertChat(it)
        }
    }

    override suspend fun removeGroupMember(chatId: String, userId: String): Chat {
        val response = client.delete("${ApiConfig.BASE_URL}/chats/$chatId/members/$userId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        return response.body<ChatDto>().toDomain().also {
            upsertChat(it)
        }
    }

    override suspend fun getMessages(chatId: String): List<ChatMessage> {
        return fetchMessages(chatId)
    }

    private suspend fun fetchMessages(chatId: String): List<ChatMessage> {
        val response = client.get("${ApiConfig.BASE_URL}/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        return response.body<List<ChatMessageDto>>().map { it.toDomain() }
    }

    override suspend fun sendMessage(
        chatId: String,
        text: String,
        attachments: List<PendingChatAttachment>
    ): ChatMessage {
        val response = client.post("${ApiConfig.BASE_URL}/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveMessageRequest(
                    text = text,
                    clientId = clientId,
                    attachments = attachments.map {
                        ChatAttachmentInputDto(
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            fileBase64 = it.bytes.toBase64OrEmpty()
                        )
                    }
                )
            )
        }
        response.requireChatSuccess()
        return response.body<ChatMessageDto>().toDomain().also {
            rememberMessageSeen(it)
            upsertMessage(it)
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun editMessage(messageId: String, text: String): ChatMessage {
        val response = client.put("${ApiConfig.BASE_URL}/chats/messages/$messageId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(SaveMessageRequest(text))
        }
        response.requireChatSuccess()
        return response.body<ChatMessageDto>().toDomain().also {
            upsertMessage(it)
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun deleteMessage(messageId: String): ChatMessage {
        val response = client.delete("${ApiConfig.BASE_URL}/chats/messages/$messageId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        return response.body<ChatMessageDto>().toDomain().also {
            upsertMessage(it)
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun deleteMessageForMe(messageId: String, chatId: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/chats/messages/$messageId/self") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        messageFlows[chatId]?.let { flow ->
            flow.value = flow.value.filterNot { it.id == messageId }
        }
        scope.launch { refreshChats(currentQuery) }
    }

    override suspend fun pinMessage(messageId: String, pinned: Boolean): ChatMessage {
        val response = client.put("${ApiConfig.BASE_URL}/chats/messages/$messageId/pin") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(PinMessageRequest(pinned))
        }
        response.requireChatSuccess()
        return response.body<ChatMessageDto>().toDomain().also {
            upsertMessage(it)
            scope.launch { refreshChats(currentQuery) }
        }
    }

    override suspend fun deleteChat(chatId: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/chats/$chatId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        chats.value = chats.value.filterNot { it.id == chatId }
        unreadChatCounts.value = unreadChatCounts.value - chatId
        messageFlows.remove(chatId)
        knownLastMessageIds.remove(chatId)
        scope.launch { refreshChats(currentQuery) }
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    private fun ensureSocketStarted() {
        if (socketStarted) return
        socketStarted = true
        scope.launch {
            while (true) {
                val token = userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
                if (token == null) {
                    delay(2_000)
                    continue
                }
                runCatching {
                    client.webSocket(
                        request = {
                            url(chatWebSocketUrl(token))
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    ) {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val event = runCatching {
                                    networkJson.decodeFromString(ChatSocketEventDto.serializer(), frame.readText())
                                }.getOrNull()
                                if (event != null) handleSocketEvent(event)
                            }
                        }
                    }
                }
                delay(1_500)
            }
        }
    }

    private fun ensureDesktopPollingStarted() {
        if (!isDesktop() || pollingStarted) return
        pollingStarted = true
        scope.launch {
            while (true) {
                val token = userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
                if (token == null) {
                    delay(2_000)
                    continue
                }
                runCatching { pollChatsForNotifications() }
                delay(10_000)
            }
        }
    }

    private suspend fun pollChatsForNotifications() {
        val loadedChats = fetchChatDtos(query = "")
        val previousLastMessages = knownLastMessageIds.toMap()
        val domainChats = loadedChats.map { it.toDomain() }

        domainChats.forEach { chat ->
            val lastMessage = chat.lastMessage ?: return@forEach
            val previousMessageId = previousLastMessages[chat.id]
            if (previousMessageId != null && previousMessageId != lastMessage.id) {
                incrementUnread(chat.id, unreadDelta(chat.id, previousMessageId))
                showChatNotification(chat, lastMessage)
            }
            knownLastMessageIds[chat.id] = lastMessage.id
        }

        if (currentQuery.isBlank()) {
            chats.value = domainChats
        } else {
            val byId = domainChats.associateBy { it.id }
            chats.value = chats.value.map { byId[it.id] ?: it }
        }
    }

    private suspend fun fetchChatDtos(query: String): List<ChatDto> {
        val response = client.get("${ApiConfig.BASE_URL}/chats?query=${query.encodeQueryComponent()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireChatSuccess()
        return response.body()
    }

    private suspend fun handleSocketEvent(event: ChatSocketEventDto) {
        when (event.type) {
            "CONNECTED" -> runCatching {
                if (isDesktop()) pollChatsForNotifications() else refreshChats(currentQuery)
            }
            "CHAT_UPDATED" -> runCatching { refreshChats(currentQuery) }
            "REGISTRATION_REQUESTED" -> {
                if (userSessionStore.getUserOrNull()?.status == "ADMINISTRATOR") {
                    val user = event.user
                    val name = "${user?.firstName.orEmpty()} ${user?.lastName.orEmpty()}".trim()
                        .ifBlank { user?.email.orEmpty() }
                        .ifBlank { "Новый пользователь" }
                    RegistrationRequestsStore.addPendingRequest()
                    if (AppNotifications.shouldShowRealtimeNotifications()) {
                        AppNotifications.showInfo(
                            title = "Заявка на регистрацию",
                            text = "$name хочет получить доступ",
                            notificationKey = user?.uid?.let { "registration:$it" }
                        )
                    }
                }
            }
            "SHIFT_REPORT_CREATED" -> {
                if (userSessionStore.getUserOrNull()?.status == "ADMINISTRATOR") {
                    ShiftReportNotificationsStore.addUnreadReport()
                    if (AppNotifications.shouldShowRealtimeNotifications()) {
                        AppNotifications.showInfo(
                            title = "Новый отчет",
                            text = "Поступил новый отчет по сменному заданию",
                            notificationKey = null
                        )
                    }
                }
            }
            "MATERIAL_REQUEST_CREATED" -> {
                val objectId = event.objectId.orEmpty()
                if (objectId == userSessionStore.getUserOrNull()?.selectedObjectId.orEmpty()) {
                    MaterialRequestsRealtimeStore.notifyChanged(objectId)
                }
            }
            "MESSAGE_CREATED", "MESSAGE_UPDATED", "MESSAGE_DELETED" -> {
                val message = event.message?.toDomain()
                if (message != null) {
                    rememberMessageSeen(message)
                    upsertMessage(message)
                    if (event.type == "MESSAGE_CREATED" && !event.isLocalEcho()) {
                        incrementUnread(message.chatId)
                        val chat = chats.value.firstOrNull { it.id == message.chatId }
                        val sender = event.user?.toDomain() ?: chat?.senderFor(message)
                        showChatNotification(chat, message, sender)
                        runCatching { refreshChats(currentQuery) }
                        return
                    }
                }
                runCatching { refreshChats(currentQuery) }
            }
            "MESSAGE_HIDDEN" -> {
                val messageId = event.messageId.orEmpty()
                val chatId = event.chatId.orEmpty()
                if (messageId.isNotBlank() && chatId.isNotBlank()) {
                    messageFlows[chatId]?.let { flow ->
                        flow.value = flow.value.filterNot { it.id == messageId }
                    }
                }
                runCatching { refreshChats(currentQuery) }
            }
        }
    }

    private fun ChatSocketEventDto.isLocalEcho(): Boolean =
        clientId.isNotBlank() && clientId == this@ServerChatRepository.clientId

    private fun rememberLastMessages(chats: List<ChatDto>) {
        chats.forEach { chat ->
            chat.lastMessage?.let { knownLastMessageIds[chat.id] = it.id }
        }
    }

    private fun rememberMessageSeen(message: ChatMessage) {
        knownLastMessageIds[message.chatId] = message.id
    }

    private fun incrementUnread(chatId: String, delta: Int = 1) {
        if (chatId.isBlank() || delta <= 0) return
        val current = unreadChatCounts.value
        unreadChatCounts.value = current + (chatId to ((current[chatId] ?: 0) + delta))
    }

    private suspend fun unreadDelta(chatId: String, previousMessageId: String): Int {
        return runCatching {
            val messages = fetchMessages(chatId)
            val previousIndex = messages.indexOfFirst { it.id == previousMessageId }
            if (previousIndex == -1) {
                1
            } else {
                messages.drop(previousIndex + 1).count { !it.isDeleted }.coerceAtLeast(1)
            }
        }.getOrDefault(1)
    }

    private fun showChatNotification(
        chat: Chat?,
        message: ChatMessage,
        sender: PlatformUser? = chat?.senderFor(message)
    ) {
        if (!AppNotifications.shouldShowRealtimeNotifications()) return
        val isGroup = chat?.type == "GROUP"
        val senderName = sender.displayName()
        val notificationText = message.notificationText()
        AppNotifications.showMessage(
            chatId = message.chatId,
            senderName = if (isGroup) chat.title.ifBlank { "Групповой чат" } else senderName,
            text = if (isGroup) "$senderName: $notificationText" else notificationText,
            avatarUrl = if (isGroup) chat.photoUrl else sender?.avatarUrl,
            notificationKey = "chat:${message.id}"
        )
    }

    private fun Chat.senderFor(message: ChatMessage): PlatformUser? =
        members.firstOrNull { it.uid == message.senderId } ?: otherUser?.takeIf { it.uid == message.senderId }

    private fun PlatformUser?.displayName(): String =
        "${this?.firstName.orEmpty()} ${this?.lastName.orEmpty()}".trim()
            .ifBlank { this?.nickname.orEmpty() }
            .ifBlank { "Новое сообщение" }

    private fun ChatMessage.notificationText(): String {
        if (text.isNotBlank()) return text
        val attachment = attachments.firstOrNull() ?: return "Вложение"
        return when {
            attachment.mimeType.startsWith("image/") -> "Фото"
            attachment.mimeType.startsWith("video/") -> "Видео"
            else -> attachment.fileName.ifBlank { "Вложение" }
        }
    }

    private fun messageFlow(chatId: String): MutableStateFlow<List<ChatMessage>> =
        messageFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }

    private fun upsertMessage(message: ChatMessage) {
        val flow = messageFlows[message.chatId] ?: return
        flow.value = (flow.value.filterNot { it.id == message.id } + message)
            .sortedBy { it.createdAtMillis }
    }

    private fun upsertChat(chat: Chat) {
        chats.value = (chats.value.filterNot { it.id == chat.id } + chat)
            .sortedByDescending { it.updatedAtMillis }
    }
}

private suspend fun HttpResponse.requireChatSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ChatErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun ChatDto.toDomain(): Chat =
    Chat(
        id = id,
        type = type,
        title = title,
        photoUrl = photoUrl?.toAbsoluteChatApiUrl(),
        ownerId = ownerId,
        otherUser = otherUser?.toDomain(),
        members = members.map { it.toDomain() },
        lastMessage = lastMessage?.toDomain(),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun ChatMessageDto.toDomain(): ChatMessage =
    ChatMessage(
        id = id,
        chatId = chatId,
        senderId = senderId,
        text = text,
        status = status,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
        isPinned = isPinned,
        attachments = attachments.map { it.toDomain() }
    )

private fun ChatAttachmentDto.toDomain(): ChatAttachment =
    ChatAttachment(
        id = id,
        messageId = messageId,
        chatId = chatId,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        url = url.toAbsoluteChatApiUrl(),
        createdAtMillis = createdAtMillis
    )

private fun PublicChatUserDto.toDomain(): PlatformUser =
    PlatformUser(
        uid = uid,
        email = email,
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarUrl?.toAbsoluteChatApiUrl(),
        bio = bio,
        phone = phone,
        role = role,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason,
        isOnline = isOnline,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt
    )

private fun String.toAbsoluteChatApiUrl(): String {
    if (startsWith("http://") || startsWith("https://")) return this
    return "${ApiConfig.BASE_URL.trimEnd('/')}/${trimStart('/')}"
}

private fun chatWebSocketUrl(token: String? = null): String {
    val base = ApiConfig.BASE_URL.trimEnd('/')
    val wsBase = when {
        base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
        base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
        else -> base
    }
    return if (token.isNullOrBlank()) {
        "$wsBase/chats/ws"
    } else {
        "$wsBase/chats/ws?token=${token.encodeQueryComponent()}"
    }
}

private fun String.encodeQueryComponent(): String =
    replace(" ", "%20")
        .replace("@", "%40")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("?", "%3F")

@Serializable
private data class CreateDirectChatRequest(
    val peerUserId: String
)

@Serializable
private data class CreateGroupChatRequest(
    val title: String,
    val memberUserIds: List<String>,
    val photoFileName: String,
    val photoBase64: String
)

@Serializable
private data class UpdateGroupChatRequest(
    val title: String,
    val photoFileName: String,
    val photoBase64: String
)

@Serializable
private data class UpdateChatMembersRequest(
    val userIds: List<String>
)

@Serializable
private data class SaveMessageRequest(
    val text: String,
    val clientId: String = "",
    val attachments: List<ChatAttachmentInputDto> = emptyList()
)

@Serializable
private data class PinMessageRequest(
    val pinned: Boolean
)

@Serializable
private data class ChatAttachmentInputDto(
    val fileName: String,
    val mimeType: String,
    val fileBase64: String
)

@Serializable
private data class ChatDto(
    val id: String,
    val type: String,
    val title: String = "",
    val photoUrl: String? = null,
    val ownerId: String = "",
    val otherUser: PublicChatUserDto? = null,
    val members: List<PublicChatUserDto> = emptyList(),
    val lastMessage: ChatMessageDto? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

@Serializable
private data class ChatMessageDto(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long = 0L,
    val isPinned: Boolean = false,
    val attachments: List<ChatAttachmentDto> = emptyList()
)

@Serializable
private data class ChatAttachmentDto(
    val id: String,
    val messageId: String,
    val chatId: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val url: String,
    val createdAtMillis: Long
)

@Serializable
private data class ChatSocketEventDto(
    val type: String,
    val chatId: String? = null,
    val objectId: String? = null,
    val messageId: String? = null,
    val message: ChatMessageDto? = null,
    val user: PublicChatUserDto? = null,
    val clientId: String = ""
)

@Serializable
private data class PublicChatUserDto(
    val uid: String,
    val email: String? = null,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String? = null,
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String? = null,
    val isOnline: Boolean = false,
    val lastSeenAt: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
private data class ChatErrorResponse(
    val message: String? = null
)

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray?.toBase64OrEmpty(): String =
    if (this == null || isEmpty()) "" else Base64.encode(this)
