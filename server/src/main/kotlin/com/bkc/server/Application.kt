package com.bkc.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.delete
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory
import java.security.spec.PKCS8EncodedKeySpec

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = port,
        module = Application::module
    ).start(wait = true)
}

fun Application.module(
    userRepository: UserRepository = UserRepository.fromEnvironment(),
    objectRepository: ObjectRepository = ObjectRepository.fromEnvironment(),
    projectRepository: ProjectRepository = ProjectRepository.fromEnvironment(),
    scheduleRepository: ScheduleRepository = ScheduleRepository.fromEnvironment(),
    shiftTaskRepository: ShiftTaskRepository = ShiftTaskRepository.fromEnvironment(),
    specificationRepository: SpecificationRepository = SpecificationRepository.fromEnvironment(),
    chatRepository: ChatRepository = ChatRepository.fromEnvironment(userRepository),
    chatRealtimeHub: ChatRealtimeHub = ChatRealtimeHub(),
    mailService: MailService = MailService.fromEnvironment(),
    pushNotificationService: PushNotificationService = PushNotificationService.fromEnvironment(),
    tokenService: TokenService = TokenService.fromEnvironment()
) {
    val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val devLoginEnabled = System.getenv("BKS_DEV_LOGIN_ENABLED") == "true"

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }

    install(WebSockets)

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.publicMessage))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректный запрос"))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Ошибка сервера"))
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok", service = "bks-auth"))
        }

        route("/auth") {
            post("/register") {
                val request = call.receive<RegisterRequest>().normalized()
                validateRegisterRequest(request)

                val user = userRepository.createUser(request)
                val adminIds = userRepository.adminIds()
                chatRealtimeHub.notifyUsers(
                    adminIds,
                    ChatSocketEvent(type = SOCKET_REGISTRATION_REQUESTED, user = user.toPublicResponse(includePrivate = true))
                )
                if (user.accountStatus == ACCOUNT_INACTIVE) {
                    pushScope.launch {
                        runCatching {
                            val tokens = userRepository.fcmTokensForUsers(adminIds)
                            pushNotificationService.sendRegistrationRequest(
                                tokens = tokens,
                                applicantName = user.displayName(),
                                applicantUserId = user.uid
                            )
                        }
                    }
                }

                val message = if (user.accountStatus == ACCOUNT_ACTIVE) {
                    "Аккаунт создан. Войдите в систему."
                } else {
                    ACCESS_REQUEST_CREATED_MESSAGE
                }
                call.respond(HttpStatusCode.Created, RegistrationResponse(message))
            }

            post("/login") {
                val request = call.receive<LoginRequest>().normalized()
                if (request.email.isBlank() || request.password.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Заполните email и пароль")
                }

                val user = if (request.email == DEV_ADMIN_LOGIN && request.password == DEV_ADMIN_PASSWORD) {
                    userRepository.findOrCreateDevAdmin(password = DEV_ADMIN_PASSWORD)
                } else {
                    userRepository.findByEmail(request.email)
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "Неверный email или пароль")
                }

                if (request.email != DEV_ADMIN_LOGIN && !PasswordHasher.verify(request.password, user.passwordHash)) {
                    throw ApiException(HttpStatusCode.Unauthorized, "Неверный email или пароль")
                }

                user.ensureActiveForLogin()
                userRepository.touchLastSeen(user.uid)

                call.respond(AuthResponse(token = tokenService.createToken(user.uid), user = user.toResponse()))
            }

            post("/dev-login") {
                if (!devLoginEnabled) {
                    throw ApiException(HttpStatusCode.NotFound, "Маршрут не найден")
                }

                val user = userRepository.findOrCreateDevAdmin()
                userRepository.touchLastSeen(user.uid)
                call.respond(AuthResponse(token = tokenService.createToken(user.uid), user = user.toResponse()))
            }

            post("/recover") {
                val request = call.receive<RecoverAccessRequest>().normalized()
                if (request.email.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Укажите email")
                }

                call.respond(RecoverAccessResponse("Если аккаунт найден, инструкция по восстановлению будет отправлена."))
            }

            get("/me") {
                val user = call.requireActiveUser(tokenService, userRepository)
                userRepository.touchLastSeen(user.uid)
                call.respond(user.toResponse())
            }

            put("/me") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val request = call.receive<UpdateProfileRequest>().normalized()
                validateUpdateProfileRequest(request)
                call.respond(userRepository.updateProfile(user.uid, request).toResponse())
            }

            get("/users/{uid}") {
                val uid = call.parameters["uid"].orEmpty()
                val currentUser = call.requireActiveUser(tokenService, userRepository)
                if (currentUser.uid != uid && currentUser.status != STATUS_ADMINISTRATOR) {
                    throw ApiException(HttpStatusCode.Forbidden, "Недостаточно прав")
                }

                val user = userRepository.findById(uid)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")

                call.respond(user.toResponse())
            }
        }

        route("/users") {
            get {
                val currentUser = call.requireActiveUser(tokenService, userRepository)
                val query = call.request.queryParameters["query"].orEmpty()
                val accountStatus = call.request.queryParameters["accountStatus"].orEmpty().ifBlank { null }
                call.respond(userRepository.listUsers(query, accountStatus, includePrivate = currentUser.status == STATUS_ADMINISTRATOR))
            }

            get("/{uid}/avatar") {
                val uid = call.parameters["uid"].orEmpty()
                val user = userRepository.findById(uid)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
                val avatarPath = user.avatarStoragePath
                    ?: throw ApiException(HttpStatusCode.NotFound, "Аватар не найден")
                val file = userRepository.resolveAvatarFile(avatarPath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Аватар не найден")
                }

                call.respondFile(file)
            }

            get("/{uid}") {
                val currentUser = call.requireActiveUser(tokenService, userRepository)
                val uid = call.parameters["uid"].orEmpty()
                val user = userRepository.findById(uid)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
                call.respond(user.toPublicResponse(includePrivate = currentUser.status == STATUS_ADMINISTRATOR || currentUser.uid == uid))
            }
        }

        route("/admin/users") {
            get {
                val currentUser = call.requireActiveUser(tokenService, userRepository)
                currentUser.requireAdminAccess()
                val query = call.request.queryParameters["query"].orEmpty()
                val accountStatus = call.request.queryParameters["accountStatus"].orEmpty().ifBlank { null }
                call.respond(userRepository.listUsers(query, accountStatus, includePrivate = true))
            }

            put("/{uid}/access") {
                val admin = call.requireActiveUser(tokenService, userRepository)
                admin.requireAdminAccess()
                val uid = call.parameters["uid"].orEmpty()
                if (uid == admin.uid) {
                    throw ApiException(HttpStatusCode.BadRequest, "Нельзя изменить доступ самому себе")
                }
                val request = call.receive<UpdateUserAccessRequest>().normalized()
                validateUserAccessRequest(request)
                call.respond(userRepository.updateAccess(uid, request, admin.uid).toResponse())
            }

            delete("/{uid}") {
                val admin = call.requireActiveUser(tokenService, userRepository)
                admin.requireAdminAccess()
                val uid = call.parameters["uid"].orEmpty()
                if (uid == admin.uid) {
                    throw ApiException(HttpStatusCode.BadRequest, "Нельзя удалить самого себя")
                }
                val deleteData = call.request.queryParameters["deleteData"] == "true"
                val affectedChatMembers = if (deleteData) chatRepository.deleteChatsForUser(uid) else emptyList()
                userRepository.deleteUser(uid, deleteData)
                if (affectedChatMembers.isNotEmpty()) {
                    chatRealtimeHub.notifyUsers(
                        affectedChatMembers,
                        ChatSocketEvent(type = SOCKET_CHAT_UPDATED)
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{uid}/access-history") {
                val admin = call.requireActiveUser(tokenService, userRepository)
                admin.requireAdminAccess()
                val uid = call.parameters["uid"].orEmpty()
                call.respond(userRepository.listAccessHistory(uid))
            }
        }

        route("/notifications") {
            post("/fcm-token") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val request = call.receive<SaveFcmTokenRequest>().normalized()
                validateFcmTokenRequest(request)
                userRepository.saveFcmToken(user.uid, request)
                call.respond(SaveFcmTokenResponse(saved = true))
            }

            delete("/fcm-token") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val request = call.receive<SaveFcmTokenRequest>().normalized()
                validateFcmTokenRequest(request)
                userRepository.deleteFcmToken(user.uid, request.token)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/chats") {
            webSocket("/ws") {
                val user = runCatching {
                    val header = call.request.headers[HttpHeaders.Authorization].orEmpty()
                    val token = header.removePrefix("Bearer ").takeIf { it != header && it.isNotBlank() }
                        ?: call.request.queryParameters["token"].orEmpty().takeIf { it.isNotBlank() }
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "Требуется авторизация")
                    activeUserFromToken(token, tokenService, userRepository)
                }.getOrElse {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Требуется авторизация"))
                    return@webSocket
                }
                chatRealtimeHub.join(user.uid, this)
            }

            get {
                val user = call.requireActiveUser(tokenService, userRepository)
                val query = call.request.queryParameters["query"].orEmpty()
                call.respond(chatRepository.listChats(user.uid, query))
            }

            post("/direct") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val request = call.receive<CreateDirectChatRequest>().normalized()
                validateCreateDirectChatRequest(request)
                val chat = chatRepository.createOrGetDirectChat(user.uid, request.peerUserId)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(chat.id),
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chat.id)
                )
                call.respond(HttpStatusCode.Created, chat)
            }

            post("/groups") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val request = call.receive<CreateGroupChatRequest>().normalized()
                validateCreateGroupChatRequest(request)
                val chat = chatRepository.createGroupChat(user.uid, request)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(chat.id),
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chat.id)
                )
                call.respond(HttpStatusCode.Created, chat)
            }

            get("/attachments/{attachmentId}") {
                val user = call.requireActiveUserFromHeaderOrQuery(tokenService, userRepository)
                val attachmentId = call.parameters["attachmentId"].orEmpty()
                val attachment = chatRepository.findAttachment(attachmentId)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Файл не найден")
                chatRepository.requireMemberAccess(attachment.chatId, user.uid)
                val file = chatRepository.resolveAttachmentFile(attachment.storagePath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Файл не найден")
                }
                call.respondAttachmentFile(file, attachment)
            }

            get("/attachments/{attachmentId}/{fileName}") {
                val user = call.requireActiveUserFromHeaderOrQuery(tokenService, userRepository)
                val attachmentId = call.parameters["attachmentId"].orEmpty()
                val attachment = chatRepository.findAttachment(attachmentId)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Файл не найден")
                chatRepository.requireMemberAccess(attachment.chatId, user.uid)
                val file = chatRepository.resolveAttachmentFile(attachment.storagePath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Файл не найден")
                }
                call.respondAttachmentFile(file, attachment)
            }

            get("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                call.respond(chatRepository.getChat(chatId, user.uid))
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                val previousMembers = chatRepository.deleteChat(chatId, user.uid)
                chatRealtimeHub.notifyUsers(
                    previousMembers,
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chatId)
                )
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{id}/group") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                val request = call.receive<UpdateGroupChatRequest>().normalized()
                validateUpdateGroupChatRequest(request)
                val chat = chatRepository.updateGroupChat(chatId, user.uid, request)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(chat.id),
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chat.id)
                )
                call.respond(chat)
            }

            post("/{id}/members") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                val request = call.receive<UpdateChatMembersRequest>().normalized()
                validateUpdateChatMembersRequest(request)
                val chat = chatRepository.addGroupMembers(chatId, user.uid, request.userIds)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(chat.id),
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chat.id)
                )
                call.respond(chat)
            }

            delete("/{id}/members/{userId}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                val targetUserId = call.parameters["userId"].orEmpty()
                val previousMembers = chatRepository.memberIds(chatId)
                val chat = chatRepository.removeGroupMember(chatId, user.uid, targetUserId)
                chatRealtimeHub.notifyUsers(
                    (previousMembers + chatRepository.memberIds(chat.id)).distinct(),
                    ChatSocketEvent(type = SOCKET_CHAT_UPDATED, chatId = chat.id)
                )
                call.respond(chat)
            }

            get("/{id}/photo") {
                val chatId = call.parameters["id"].orEmpty()
                val chat = chatRepository.findById(chatId)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Чат не найден")
                val photoPath = chat.photoStoragePath
                    ?: throw ApiException(HttpStatusCode.NotFound, "Фото чата не найдено")
                val file = chatRepository.resolvePhotoFile(photoPath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Фото чата не найдено")
                }

                call.respondFile(file)
            }

            get("/{id}/messages") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                call.respond(chatRepository.listMessages(chatId, user.uid))
            }

            post("/{id}/messages") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val chatId = call.parameters["id"].orEmpty()
                val request = call.receive<SendMessageRequest>().normalized()
                validateMessageRequest(request)
                val message = chatRepository.sendMessage(chatId, user.uid, request)
                val memberIds = chatRepository.memberIds(chatId)
                chatRealtimeHub.notifyUsers(
                    memberIds,
                    ChatSocketEvent(
                        type = SOCKET_MESSAGE_CREATED,
                        chatId = chatId,
                        message = message,
                        user = user.toPublicResponse(includePrivate = false),
                        clientId = request.clientId
                    )
                )
                pushScope.launch {
                    runCatching {
                        val recipientIds = memberIds.filter { it != user.uid }
                        val tokens = userRepository.fcmTokensForUsers(recipientIds)
                        if (tokens.isNotEmpty()) {
                            val chat = chatRepository.findById(chatId)
                            val senderName = user.displayName()
                            val baseBody = message.pushBody()
                            val isGroup = chat?.type == CHAT_GROUP
                            val groupTitle = chat?.title?.ifBlank { "Групповой чат" } ?: "Групповой чат"
                            val title = if (isGroup) groupTitle else senderName
                            val body = if (isGroup) "$senderName: $baseBody" else baseBody
                            val avatarUrl = when {
                                isGroup && chat.photoStoragePath != null -> "/chats/$chatId/photo"
                                !isGroup && user.avatarStoragePath != null -> "/users/${user.uid}/avatar"
                                else -> null
                            }
                            pushNotificationService.sendChatMessage(
                                tokens = tokens,
                                messageId = message.id,
                                chatId = chatId,
                                title = title,
                                body = body,
                                avatarUrl = avatarUrl
                            )
                        }
                    }
                }
                call.respond(HttpStatusCode.Created, message)
            }

            put("/messages/{messageId}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val messageId = call.parameters["messageId"].orEmpty()
                val request = call.receive<UpdateMessageRequest>().normalized()
                validateMessageText(request.text)
                val message = chatRepository.editMessage(messageId, user.uid, request.text)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(message.chatId),
                    ChatSocketEvent(type = SOCKET_MESSAGE_UPDATED, chatId = message.chatId, message = message)
                )
                call.respond(message)
            }

            put("/messages/{messageId}/pin") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val messageId = call.parameters["messageId"].orEmpty()
                val request = call.receive<PinMessageRequest>()
                val message = chatRepository.pinMessage(messageId, user.uid, request.pinned)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(message.chatId),
                    ChatSocketEvent(type = SOCKET_MESSAGE_UPDATED, chatId = message.chatId, message = message)
                )
                call.respond(message)
            }

            delete("/messages/{messageId}/self") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val messageId = call.parameters["messageId"].orEmpty()
                val chatId = chatRepository.deleteMessageForUser(messageId, user.uid)
                chatRealtimeHub.notifyUsers(
                    listOf(user.uid),
                    ChatSocketEvent(type = SOCKET_MESSAGE_HIDDEN, chatId = chatId, messageId = messageId)
                )
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/messages/{messageId}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val messageId = call.parameters["messageId"].orEmpty()
                val message = chatRepository.deleteMessage(messageId, user.uid)
                chatRealtimeHub.notifyUsers(
                    chatRepository.memberIds(message.chatId),
                    ChatSocketEvent(type = SOCKET_MESSAGE_DELETED, chatId = message.chatId, message = message)
                )
                call.respond(message)
            }
        }

        route("/objects") {
            get {
                call.requireActiveUser(tokenService, userRepository)
                call.respond(objectRepository.listObjects())
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireObjectManagement()

                val request = call.receive<SaveObjectRequest>().normalized()
                validateObjectRequest(request)
                call.respond(HttpStatusCode.Created, objectRepository.createObject(request, user.uid))
            }

            put("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireObjectManagement()

                val id = call.parameters["id"].orEmpty()
                val request = call.receive<SaveObjectRequest>().normalized()
                validateObjectRequest(request)
                call.respond(objectRepository.updateObject(id, request))
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireObjectManagement()

                val id = call.parameters["id"].orEmpty()
                objectRepository.deleteObject(id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{id}/photo") {
                val id = call.parameters["id"].orEmpty()
                val obj = objectRepository.findById(id)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                val photoPath = obj.photoStoragePath
                    ?: throw ApiException(HttpStatusCode.NotFound, "Фото объекта не найдено")
                val file = objectRepository.resolveFile(photoPath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Фото объекта не найдено")
                }

                call.respondFile(file)
            }
        }

        route("/projects") {
            get {
                call.requireActiveUser(tokenService, userRepository)
                val objectId = call.request.queryParameters["objectId"].orEmpty().ifBlank { null }
                call.respond(projectRepository.listProjects(objectId))
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)

                if (user.status != STATUS_ADMINISTRATOR) {
                    throw ApiException(HttpStatusCode.Forbidden, "Добавлять проекты может только администратор")
                }

                val request = call.receive<CreateProjectRequest>().normalized()
                validateProjectRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }
                call.respond(HttpStatusCode.Created, projectRepository.createProject(request, user.uid))
            }

            get("/{id}/file") {
                val id = call.parameters["id"].orEmpty()
                val project = projectRepository.findById(id)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Проект не найден")
                val file = projectRepository.resolveFile(project.storagePath).toFile()
                if (!file.exists()) {
                    throw ApiException(HttpStatusCode.NotFound, "Файл проекта не найден")
                }

                call.respondFile(file)
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)

                if (user.status != STATUS_ADMINISTRATOR) {
                    throw ApiException(HttpStatusCode.Forbidden, "Удалять проекты может только администратор")
                }

                val id = call.parameters["id"].orEmpty()
                projectRepository.deleteProject(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/schedules") {
            get {
                val user = call.requireActiveUser(tokenService, userRepository)
                val objectId = call.request.queryParameters["objectId"].orEmpty()
                if (objectId.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
                }
                if (objectRepository.findById(objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(
                    scheduleRepository.listTasks(
                        objectId = objectId,
                        userId = user.uid,
                        includeAllProgress = user.status == STATUS_FOREMAN || user.status == STATUS_ADMINISTRATOR
                    )
                )
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireScheduleDefinitionManagement()

                val request = call.receive<SaveScheduleTaskRequest>().normalized()
                validateScheduleTaskRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(HttpStatusCode.Created, scheduleRepository.createTask(request, user.uid))
            }

            put("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireScheduleDefinitionManagement()

                val id = call.parameters["id"].orEmpty()
                val request = call.receive<SaveScheduleTaskRequest>().normalized()
                validateScheduleTaskRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(scheduleRepository.updateTask(id, request))
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireScheduleDefinitionManagement()

                val id = call.parameters["id"].orEmpty()
                scheduleRepository.deleteTask(id)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{id}/progress") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireScheduleProgressManagement()

                val id = call.parameters["id"].orEmpty()
                val request = call.receive<SaveScheduleProgressRequest>().normalized(user)
                validateScheduleProgressRequest(request)

                call.respond(scheduleRepository.saveProgress(id, user.uid, request))
            }
        }

        route("/shift-form") {
            get {
                call.requireActiveUser(tokenService, userRepository)
                val objectId = call.request.queryParameters["objectId"].orEmpty()
                if (objectId.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
                }
                if (objectRepository.findById(objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(shiftTaskRepository.getForm(objectId))
            }

            put {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireAdminAccess()

                val request = call.receive<SaveShiftFormRequest>().normalized()
                validateShiftFormRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(shiftTaskRepository.saveForm(request, user.uid))
            }
        }

        route("/shift-reports") {
            get {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireAdminAccess()

                val objectId = call.request.queryParameters["objectId"].orEmpty()
                if (objectId.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
                }
                if (objectRepository.findById(objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                val query = call.request.queryParameters["query"].orEmpty()
                call.respond(shiftTaskRepository.listReports(objectId, query))
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireShiftReportSubmission()

                val request = call.receive<SubmitShiftReportRequest>().normalized()
                validateShiftReportRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                val report = shiftTaskRepository.submitReport(request, user)
                chatRealtimeHub.notifyUsers(
                    userRepository.adminIds(),
                    ChatSocketEvent(type = SOCKET_SHIFT_REPORT_CREATED)
                )
                call.respond(HttpStatusCode.Created, report)
            }
        }

        route("/specifications") {
            get {
                call.requireActiveUser(tokenService, userRepository)
                val objectId = call.request.queryParameters["objectId"].orEmpty()
                if (objectId.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
                }
                if (objectRepository.findById(objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(specificationRepository.listSpecifications(objectId))
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireAdminAccess()

                val request = call.receive<SaveSpecificationRequest>().normalized()
                validateSpecificationRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(HttpStatusCode.Created, specificationRepository.createSpecification(request, user.uid))
            }

            put("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireAdminAccess()

                val id = call.parameters["id"].orEmpty()
                val request = call.receive<SaveSpecificationRequest>().normalized()
                validateSpecificationRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(specificationRepository.updateSpecification(id, request))
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireAdminAccess()

                val id = call.parameters["id"].orEmpty()
                specificationRepository.deleteSpecification(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/material-requests") {
            get {
                call.requireActiveUser(tokenService, userRepository)
                val objectId = call.request.queryParameters["objectId"].orEmpty()
                if (objectId.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
                }
                if (objectRepository.findById(objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                call.respond(specificationRepository.listMaterialRequests(objectId))
            }

            post {
                val user = call.requireActiveUser(tokenService, userRepository)
                user.requireMaterialRequestManagement()

                val request = call.receive<CreateMaterialRequest>().normalized()
                validateMaterialRequest(request)
                if (objectRepository.findById(request.objectId) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
                }

                val savedRequest = specificationRepository.createMaterialRequest(request, user)
                val emailStatus = mailService.sendMaterialRequest(savedRequest)
                val response = specificationRepository.updateMaterialRequestEmailStatus(savedRequest.id, emailStatus)
                val recipientUserId = userRepository.findByEmail(response.recipientEmail)?.uid
                chatRealtimeHub.notifyUsers(
                    listOfNotNull(user.uid, recipientUserId).distinct(),
                    ChatSocketEvent(
                        type = SOCKET_MATERIAL_REQUEST_CREATED,
                        objectId = response.objectId,
                        materialRequest = response
                    )
                )
                call.respond(HttpStatusCode.Created, response)
            }

            delete("/{id}") {
                val user = call.requireActiveUser(tokenService, userRepository)
                val requestId = call.parameters["id"].orEmpty()
                specificationRepository.deleteMaterialRequest(requestId, user)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireUserId(tokenService: TokenService): String {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val token = header.removePrefix("Bearer ").takeIf { it != header && it.isNotBlank() }
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Требуется авторизация")

    return tokenService.verifyToken(token)
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Недействительный токен")
}

private fun io.ktor.server.application.ApplicationCall.requireActiveUser(
    tokenService: TokenService,
    userRepository: UserRepository
): StoredUser {
    val uid = requireUserId(tokenService)
    return activeUserById(uid, userRepository)
}

private fun io.ktor.server.application.ApplicationCall.requireActiveUserFromHeaderOrQuery(
    tokenService: TokenService,
    userRepository: UserRepository
): StoredUser {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val token = header.removePrefix("Bearer ").takeIf { it != header && it.isNotBlank() }
        ?: request.queryParameters["token"].orEmpty().takeIf { it.isNotBlank() }
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Требуется авторизация")
    return activeUserFromToken(token, tokenService, userRepository)
}

private fun activeUserFromToken(
    token: String,
    tokenService: TokenService,
    userRepository: UserRepository
): StoredUser {
    val uid = tokenService.verifyToken(token)
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Недействительный токен")
    return activeUserById(uid, userRepository)
}

private fun activeUserById(uid: String, userRepository: UserRepository): StoredUser {
    val user = userRepository.findById(uid)
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Требуется авторизация")
    user.ensureActiveForApi()
    return user
}

private suspend fun io.ktor.server.application.ApplicationCall.respondAttachmentFile(
    file: File,
    attachment: StoredChatAttachment
) {
    val size = file.length().coerceAtLeast(0L)
    val contentType = runCatching {
        ContentType.parse(attachment.mimeType.ifBlank { mimeTypeForName(attachment.fileName) })
    }.getOrDefault(ContentType.Application.OctetStream)
    response.headers.append(HttpHeaders.AcceptRanges, "bytes")
    response.headers.append(HttpHeaders.ContentDisposition, attachment.fileName.inlineContentDisposition())

    val rangeHeader = request.headers[HttpHeaders.Range]
    val range = rangeHeader?.parseSingleByteRange(size)
    if (rangeHeader != null && range == null) {
        response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    if (range == null) {
        response.headers.append(HttpHeaders.ContentLength, size.toString())
        respondOutputStream(contentType = contentType) {
            file.inputStream().use { input -> input.copyTo(this) }
        }
        return
    }

    val length = range.last - range.first + 1
    response.headers.append(HttpHeaders.ContentLength, length.toString())
    response.headers.append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
    respondOutputStream(contentType = contentType, status = HttpStatusCode.PartialContent) {
        file.inputStream().use { input ->
            input.skipFully(range.first)
            val buffer = ByteArray(8192)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

private fun String.parseSingleByteRange(size: Long): LongRange? {
    if (size <= 0L || !startsWith("bytes=")) return null
    val value = removePrefix("bytes=").substringBefore(",").trim()
    val dashIndex = value.indexOf('-')
    if (dashIndex < 0) return null

    val startText = value.substring(0, dashIndex).trim()
    val endText = value.substring(dashIndex + 1).trim()
    val start: Long
    val end: Long
    if (startText.isBlank()) {
        val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        start = (size - suffixLength).coerceAtLeast(0L)
        end = size - 1
    } else {
        start = startText.toLongOrNull() ?: return null
        end = endText.toLongOrNull() ?: (size - 1)
    }

    if (start < 0L || end < start || start >= size) return null
    return start..end.coerceAtMost(size - 1)
}

private fun String.inlineContentDisposition(): String {
    val fileName = ifBlank { "attachment" }
    val fallback = fileName.map { char ->
        when {
            char == '"' || char == '\\' -> '_'
            char.code in 0x20..0x7e -> char
            else -> '_'
        }
    }.joinToString("").ifBlank { "attachment" }
    return "inline; filename=\"$fallback\"; filename*=UTF-8''${fileName.encodeHeaderValue()}"
}

private fun String.encodeHeaderValue(): String =
    encodeToByteArray().joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        val char = value.toChar()
        if (
            char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == '~'
        ) {
            char.toString()
        } else {
            "%${value.toString(16).uppercase().padStart(2, '0')}"
        }
    }

private fun java.io.InputStream.skipFully(bytesToSkip: Long) {
    var remaining = bytesToSkip
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped <= 0L) {
            if (read() == -1) break
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

private fun RegisterRequest.normalized(): RegisterRequest =
    run {
        val normalizedRole = role.trim().uppercase()
        val normalizedStatus = status.trim().uppercase().ifBlank {
            defaultStatusForRole(normalizedRole)
        }

        copy(
            email = email.trim().lowercase(),
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            nickname = nickname.trim(),
            role = roleForStatus(normalizedStatus, normalizedRole),
            status = normalizedStatus
        )
    }

private fun LoginRequest.normalized(): LoginRequest =
    copy(email = email.trim().lowercase())

private fun RecoverAccessRequest.normalized(): RecoverAccessRequest =
    copy(email = email.trim().lowercase())

private fun UpdateProfileRequest.normalized(): UpdateProfileRequest =
    copy(
        email = email.trim().lowercase(),
        firstName = firstName.trim(),
        lastName = lastName.trim(),
        nickname = nickname.trim(),
        bio = bio.trim(),
        phone = phone.trim(),
        avatarFileName = avatarFileName.trim(),
        avatarBase64 = avatarBase64.trim()
    )

private fun UpdateUserAccessRequest.normalized(): UpdateUserAccessRequest =
    copy(
        accountStatus = accountStatus.trim().uppercase(),
        blockedReason = blockedReason.trim()
    )

private fun SaveFcmTokenRequest.normalized(): SaveFcmTokenRequest =
    copy(
        token = token.trim(),
        platform = platform.trim().uppercase().take(24).ifBlank { "ANDROID" }
    )

private fun CreateDirectChatRequest.normalized(): CreateDirectChatRequest =
    copy(peerUserId = peerUserId.trim())

private fun CreateGroupChatRequest.normalized(): CreateGroupChatRequest =
    copy(
        title = title.trim(),
        memberUserIds = memberUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        photoFileName = photoFileName.trim(),
        photoBase64 = photoBase64.trim()
    )

private fun UpdateGroupChatRequest.normalized(): UpdateGroupChatRequest =
    copy(
        title = title.trim(),
        photoFileName = photoFileName.trim(),
        photoBase64 = photoBase64.trim()
    )

private fun UpdateChatMembersRequest.normalized(): UpdateChatMembersRequest =
    copy(userIds = userIds.map { it.trim() }.filter { it.isNotBlank() }.distinct())

private fun SendMessageRequest.normalized(): SendMessageRequest =
    copy(
        text = text.trim(),
        clientId = clientId.trim().take(120),
        attachments = attachments.map { it.normalized() }.filter { it.fileBase64.isNotBlank() }
    )

private fun UpdateMessageRequest.normalized(): UpdateMessageRequest =
    copy(text = text.trim())

private fun ChatAttachmentInput.normalized(): ChatAttachmentInput =
    copy(
        fileName = fileName.trim(),
        mimeType = mimeType.trim(),
        fileBase64 = fileBase64.trim()
    )

private fun CreateProjectRequest.normalized(): CreateProjectRequest =
    copy(
        title = title.trim(),
        fileName = fileName.trim()
    )

private fun SaveSpecificationRequest.normalized(): SaveSpecificationRequest =
    copy(
        objectId = objectId.trim(),
        name = name.trim(),
        unit = unit.trim()
    )

private fun CreateMaterialRequest.normalized(): CreateMaterialRequest =
    copy(
        objectId = objectId.trim(),
        recipientEmail = recipientEmail.trim().lowercase(),
        items = items.map { it.normalized() }
    )

private fun MaterialRequestItemInput.normalized(): MaterialRequestItemInput =
    copy(specificationId = specificationId.trim())

private fun validateRegisterRequest(request: RegisterRequest) {
    if (
        request.email.isBlank() ||
        request.password.isBlank() ||
        request.firstName.isBlank() ||
        request.lastName.isBlank()
    ) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните все поля")
    }

    if (!request.email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите корректный email")
    }

    if (request.password.length < 6) {
        throw ApiException(HttpStatusCode.BadRequest, "Пароль должен быть не короче 6 символов")
    }

    if (request.nickname.isNotBlank() && !request.nickname.matches(Regex("^[A-Za-z0-9_.-]{3,32}$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Логин должен быть от 3 до 32 символов: латиница, цифры, точка, дефис или подчёркивание")
    }

    if (request.role !in VALID_ROLES) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите тип пользователя")
    }

    if (request.status !in VALID_STATUSES) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите статус пользователя")
    }
}

private fun defaultStatusForRole(role: String): String =
    if (role == ROLE_ADMIN) STATUS_FOREMAN else STATUS_ELECTRICIAN

private fun roleForStatus(status: String, fallbackRole: String = ROLE_USER): String =
    when (status) {
        STATUS_ADMINISTRATOR, STATUS_FOREMAN -> ROLE_ADMIN
        STATUS_ELECTRICIAN -> ROLE_USER
        else -> fallbackRole
    }

private fun StoredUser.requireObjectManagement() {
    if (status != STATUS_ADMINISTRATOR) {
        throw ApiException(HttpStatusCode.Forbidden, "Изменять объекты может только администратор")
    }
}

private fun StoredUser.requireAdminAccess() {
    if (status != STATUS_ADMINISTRATOR) {
        throw ApiException(HttpStatusCode.Forbidden, "Доступно только администратору")
    }
}

private fun StoredUser.ensureActiveForLogin() {
    when (accountStatus) {
        ACCOUNT_BLOCKED -> throw ApiException(
            HttpStatusCode.Forbidden,
            blockedReason.ifBlank { "Заявка на регистрацию отклонена администратором" }
        )
        ACCOUNT_INACTIVE -> throw ApiException(HttpStatusCode.Forbidden, "Заявка на регистрацию ожидает решения администратора")
        ACCOUNT_DELETED -> throw ApiException(HttpStatusCode.Forbidden, "Пользователь удален из системы")
    }
}

private fun StoredUser.ensureActiveForApi() {
    when (accountStatus) {
        ACCOUNT_BLOCKED -> throw ApiException(
            HttpStatusCode.Forbidden,
            "Пользователь заблокирован. Доступ к платформе ограничен."
        )
        ACCOUNT_INACTIVE -> throw ApiException(HttpStatusCode.Forbidden, "Аккаунт неактивен")
        ACCOUNT_DELETED -> throw ApiException(HttpStatusCode.Forbidden, "Пользователь удален из системы")
    }
}

private fun StoredUser.requireScheduleDefinitionManagement() {
    if (status != STATUS_ADMINISTRATOR) {
        throw ApiException(HttpStatusCode.Forbidden, "Формировать график может только администратор")
    }
}

private fun StoredUser.requireScheduleProgressManagement() {
    if (status != STATUS_FOREMAN && status != STATUS_ADMINISTRATOR) {
        throw ApiException(HttpStatusCode.Forbidden, "Заполнять график может только прораб или администратор")
    }
}

private fun StoredUser.requireShiftReportSubmission() {
    if (status != STATUS_FOREMAN && status != STATUS_ELECTRICIAN) {
        throw ApiException(HttpStatusCode.Forbidden, "Заполнять сменное задание может только прораб или электромонтажник")
    }
}

private fun StoredUser.requireMaterialRequestManagement() {
    if (status != STATUS_FOREMAN && status != STATUS_ADMINISTRATOR) {
        throw ApiException(HttpStatusCode.Forbidden, "Создавать заявки может только прораб или администратор")
    }
}

private fun StoredUser.displayName(): String =
    "${firstName.trim()} ${lastName.trim()}".trim().ifBlank { nickname.ifBlank { email } }

private fun UserResponse.displayName(): String =
    "${firstName.trim()} ${lastName.trim()}".trim().ifBlank { nickname.ifBlank { email } }

private fun ChatMessageResponse.pushBody(): String {
    if (text.isNotBlank()) return text.take(240)
    val firstAttachment = attachments.firstOrNull() ?: return "Новое сообщение"
    return when {
        firstAttachment.mimeType.startsWith("image/") -> "Фото"
        firstAttachment.mimeType.startsWith("video/") -> "Видео"
        else -> "Файл: ${firstAttachment.fileName}"
    }.take(240)
}

private fun SaveObjectRequest.normalized(): SaveObjectRequest =
    copy(
        name = name.trim(),
        photoFileName = photoFileName.trim(),
        photoBase64 = photoBase64.trim()
    )

private fun validateObjectRequest(request: SaveObjectRequest) {
    if (request.name.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните название объекта")
    }
}

private fun validateFcmTokenRequest(request: SaveFcmTokenRequest) {
    if (request.token.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Не получен токен уведомлений")
    }
    if (request.token.length > 4096) {
        throw ApiException(HttpStatusCode.BadRequest, "Некорректный токен уведомлений")
    }
}

private fun validateProjectRequest(request: CreateProjectRequest) {
    if (
        request.title.isBlank() ||
        request.fileName.isBlank() ||
        request.fileBase64.isBlank() ||
        request.objectId.isBlank()
    ) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните название и файл проекта")
    }
}

private fun validateSpecificationRequest(request: SaveSpecificationRequest) {
    if (request.objectId.isBlank() || request.name.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните объект и наименование материала")
    }
    if (request.initialQuantity <= 0.0) {
        throw ApiException(HttpStatusCode.BadRequest, "Плановое количество должно быть больше нуля")
    }
    if (request.remainingQuantity < 0.0 || request.remainingQuantity > request.initialQuantity) {
        throw ApiException(HttpStatusCode.BadRequest, "Остаток должен быть от 0 до планового количества")
    }
}

private fun validateMaterialRequest(request: CreateMaterialRequest) {
    if (request.objectId.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
    }
    if (!request.recipientEmail.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите корректный email получателя")
    }
    if (request.items.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Добавьте материалы в заявку")
    }
    request.items.forEach {
        if (it.specificationId.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Выберите материал")
        }
        if (it.quantity <= 0.0) {
            throw ApiException(HttpStatusCode.BadRequest, "Количество должно быть больше нуля")
        }
    }
}

private fun SaveScheduleTaskRequest.normalized(): SaveScheduleTaskRequest =
    copy(
        objectId = objectId.trim(),
        place = place.trim(),
        workType = workType.trim(),
        color = color.trim().ifBlank { "#4F8EF7" }
    )

private fun validateScheduleTaskRequest(request: SaveScheduleTaskRequest) {
    if (request.objectId.isBlank() || request.place.isBlank() || request.workType.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните место и вид работ")
    }
    if (!request.color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите цвет задачи")
    }
}

private fun SaveScheduleProgressRequest.normalized(user: StoredUser): SaveScheduleProgressRequest =
    copy(
        foremanName = foremanName.trim().ifBlank { "${user.firstName} ${user.lastName}".trim() },
        workDates = workDates.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    )

private fun SaveShiftFormRequest.normalized(): SaveShiftFormRequest =
    copy(
        objectId = objectId.trim(),
        questions = questions.map { it.normalized() }
    )

private fun SaveShiftQuestionRequest.normalized(): SaveShiftQuestionRequest =
    copy(
        prompt = prompt.trim(),
        type = type.trim().uppercase(),
        options = options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    )

private fun SubmitShiftReportRequest.normalized(): SubmitShiftReportRequest =
    copy(
        objectId = objectId.trim(),
        answers = answers.map { it.normalized() }
    )

private fun SubmitShiftAnswerRequest.normalized(): SubmitShiftAnswerRequest =
    copy(
        questionId = questionId.trim(),
        value = value.trim()
    )

private fun validateScheduleProgressRequest(request: SaveScheduleProgressRequest) {
    if (request.foremanName.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Не удалось определить ФИО прораба")
    }
    val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    if (request.workDates.any { !it.matches(dateRegex) }) {
        throw ApiException(HttpStatusCode.BadRequest, "Некорректная дата работы")
    }
}

private fun validateShiftFormRequest(request: SaveShiftFormRequest) {
    if (request.objectId.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
    }
    if (request.questions.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Добавьте хотя бы один вопрос")
    }
    request.questions.forEach { question ->
        if (question.prompt.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Заполните текст вопроса")
        }
        if (question.type !in VALID_SHIFT_QUESTION_TYPES) {
            throw ApiException(HttpStatusCode.BadRequest, "Выберите тип вопроса")
        }
        if (question.type == SHIFT_QUESTION_CHOICE && question.options.size < 2) {
            throw ApiException(HttpStatusCode.BadRequest, "Для выбора добавьте минимум два варианта")
        }
    }
}

private fun validateShiftReportRequest(request: SubmitShiftReportRequest) {
    if (request.objectId.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите объект")
    }
    if (request.answers.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните форму")
    }
    request.answers.forEach {
        if (it.questionId.isBlank() || it.value.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Ответьте на все вопросы")
        }
    }
}

private fun validateUpdateProfileRequest(request: UpdateProfileRequest) {
    if (request.firstName.isBlank() || request.lastName.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Заполните имя и фамилию")
    }
    if (request.email.isNotBlank() && !request.email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите корректный email")
    }
    if (!request.nickname.matches(Regex("^[A-Za-z0-9_.-]{3,32}$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "Логин должен быть от 3 до 32 символов: латиница, цифры, точка, дефис или подчёркивание")
    }
    if (request.bio.length > 500) {
        throw ApiException(HttpStatusCode.BadRequest, "Описание не должно быть длиннее 500 символов")
    }
}

private fun validateUserAccessRequest(request: UpdateUserAccessRequest) {
    if (request.accountStatus !in VALID_ACCOUNT_STATUSES) {
        throw ApiException(HttpStatusCode.BadRequest, "Некорректный статус доступа")
    }
}

private fun validateCreateDirectChatRequest(request: CreateDirectChatRequest) {
    if (request.peerUserId.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите пользователя")
    }
}

private fun validateCreateGroupChatRequest(request: CreateGroupChatRequest) {
    if (request.title.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите название чата")
    }
    if (request.title.length > 80) {
        throw ApiException(HttpStatusCode.BadRequest, "Название чата слишком длинное")
    }
    if (request.memberUserIds.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Добавьте участников")
    }
    validateAttachments(request.photoBase64, request.photoFileName, "фото чата")
}

private fun validateUpdateGroupChatRequest(request: UpdateGroupChatRequest) {
    if (request.title.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите название чата")
    }
    if (request.title.length > 80) {
        throw ApiException(HttpStatusCode.BadRequest, "Название чата слишком длинное")
    }
    validateAttachments(request.photoBase64, request.photoFileName, "фото чата")
}

private fun validateUpdateChatMembersRequest(request: UpdateChatMembersRequest) {
    if (request.userIds.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Выберите пользователей")
    }
}

private fun validateMessageRequest(request: SendMessageRequest) {
    if (request.text.isBlank() && request.attachments.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите сообщение или добавьте файл")
    }
    validateMessageText(request.text, allowBlank = request.attachments.isNotEmpty())
    if (request.attachments.size > 5) {
        throw ApiException(HttpStatusCode.BadRequest, "К одному сообщению можно добавить до 5 файлов")
    }
    request.attachments.forEach { attachment ->
        validateAttachments(attachment.fileBase64, attachment.fileName, "вложение")
    }
}

private fun validateMessageText(text: String, allowBlank: Boolean = false) {
    if (!allowBlank && text.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Введите сообщение")
    }
    if (text.length > 4000) {
        throw ApiException(HttpStatusCode.BadRequest, "Сообщение слишком длинное")
    }
}

private fun validateAttachments(base64: String, fileName: String, label: String) {
    if (base64.isBlank()) return
    if (fileName.isBlank()) {
        throw ApiException(HttpStatusCode.BadRequest, "Укажите имя файла: $label")
    }
    if (base64.length > MAX_UPLOAD_BASE64_LENGTH) {
        throw ApiException(HttpStatusCode.BadRequest, "Файл слишком большой")
    }
}

private fun String.safeNickname(fallback: String): String {
    val cleaned = lowercase()
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') it else '_' }
        .joinToString("")
        .trim('_', '-', '.')
        .take(24)
    return cleaned.takeIf { it.length >= 3 } ?: fallback
}

private fun String.safeFileExtension(): String {
    val extension = substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .take(8)
    return extension.ifBlank { "bin" }
}

private fun mimeTypeForName(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

class UserRepository(
    private val dbPath: Path,
    private val avatarFilesDir: Path
) {
    init {
        Files.createDirectories(dbPath.parent)
        Files.createDirectories(avatarFilesDir)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        uid TEXT PRIMARY KEY,
                        email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        password_hash TEXT NOT NULL,
                        first_name TEXT NOT NULL,
                        last_name TEXT NOT NULL,
                        nickname TEXT NOT NULL DEFAULT '',
                        avatar_storage_path TEXT,
                        bio TEXT NOT NULL DEFAULT '',
                        phone TEXT NOT NULL DEFAULT '',
                        role TEXT NOT NULL DEFAULT 'USER',
                        status TEXT NOT NULL DEFAULT 'ELECTRICIAN',
                        account_status TEXT NOT NULL DEFAULT 'ACTIVE',
                        blocked_reason TEXT NOT NULL DEFAULT '',
                        privacy_profile_visible INTEGER NOT NULL DEFAULT 1,
                        notifications_enabled INTEGER NOT NULL DEFAULT 1,
                        last_seen_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                ensureColumn(connection, "users", "role", "TEXT NOT NULL DEFAULT 'USER'")
                ensureColumn(connection, "users", "status", "TEXT NOT NULL DEFAULT 'ELECTRICIAN'")
                ensureColumn(connection, "users", "nickname", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "users", "avatar_storage_path", "TEXT")
                ensureColumn(connection, "users", "bio", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "users", "phone", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "users", "account_status", "TEXT NOT NULL DEFAULT 'ACTIVE'")
                ensureColumn(connection, "users", "blocked_reason", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "users", "privacy_profile_visible", "INTEGER NOT NULL DEFAULT 1")
                ensureColumn(connection, "users", "notifications_enabled", "INTEGER NOT NULL DEFAULT 1")
                ensureColumn(connection, "users", "last_seen_at", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(connection, "users", "updated_at", "INTEGER NOT NULL DEFAULT 0")
                connection.createStatement().use { migration ->
                    migration.executeUpdate(
                        """
                        UPDATE users
                        SET status = 'FOREMAN'
                        WHERE role = 'ADMIN' AND status IN ('ADMIN', 'ELECTRICIAN')
                        """.trimIndent()
                    )
                    migration.executeUpdate(
                        """
                        UPDATE users
                        SET nickname = lower(substr(email, 1, instr(email, '@') - 1)) || '-' || substr(uid, 1, 8)
                        WHERE nickname = ''
                        """.trimIndent()
                    )
                    migration.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS users_nickname_unique ON users(nickname COLLATE NOCASE)")
                    migration.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS user_access_history (
                            id TEXT PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            changed_by TEXT NOT NULL,
                            old_status TEXT NOT NULL,
                            new_status TEXT NOT NULL,
                            reason TEXT NOT NULL DEFAULT '',
                            created_at_millis INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    migration.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS user_fcm_tokens (
                            token TEXT PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            platform TEXT NOT NULL,
                            created_at_millis INTEGER NOT NULL,
                            updated_at_millis INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    migration.executeUpdate("CREATE INDEX IF NOT EXISTS user_fcm_tokens_user_idx ON user_fcm_tokens(user_id)")
                }
            }
        }
    }

    fun createUser(request: RegisterRequest): UserResponse {
        val uid = UUID.randomUUID().toString()
        val now = Instant.now().toEpochMilli()
        val isFirstUser = !hasAnyUsers()
        val user = StoredUser(
            uid = uid,
            email = request.email,
            passwordHash = PasswordHasher.hash(request.password),
            firstName = request.firstName,
            lastName = request.lastName,
            nickname = request.nickname.ifBlank {
                request.email.substringBefore("@").safeNickname("user") + "-" + uid.take(8)
            },
            avatarStoragePath = null,
            bio = "",
            phone = "",
            role = request.role,
            status = request.status,
            accountStatus = if (isFirstUser) ACCOUNT_ACTIVE else ACCOUNT_INACTIVE,
            blockedReason = "",
            privacyProfileVisible = true,
            notificationsEnabled = true,
            lastSeenAt = 0L,
            updatedAt = now,
            createdAt = now
        )

        try {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (
                        uid, email, password_hash, first_name, last_name, nickname, avatar_storage_path,
                        bio, phone, role, status, account_status, blocked_reason, privacy_profile_visible,
                        notifications_enabled, last_seen_at, updated_at, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, user.uid)
                    statement.setString(2, user.email)
                    statement.setString(3, user.passwordHash)
                    statement.setString(4, user.firstName)
                    statement.setString(5, user.lastName)
                    statement.setString(6, user.nickname)
                    statement.setString(7, user.avatarStoragePath)
                    statement.setString(8, user.bio)
                    statement.setString(9, user.phone)
                    statement.setString(10, user.role)
                    statement.setString(11, user.status)
                    statement.setString(12, user.accountStatus)
                    statement.setString(13, user.blockedReason)
                    statement.setInt(14, if (user.privacyProfileVisible) 1 else 0)
                    statement.setInt(15, if (user.notificationsEnabled) 1 else 0)
                    statement.setLong(16, user.lastSeenAt)
                    statement.setLong(17, user.updatedAt)
                    statement.setLong(18, user.createdAt)
                    statement.executeUpdate()
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                val message = if (e.message?.contains("nickname", ignoreCase = true) == true) {
                    "Пользователь с таким логином уже существует"
                } else {
                    "Пользователь с таким email уже существует"
                }
                throw ApiException(HttpStatusCode.Conflict, message)
            }
            throw e
        }

        return user.toResponse()
    }

    fun findOrCreateDevAdmin(password: String? = null): StoredUser {
        findByEmail(DEV_ADMIN_EMAIL)?.let { current ->
            if (
                password == null &&
                current.role == ROLE_ADMIN &&
                current.status == STATUS_ADMINISTRATOR &&
                current.accountStatus == ACCOUNT_ACTIVE
            ) {
                return current
            }

            val now = Instant.now().toEpochMilli()
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE users
                    SET password_hash = ?, role = ?, status = ?, account_status = ?, blocked_reason = '', updated_at = ?
                    WHERE uid = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, password?.let { PasswordHasher.hash(it) } ?: current.passwordHash)
                    statement.setString(2, ROLE_ADMIN)
                    statement.setString(3, STATUS_ADMINISTRATOR)
                    statement.setString(4, ACCOUNT_ACTIVE)
                    statement.setLong(5, now)
                    statement.setString(6, current.uid)
                    statement.executeUpdate()
                }
            }

            return findById(current.uid) ?: current.copy(
                passwordHash = password?.let { PasswordHasher.hash(it) } ?: current.passwordHash,
                role = ROLE_ADMIN,
                status = STATUS_ADMINISTRATOR,
                accountStatus = ACCOUNT_ACTIVE,
                blockedReason = "",
                updatedAt = now
            )
        }

        val uid = UUID.randomUUID().toString()
        val now = Instant.now().toEpochMilli()
        val user = StoredUser(
            uid = uid,
            email = DEV_ADMIN_EMAIL,
            passwordHash = PasswordHasher.hash(password ?: UUID.randomUUID().toString()),
            firstName = "Developer",
            lastName = "Admin",
            nickname = DEV_ADMIN_NICKNAME,
            avatarStoragePath = null,
            bio = "",
            phone = "",
            role = ROLE_ADMIN,
            status = STATUS_ADMINISTRATOR,
            accountStatus = ACCOUNT_ACTIVE,
            blockedReason = "",
            privacyProfileVisible = true,
            notificationsEnabled = false,
            lastSeenAt = 0L,
            updatedAt = now,
            createdAt = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (
                    uid, email, password_hash, first_name, last_name, nickname, avatar_storage_path,
                    bio, phone, role, status, account_status, blocked_reason, privacy_profile_visible,
                    notifications_enabled, last_seen_at, updated_at, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, user.uid)
                statement.setString(2, user.email)
                statement.setString(3, user.passwordHash)
                statement.setString(4, user.firstName)
                statement.setString(5, user.lastName)
                statement.setString(6, user.nickname)
                statement.setString(7, user.avatarStoragePath)
                statement.setString(8, user.bio)
                statement.setString(9, user.phone)
                statement.setString(10, user.role)
                statement.setString(11, user.status)
                statement.setString(12, user.accountStatus)
                statement.setString(13, user.blockedReason)
                statement.setInt(14, if (user.privacyProfileVisible) 1 else 0)
                statement.setInt(15, if (user.notificationsEnabled) 1 else 0)
                statement.setLong(16, user.lastSeenAt)
                statement.setLong(17, user.updatedAt)
                statement.setLong(18, user.createdAt)
                statement.executeUpdate()
            }
        }

        return user
    }

    fun updateProfile(uid: String, request: UpdateProfileRequest): StoredUser {
        val current = findById(uid) ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
        val email = request.email.ifBlank { current.email }
        val avatarStoragePath = if (request.avatarBase64.isBlank()) {
            current.avatarStoragePath
        } else {
            current.avatarStoragePath?.let { Files.deleteIfExists(resolveAvatarFile(it)) }
            writeAvatar(uid, request)
        }
        val updatedAt = Instant.now().toEpochMilli()

        try {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE users
                    SET email = ?, first_name = ?, last_name = ?, nickname = ?, avatar_storage_path = ?, bio = ?, phone = ?,
                        privacy_profile_visible = ?, notifications_enabled = ?, updated_at = ?
                    WHERE uid = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, email)
                    statement.setString(2, request.firstName)
                    statement.setString(3, request.lastName)
                    statement.setString(4, request.nickname)
                    statement.setString(5, avatarStoragePath)
                    statement.setString(6, request.bio)
                    statement.setString(7, request.phone)
                    statement.setInt(8, if (request.privacyProfileVisible) 1 else 0)
                    statement.setInt(9, if (request.notificationsEnabled) 1 else 0)
                    statement.setLong(10, updatedAt)
                    statement.setString(11, uid)
                    statement.executeUpdate()
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                val message = if (e.message?.contains("nickname", ignoreCase = true) == true) {
                    "Пользователь с таким логином уже существует"
                } else {
                    "Пользователь с таким email уже существует"
                }
                throw ApiException(HttpStatusCode.Conflict, message)
            }
            throw e
        }

        return findById(uid) ?: current
    }

    fun adminIds(): List<String> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT uid
                FROM users
                WHERE status = ? AND account_status = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, STATUS_ADMINISTRATOR)
                statement.setString(2, ACCOUNT_ACTIVE)
                statement.executeQuery().use { result ->
                    val ids = mutableListOf<String>()
                    while (result.next()) ids += result.getString("uid")
                    return ids
                }
            }
        }
    }

    fun saveFcmToken(userId: String, request: SaveFcmTokenRequest) {
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_fcm_tokens (token, user_id, platform, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(token) DO UPDATE SET
                    user_id = excluded.user_id,
                    platform = excluded.platform,
                    updated_at_millis = excluded.updated_at_millis
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.token)
                statement.setString(2, userId)
                statement.setString(3, request.platform)
                statement.setLong(4, now)
                statement.setLong(5, now)
                statement.executeUpdate()
            }
        }
    }

    fun deleteFcmToken(userId: String, token: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM user_fcm_tokens WHERE user_id = ? AND token = ?").use { statement ->
                statement.setString(1, userId)
                statement.setString(2, token)
                statement.executeUpdate()
            }
        }
    }

    fun fcmTokensForUsers(userIds: List<String>): List<String> {
        val ids = userIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT t.token
                FROM user_fcm_tokens t
                JOIN users u ON u.uid = t.user_id
                WHERE t.user_id IN ($placeholders)
                    AND u.account_status = ?
                    AND u.notifications_enabled = 1
                """.trimIndent()
            ).use { statement ->
                ids.forEachIndexed { index, userId -> statement.setString(index + 1, userId) }
                statement.setString(ids.size + 1, ACCOUNT_ACTIVE)
                statement.executeQuery().use { result ->
                    val tokens = mutableListOf<String>()
                    while (result.next()) tokens += result.getString("token")
                    return tokens
                }
            }
        }
    }

    fun listUsers(query: String, accountStatus: String?, includePrivate: Boolean): List<PublicUserResponse> {
        val normalizedStatus = accountStatus?.uppercase()?.takeIf { it in VALID_ACCOUNT_STATUSES }
        val normalizedQuery = query.trim()
        val sql = buildString {
            append(
                """
                SELECT uid, email, password_hash, first_name, last_name, nickname, avatar_storage_path, bio, phone,
                       role, status, account_status, blocked_reason, privacy_profile_visible, notifications_enabled,
                       last_seen_at, updated_at, created_at
                FROM users
                WHERE 1 = 1
                """.trimIndent()
            )
            if (normalizedQuery.isNotBlank()) {
                append(" AND (first_name LIKE ? OR last_name LIKE ? OR nickname LIKE ? OR email LIKE ?)")
            }
            if (normalizedStatus != null) {
                append(" AND account_status = ?")
            }
            append(" ORDER BY first_name COLLATE NOCASE ASC, last_name COLLATE NOCASE ASC")
        }

        connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                if (normalizedQuery.isNotBlank()) {
                    repeat(4) {
                        statement.setString(index++, "%$normalizedQuery%")
                    }
                }
                if (normalizedStatus != null) {
                    statement.setString(index, normalizedStatus)
                }
                statement.executeQuery().use { result ->
                    val users = mutableListOf<PublicUserResponse>()
                    while (result.next()) {
                        users += result.toStoredUser().toPublicResponse(includePrivate)
                    }
                    return users
                }
            }
        }
    }

    fun updateAccess(uid: String, request: UpdateUserAccessRequest, changedBy: String): StoredUser {
        val current = findById(uid) ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
        val now = Instant.now().toEpochMilli()

        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE users
                SET account_status = ?, blocked_reason = ?, updated_at = ?
                WHERE uid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.accountStatus)
                statement.setString(2, if (request.accountStatus == ACCOUNT_BLOCKED) request.blockedReason else "")
                statement.setLong(3, now)
                statement.setString(4, uid)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO user_access_history (id, user_id, changed_by, old_status, new_status, reason, created_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, uid)
                statement.setString(3, changedBy)
                statement.setString(4, current.accountStatus)
                statement.setString(5, request.accountStatus)
                statement.setString(6, request.blockedReason)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
        }

        return findById(uid) ?: current
    }

    fun deleteUser(uid: String, deleteData: Boolean) {
        val current = findById(uid) ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
        if (!deleteData) {
            markUserDeleted(current)
            return
        }

        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM user_fcm_tokens WHERE user_id = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM user_access_history WHERE user_id = ? OR changed_by = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.setString(2, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM schedule_progress WHERE user_id = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM shift_report_answers WHERE report_id IN (SELECT id FROM shift_reports WHERE sender_uid = ?)").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM shift_reports WHERE sender_uid = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM material_request_items WHERE request_id IN (SELECT id FROM material_requests WHERE sender_uid = ?)").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM material_requests WHERE sender_uid = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chat_message_hidden WHERE user_id = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chat_members WHERE user_id = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM users WHERE uid = ?").use { statement ->
                    statement.setString(1, uid)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

        current.avatarStoragePath?.let { Files.deleteIfExists(resolveAvatarFile(it)) }
    }

    private fun markUserDeleted(current: StoredUser) {
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM user_fcm_tokens WHERE user_id = ?").use { statement ->
                    statement.setString(1, current.uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE users
                    SET email = ?, password_hash = '', first_name = ?, last_name = '', nickname = ?,
                        avatar_storage_path = NULL, bio = '', phone = '', account_status = ?,
                        blocked_reason = ?, privacy_profile_visible = 0, notifications_enabled = 0,
                        updated_at = ?
                    WHERE uid = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "deleted-${current.uid.take(12)}@deleted.local")
                    statement.setString(2, DELETED_USER_DISPLAY_NAME)
                    statement.setString(3, "deleted-${current.uid.take(8)}")
                    statement.setString(4, ACCOUNT_DELETED)
                    statement.setString(5, DELETED_USER_DISPLAY_NAME)
                    statement.setLong(6, now)
                    statement.setString(7, current.uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("UPDATE schedule_progress SET foreman_name = ? WHERE user_id = ?").use { statement ->
                    statement.setString(1, DELETED_USER_DISPLAY_NAME)
                    statement.setString(2, current.uid)
                    statement.executeUpdate()
                }
                connection.prepareStatement("UPDATE shift_reports SET sender_name = ?, sender_email = '', sender_status = ? WHERE sender_uid = ?").use { statement ->
                    statement.setString(1, DELETED_USER_DISPLAY_NAME)
                    statement.setString(2, ACCOUNT_DELETED)
                    statement.setString(3, current.uid)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

        current.avatarStoragePath?.let { Files.deleteIfExists(resolveAvatarFile(it)) }
    }

    fun listAccessHistory(uid: String): List<UserAccessHistoryResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, user_id, changed_by, old_status, new_status, reason, created_at_millis
                FROM user_access_history
                WHERE user_id = ?
                ORDER BY created_at_millis DESC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, uid)
                statement.executeQuery().use { result ->
                    val history = mutableListOf<UserAccessHistoryResponse>()
                    while (result.next()) {
                        history += UserAccessHistoryResponse(
                            id = result.getString("id"),
                            userId = result.getString("user_id"),
                            changedBy = result.getString("changed_by"),
                            oldStatus = result.getString("old_status"),
                            newStatus = result.getString("new_status"),
                            reason = result.getString("reason"),
                            createdAtMillis = result.getLong("created_at_millis")
                        )
                    }
                    return history
                }
            }
        }
    }

    fun touchLastSeen(uid: String) {
        connection().use { connection ->
            connection.prepareStatement("UPDATE users SET last_seen_at = ? WHERE uid = ?").use { statement ->
                statement.setLong(1, Instant.now().toEpochMilli())
                statement.setString(2, uid)
                statement.executeUpdate()
            }
        }
    }

    fun findByEmail(email: String): StoredUser? =
        findBy("email", email)

    fun findById(uid: String): StoredUser? =
        findBy("uid", uid)

    private fun hasAnyUsers(): Boolean {
        connection().use { connection ->
            connection.prepareStatement("SELECT 1 FROM users LIMIT 1").use { statement ->
                statement.executeQuery().use { result ->
                    return result.next()
                }
            }
        }
    }

    private fun findBy(column: String, value: String): StoredUser? {
        val safeColumn = when (column) {
            "uid", "email" -> column
            else -> error("Unsupported column")
        }

        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT uid, email, password_hash, first_name, last_name, nickname, avatar_storage_path, bio, phone,
                       role, status, account_status, blocked_reason, privacy_profile_visible, notifications_enabled,
                       last_seen_at, updated_at, created_at
                FROM users
                WHERE $safeColumn = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredUser() else null
                }
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    private fun writeAvatar(uid: String, request: UpdateProfileRequest): String? {
        if (request.avatarBase64.isBlank()) return null
        val bytes = runCatching {
            Base64.getDecoder().decode(request.avatarBase64)
        }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Некорректный аватар")
        val extension = request.avatarFileName.substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            ?: "jpg"
        val storagePath = "$uid.$extension"
        Files.write(resolveAvatarFile(storagePath), bytes)
        return storagePath
    }

    fun resolveAvatarFile(storagePath: String): Path =
        avatarFilesDir.resolve(storagePath).normalize()

    private fun ensureColumn(connection: Connection, table: String, column: String, definition: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    if (result.getString("name") == column) return
                }
            }
        }

        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    companion object {
        fun fromEnvironment(): UserRepository {
            val path = System.getenv("BKS_DB_PATH") ?: "build/bks-app.db"
            val filesPath = System.getenv("BKS_USER_FILES_DIR") ?: "build/user-files"
            return UserRepository(Path.of(path), Path.of(filesPath))
        }
    }
}

class ObjectRepository(
    private val dbPath: Path,
    private val filesDir: Path
) {
    init {
        Files.createDirectories(dbPath.parent)
        Files.createDirectories(filesDir)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS work_objects (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        photo_storage_path TEXT,
                        created_by TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    fun listObjects(): List<ObjectResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name, photo_storage_path, created_by, created_at_millis, updated_at_millis
                FROM work_objects
                ORDER BY name COLLATE NOCASE ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val objects = mutableListOf<ObjectResponse>()
                    while (result.next()) {
                        objects += result.toStoredObject().toResponse()
                    }
                    return objects
                }
            }
        }
    }

    fun createObject(request: SaveObjectRequest, createdBy: String): ObjectResponse {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toEpochMilli()
        val photoStoragePath = writePhotoOrNull(id, request)
        val obj = StoredObject(
            id = id,
            name = request.name,
            photoStoragePath = photoStoragePath,
            createdBy = createdBy,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO work_objects (id, name, photo_storage_path, created_by, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, obj.id)
                statement.setString(2, obj.name)
                statement.setString(3, obj.photoStoragePath)
                statement.setString(4, obj.createdBy)
                statement.setLong(5, obj.createdAtMillis)
                statement.setLong(6, obj.updatedAtMillis)
                statement.executeUpdate()
            }
        }

        return obj.toResponse()
    }

    fun updateObject(id: String, request: SaveObjectRequest): ObjectResponse {
        val current = findById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
        val photoStoragePath = if (request.photoBase64.isBlank()) {
            current.photoStoragePath
        } else {
            current.photoStoragePath?.let { Files.deleteIfExists(resolveFile(it)) }
            writePhotoOrNull(id, request)
        }
        val updatedAt = Instant.now().toEpochMilli()

        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE work_objects
                SET name = ?, photo_storage_path = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.name)
                statement.setString(2, photoStoragePath)
                statement.setLong(3, updatedAt)
                statement.setString(4, id)
                statement.executeUpdate()
            }
        }

        return current.copy(
            name = request.name,
            photoStoragePath = photoStoragePath,
            updatedAtMillis = updatedAt
        ).toResponse()
    }

    fun deleteObject(id: String) {
        val obj = findById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Объект не найден")
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM work_objects WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
        obj.photoStoragePath?.let { Files.deleteIfExists(resolveFile(it)) }
    }

    fun findById(id: String): StoredObject? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name, photo_storage_path, created_by, created_at_millis, updated_at_millis
                FROM work_objects
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredObject() else null
                }
            }
        }
    }

    fun resolveFile(storagePath: String): Path =
        filesDir.resolve(storagePath).normalize()

    private fun writePhotoOrNull(id: String, request: SaveObjectRequest): String? {
        if (request.photoBase64.isBlank()) return null
        val photoBytes = runCatching {
            Base64.getDecoder().decode(request.photoBase64)
        }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Некорректное фото объекта")
        val extension = request.photoFileName.substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            ?: "jpg"
        val storagePath = "$id.$extension"
        Files.write(resolveFile(storagePath), photoBytes)
        return storagePath
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    companion object {
        fun fromEnvironment(): ObjectRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            val filesPath = Path.of(System.getenv("BKS_OBJECT_FILES_DIR") ?: "build/object-files")
            return ObjectRepository(dbPath, filesPath)
        }
    }
}

class ProjectRepository(
    private val dbPath: Path,
    private val filesDir: Path
) {
    init {
        Files.createDirectories(dbPath.parent)
        Files.createDirectories(filesDir)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        object_id TEXT NOT NULL DEFAULT '',
                        file_name TEXT NOT NULL DEFAULT '',
                        storage_path TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                ensureColumn(connection, "projects", "object_id", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "projects", "file_name", "TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    fun listProjects(objectId: String?): List<ProjectResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                if (objectId == null) {
                    """
                    SELECT id, title, object_id, file_name, storage_path, created_by, created_at_millis
                    FROM projects
                    ORDER BY created_at_millis DESC
                    """.trimIndent()
                } else {
                    """
                    SELECT id, title, object_id, file_name, storage_path, created_by, created_at_millis
                    FROM projects
                    WHERE object_id = ?
                    ORDER BY created_at_millis DESC
                    """.trimIndent()
                }
            ).use { statement ->
                if (objectId != null) statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val projects = mutableListOf<ProjectResponse>()
                    while (result.next()) {
                        projects += result.toStoredProject().toResponse()
                    }
                    return projects
                }
            }
        }
    }

    fun createProject(request: CreateProjectRequest, createdBy: String): ProjectResponse {
        val id = UUID.randomUUID().toString()
        val createdAt = Instant.now().toEpochMilli()
        val storagePath = "$id.${request.fileName.safeFileExtension()}"
        val fileBytes = runCatching {
            Base64.getDecoder().decode(request.fileBase64)
        }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Некорректный файл проекта")

        Files.write(resolveFile(storagePath), fileBytes)

        val project = StoredProject(
            id = id,
            title = request.title.ifBlank { request.fileName.ifBlank { "project.pdf" } },
            objectId = request.objectId,
            fileName = request.fileName,
            storagePath = storagePath,
            createdBy = createdBy,
            createdAtMillis = createdAt
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO projects (id, title, object_id, file_name, storage_path, created_by, created_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, project.id)
                statement.setString(2, project.title)
                statement.setString(3, project.objectId)
                statement.setString(4, project.fileName)
                statement.setString(5, project.storagePath)
                statement.setString(6, project.createdBy)
                statement.setLong(7, project.createdAtMillis)
                statement.executeUpdate()
            }
        }

        return project.toResponse()
    }

    fun findById(id: String): StoredProject? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, title, object_id, file_name, storage_path, created_by, created_at_millis
                FROM projects
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredProject() else null
                }
            }
        }
    }

    fun deleteProject(id: String) {
        val project = findById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Проект не найден")

        connection().use { connection ->
            connection.prepareStatement("DELETE FROM projects WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }

        Files.deleteIfExists(resolveFile(project.storagePath))
    }

    fun resolveFile(storagePath: String): Path =
        filesDir.resolve(storagePath).normalize()

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    private fun ensureColumn(connection: Connection, table: String, column: String, definition: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    if (result.getString("name") == column) return
                }
            }
        }

        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    companion object {
        fun fromEnvironment(): ProjectRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            val filesPath = Path.of(System.getenv("BKS_FILES_DIR") ?: "build/project-files")
            return ProjectRepository(dbPath, filesPath)
        }
    }
}

class ScheduleRepository(
    private val dbPath: Path
) {
    init {
        Files.createDirectories(dbPath.parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS schedule_tasks (
                        id TEXT PRIMARY KEY,
                        object_id TEXT NOT NULL,
                        place TEXT NOT NULL,
                        work_type TEXT NOT NULL,
                        color TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS schedule_progress (
                        task_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        foreman_name TEXT NOT NULL,
                        work_dates TEXT NOT NULL DEFAULT '',
                        is_done INTEGER NOT NULL DEFAULT 0,
                        updated_at_millis INTEGER NOT NULL,
                        PRIMARY KEY (task_id, user_id)
                    )
                    """.trimIndent()
                )
            }
            ensureColumn(connection, "schedule_tasks", "object_id", "TEXT NOT NULL DEFAULT ''")
            ensureColumn(connection, "schedule_tasks", "color", "TEXT NOT NULL DEFAULT '#4F8EF7'")
        }
    }

    fun listTasks(objectId: String, userId: String, includeAllProgress: Boolean): List<ScheduleTaskResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    t.id,
                    t.object_id,
                    t.place,
                    t.work_type,
                    t.color,
                    t.created_by,
                    t.created_at_millis,
                    t.updated_at_millis
                FROM schedule_tasks t
                WHERE t.object_id = ?
                ORDER BY t.place COLLATE NOCASE ASC, t.created_at_millis ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val tasks = mutableListOf<ScheduleTaskResponse>()
                    val storedTasks = mutableListOf<StoredScheduleTask>()
                    while (result.next()) {
                        storedTasks += result.toStoredScheduleTask()
                    }
                    val progresses = listProgressForTasks(
                        taskIds = storedTasks.map { it.id },
                        userId = userId.takeUnless { includeAllProgress }
                    ).groupBy { it.taskId }
                    storedTasks.forEach { task ->
                        val taskProgresses = progresses[task.id].orEmpty()
                        val currentUserProgress = taskProgresses.firstOrNull { it.userId == userId }
                        tasks += task.toResponse(
                            progress = currentUserProgress,
                            progresses = if (includeAllProgress) taskProgresses else listOfNotNull(currentUserProgress)
                        )
                    }
                    return tasks
                }
            }
        }
    }

    private fun listProgressForTasks(taskIds: List<String>, userId: String?): List<StoredScheduleProgress> {
        val ids = taskIds.distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                SELECT
                    task_id AS progress_task_id,
                    user_id AS progress_user_id,
                    foreman_name AS progress_foreman_name,
                    work_dates AS progress_work_dates,
                    is_done AS progress_is_done,
                    updated_at_millis AS progress_updated_at_millis
                FROM schedule_progress
                WHERE task_id IN ($placeholders)
                """.trimIndent()
            )
            if (userId != null) {
                append(" AND user_id = ?")
            }
            append(" ORDER BY foreman_name COLLATE NOCASE ASC, updated_at_millis DESC")
        }

        connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                ids.forEachIndexed { index, taskId -> statement.setString(index + 1, taskId) }
                if (userId != null) {
                    statement.setString(ids.size + 1, userId)
                }
                statement.executeQuery().use { result ->
                    val progresses = mutableListOf<StoredScheduleProgress>()
                    while (result.next()) {
                        val progress = result.toStoredScheduleProgressOrNull()
                        if (progress != null) progresses += progress
                    }
                    return progresses
                }
            }
        }
    }

    fun createTask(request: SaveScheduleTaskRequest, createdBy: String): ScheduleTaskResponse {
        val now = Instant.now().toEpochMilli()
        val task = StoredScheduleTask(
            id = UUID.randomUUID().toString(),
            objectId = request.objectId,
            place = request.place,
            workType = request.workType,
            color = request.color,
            createdBy = createdBy,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO schedule_tasks (id, object_id, place, work_type, color, created_by, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, task.id)
                statement.setString(2, task.objectId)
                statement.setString(3, task.place)
                statement.setString(4, task.workType)
                statement.setString(5, task.color)
                statement.setString(6, task.createdBy)
                statement.setLong(7, task.createdAtMillis)
                statement.setLong(8, task.updatedAtMillis)
                statement.executeUpdate()
            }
        }

        return task.toResponse(progress = null)
    }

    fun updateTask(id: String, request: SaveScheduleTaskRequest): ScheduleTaskResponse {
        val current = findTaskById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Задача графика не найдена")
        val updatedAt = Instant.now().toEpochMilli()

        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE schedule_tasks
                SET object_id = ?, place = ?, work_type = ?, color = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.objectId)
                statement.setString(2, request.place)
                statement.setString(3, request.workType)
                statement.setString(4, request.color)
                statement.setLong(5, updatedAt)
                statement.setString(6, id)
                statement.executeUpdate()
            }
        }

        return current.copy(
            objectId = request.objectId,
            place = request.place,
            workType = request.workType,
            color = request.color,
            updatedAtMillis = updatedAt
        ).toResponse(progress = null)
    }

    fun deleteTask(id: String) {
        findTaskById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Задача графика не найдена")

        connection().use { connection ->
            connection.prepareStatement("DELETE FROM schedule_progress WHERE task_id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM schedule_tasks WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun saveProgress(taskId: String, userId: String, request: SaveScheduleProgressRequest): ScheduleProgressResponse {
        findTaskById(taskId) ?: throw ApiException(HttpStatusCode.NotFound, "Задача графика не найдена")

        val progress = StoredScheduleProgress(
            taskId = taskId,
            userId = userId,
            foremanName = request.foremanName,
            workDates = request.workDates,
            isDone = request.isDone,
            updatedAtMillis = Instant.now().toEpochMilli()
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO schedule_progress (task_id, user_id, foreman_name, work_dates, is_done, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id, user_id) DO UPDATE SET
                    foreman_name = excluded.foreman_name,
                    work_dates = excluded.work_dates,
                    is_done = excluded.is_done,
                    updated_at_millis = excluded.updated_at_millis
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, progress.taskId)
                statement.setString(2, progress.userId)
                statement.setString(3, progress.foremanName)
                statement.setString(4, progress.workDates.joinToString(","))
                statement.setInt(5, if (progress.isDone) 1 else 0)
                statement.setLong(6, progress.updatedAtMillis)
                statement.executeUpdate()
            }
        }

        return progress.toResponse()
    }

    private fun findTaskById(id: String): StoredScheduleTask? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, place, work_type, color, created_by, created_at_millis, updated_at_millis
                FROM schedule_tasks
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredScheduleTask() else null
                }
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    private fun ensureColumn(connection: Connection, table: String, column: String, definition: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    if (result.getString("name") == column) return
                }
            }
        }

        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    companion object {
        fun fromEnvironment(): ScheduleRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            return ScheduleRepository(dbPath)
        }
    }
}

class ShiftTaskRepository(
    private val dbPath: Path
) {
    private val formJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(dbPath.parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shift_form_questions (
                        id TEXT PRIMARY KEY,
                        object_id TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        type TEXT NOT NULL,
                        options_json TEXT NOT NULL DEFAULT '[]',
                        position INTEGER NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shift_reports (
                        id TEXT PRIMARY KEY,
                        object_id TEXT NOT NULL,
                        sender_uid TEXT NOT NULL,
                        sender_name TEXT NOT NULL,
                        sender_email TEXT NOT NULL,
                        sender_status TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS shift_report_answers (
                        id TEXT PRIMARY KEY,
                        report_id TEXT NOT NULL,
                        question_id TEXT NOT NULL,
                        question_prompt TEXT NOT NULL,
                        question_type TEXT NOT NULL,
                        answer_value TEXT NOT NULL,
                        position INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS shift_form_questions_object_idx ON shift_form_questions(object_id, position)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS shift_reports_object_idx ON shift_reports(object_id, created_at_millis)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS shift_report_answers_report_idx ON shift_report_answers(report_id, position)")
            }
        }
    }

    fun getForm(objectId: String): ShiftFormResponse {
        ensureDefaultForm(objectId)
        return ShiftFormResponse(objectId = objectId, questions = readQuestions(objectId))
    }

    fun saveForm(request: SaveShiftFormRequest, updatedBy: String): ShiftFormResponse {
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM shift_form_questions WHERE object_id = ?").use { statement ->
                    statement.setString(1, request.objectId)
                    statement.executeUpdate()
                }
                request.questions.forEachIndexed { index, question ->
                    insertQuestion(
                        connection = connection,
                        objectId = request.objectId,
                        question = StoredShiftQuestion(
                            id = UUID.randomUUID().toString(),
                            objectId = request.objectId,
                            prompt = question.prompt,
                            type = question.type,
                            options = question.options.takeIf { question.type == SHIFT_QUESTION_CHOICE }.orEmpty(),
                            position = index,
                            createdBy = updatedBy,
                            createdAtMillis = now,
                            updatedAtMillis = now
                        )
                    )
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
        return getForm(request.objectId)
    }

    fun submitReport(request: SubmitShiftReportRequest, sender: StoredUser): ShiftReportResponse {
        val questions = readQuestions(request.objectId)
        if (questions.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "Форма сменного задания не настроена")
        }

        val answersByQuestionId = request.answers.associateBy { it.questionId }
        val missingQuestion = questions.firstOrNull { answersByQuestionId[it.id]?.value.isNullOrBlank() }
        if (missingQuestion != null) {
            throw ApiException(HttpStatusCode.BadRequest, "Ответьте на все вопросы")
        }

        val now = Instant.now().toEpochMilli()
        val report = StoredShiftReport(
            id = UUID.randomUUID().toString(),
            objectId = request.objectId,
            senderUid = sender.uid,
            senderName = "${sender.firstName} ${sender.lastName}".trim(),
            senderEmail = sender.email,
            senderStatus = sender.status,
            createdAtMillis = now
        )

        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO shift_reports (
                        id, object_id, sender_uid, sender_name, sender_email, sender_status, created_at_millis
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, report.id)
                    statement.setString(2, report.objectId)
                    statement.setString(3, report.senderUid)
                    statement.setString(4, report.senderName)
                    statement.setString(5, report.senderEmail)
                    statement.setString(6, report.senderStatus)
                    statement.setLong(7, report.createdAtMillis)
                    statement.executeUpdate()
                }

                questions.forEachIndexed { index, question ->
                    val answer = answersByQuestionId[question.id]?.value.orEmpty()
                    if (question.type == SHIFT_QUESTION_CHOICE && answer !in question.options) {
                        throw ApiException(HttpStatusCode.BadRequest, "Выберите один из вариантов")
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO shift_report_answers (
                            id, report_id, question_id, question_prompt, question_type, answer_value, position
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, UUID.randomUUID().toString())
                        statement.setString(2, report.id)
                        statement.setString(3, question.id)
                        statement.setString(4, question.prompt)
                        statement.setString(5, question.type)
                        statement.setString(6, answer)
                        statement.setInt(7, index)
                        statement.executeUpdate()
                    }
                }

                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

        return readReport(report.id) ?: report.toResponse(emptyList())
    }

    fun listReports(objectId: String, query: String): List<ShiftReportResponse> {
        val normalizedQuery = query.trim().lowercase()
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, sender_uid, sender_name, sender_email, sender_status, created_at_millis
                FROM shift_reports
                WHERE object_id = ?
                ORDER BY created_at_millis DESC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val reports = mutableListOf<ShiftReportResponse>()
                    while (result.next()) {
                        val report = result.toStoredShiftReport()
                        val response = report.toResponse(readAnswers(connection, report.id))
                        if (normalizedQuery.isBlank() || response.matches(normalizedQuery)) {
                            reports += response
                        }
                    }
                    return reports
                }
            }
        }
    }

    private fun ensureDefaultForm(objectId: String) {
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM shift_form_questions WHERE object_id = ?"
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    if (result.next() && result.getInt(1) > 0) return
                }
            }

            val now = Instant.now().toEpochMilli()
            connection.autoCommit = false
            try {
                defaultQuestions(objectId, now).forEach { question ->
                    insertQuestion(connection, objectId, question)
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun readQuestions(objectId: String): List<ShiftQuestionResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, prompt, type, options_json, position, created_by, created_at_millis, updated_at_millis
                FROM shift_form_questions
                WHERE object_id = ?
                ORDER BY position ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val questions = mutableListOf<ShiftQuestionResponse>()
                    while (result.next()) {
                        questions += result.toStoredShiftQuestion().toResponse()
                    }
                    return questions
                }
            }
        }
    }

    private fun readReport(reportId: String): ShiftReportResponse? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, sender_uid, sender_name, sender_email, sender_status, created_at_millis
                FROM shift_reports
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, reportId)
                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        val report = result.toStoredShiftReport()
                        report.toResponse(readAnswers(connection, report.id))
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun readAnswers(connection: Connection, reportId: String): List<ShiftReportAnswerResponse> {
        connection.prepareStatement(
            """
            SELECT id, report_id, question_id, question_prompt, question_type, answer_value, position
            FROM shift_report_answers
            WHERE report_id = ?
            ORDER BY position ASC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, reportId)
            statement.executeQuery().use { result ->
                val answers = mutableListOf<ShiftReportAnswerResponse>()
                while (result.next()) {
                    answers += result.toStoredShiftReportAnswer().toResponse()
                }
                return answers
            }
        }
    }

    private fun insertQuestion(connection: Connection, objectId: String, question: StoredShiftQuestion) {
        connection.prepareStatement(
            """
            INSERT INTO shift_form_questions (
                id, object_id, prompt, type, options_json, position, created_by, created_at_millis, updated_at_millis
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, question.id)
            statement.setString(2, objectId)
            statement.setString(3, question.prompt)
            statement.setString(4, question.type)
            statement.setString(5, question.options.toOptionsJson())
            statement.setInt(6, question.position)
            statement.setString(7, question.createdBy)
            statement.setLong(8, question.createdAtMillis)
            statement.setLong(9, question.updatedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun defaultQuestions(objectId: String, now: Long): List<StoredShiftQuestion> =
        listOf(
            StoredShiftQuestion(
                id = UUID.randomUUID().toString(),
                objectId = objectId,
                prompt = "Дата и место выполнения работ",
                type = SHIFT_QUESTION_TEXT,
                options = emptyList(),
                position = 0,
                createdBy = "system",
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            StoredShiftQuestion(
                id = UUID.randomUUID().toString(),
                objectId = objectId,
                prompt = "Какие работы выполнены за смену?",
                type = SHIFT_QUESTION_TEXT,
                options = emptyList(),
                position = 1,
                createdBy = "system",
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            StoredShiftQuestion(
                id = UUID.randomUUID().toString(),
                objectId = objectId,
                prompt = "Статус работ",
                type = SHIFT_QUESTION_CHOICE,
                options = listOf("Выполнено", "В работе", "Нужна помощь"),
                position = 2,
                createdBy = "system",
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )

    private fun List<String>.toOptionsJson(): String =
        formJson.encodeToString(ListSerializer(String.serializer()), this)

    private fun String.toOptionsList(): List<String> =
        runCatching {
            formJson.decodeFromString(ListSerializer(String.serializer()), this)
        }.getOrDefault(emptyList())

    private fun ShiftReportResponse.matches(query: String): Boolean =
        senderName.lowercase().contains(query) ||
            senderEmail.lowercase().contains(query) ||
            senderStatus.lowercase().contains(query) ||
            answers.any {
                it.questionPrompt.lowercase().contains(query) || it.value.lowercase().contains(query)
            }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    private fun ResultSet.toStoredShiftQuestion(): StoredShiftQuestion =
        StoredShiftQuestion(
            id = getString("id"),
            objectId = getString("object_id"),
            prompt = getString("prompt"),
            type = getString("type"),
            options = getString("options_json").orEmpty().toOptionsList(),
            position = getInt("position"),
            createdBy = getString("created_by"),
            createdAtMillis = getLong("created_at_millis"),
            updatedAtMillis = getLong("updated_at_millis")
        )

    companion object {
        fun fromEnvironment(): ShiftTaskRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            return ShiftTaskRepository(dbPath)
        }
    }
}

class SpecificationRepository(
    private val dbPath: Path
) {
    init {
        Files.createDirectories(dbPath.parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS specifications (
                        id TEXT PRIMARY KEY,
                        object_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        unit TEXT NOT NULL DEFAULT '',
                        initial_quantity REAL NOT NULL,
                        remaining_quantity REAL NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS material_requests (
                        id TEXT PRIMARY KEY,
                        object_id TEXT NOT NULL,
                        recipient_email TEXT NOT NULL,
                        sender_uid TEXT NOT NULL,
                        sender_name TEXT NOT NULL,
                        sender_email TEXT NOT NULL,
                        email_status TEXT NOT NULL DEFAULT '$EMAIL_PENDING',
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS material_request_items (
                        id TEXT PRIMARY KEY,
                        request_id TEXT NOT NULL,
                        specification_id TEXT NOT NULL,
                        specification_name TEXT NOT NULL,
                        unit TEXT NOT NULL DEFAULT '',
                        quantity REAL NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS specifications_object_idx ON specifications(object_id)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS material_requests_object_idx ON material_requests(object_id, created_at_millis)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS material_request_items_request_idx ON material_request_items(request_id)")
            }
        }
    }

    fun listSpecifications(objectId: String): List<SpecificationResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, name, unit, initial_quantity, remaining_quantity,
                       created_by, created_at_millis, updated_at_millis
                FROM specifications
                WHERE object_id = ?
                ORDER BY name COLLATE NOCASE ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val specifications = mutableListOf<SpecificationResponse>()
                    while (result.next()) {
                        specifications += result.toStoredSpecification().toResponse()
                    }
                    return specifications
                }
            }
        }
    }

    fun createSpecification(request: SaveSpecificationRequest, createdBy: String): SpecificationResponse {
        val now = Instant.now().toEpochMilli()
        val specification = StoredSpecification(
            id = UUID.randomUUID().toString(),
            objectId = request.objectId,
            name = request.name,
            unit = request.unit,
            initialQuantity = request.initialQuantity,
            remainingQuantity = request.remainingQuantity,
            createdBy = createdBy,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO specifications (
                    id, object_id, name, unit, initial_quantity, remaining_quantity,
                    created_by, created_at_millis, updated_at_millis
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, specification.id)
                statement.setString(2, specification.objectId)
                statement.setString(3, specification.name)
                statement.setString(4, specification.unit)
                statement.setDouble(5, specification.initialQuantity)
                statement.setDouble(6, specification.remainingQuantity)
                statement.setString(7, specification.createdBy)
                statement.setLong(8, specification.createdAtMillis)
                statement.setLong(9, specification.updatedAtMillis)
                statement.executeUpdate()
            }
        }

        return specification.toResponse()
    }

    fun updateSpecification(id: String, request: SaveSpecificationRequest): SpecificationResponse {
        val current = findSpecificationById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Материал не найден")
        val updatedAt = Instant.now().toEpochMilli()

        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE specifications
                SET object_id = ?, name = ?, unit = ?, initial_quantity = ?,
                    remaining_quantity = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.objectId)
                statement.setString(2, request.name)
                statement.setString(3, request.unit)
                statement.setDouble(4, request.initialQuantity)
                statement.setDouble(5, request.remainingQuantity)
                statement.setLong(6, updatedAt)
                statement.setString(7, id)
                statement.executeUpdate()
            }
        }

        return current.copy(
            objectId = request.objectId,
            name = request.name,
            unit = request.unit,
            initialQuantity = request.initialQuantity,
            remainingQuantity = request.remainingQuantity,
            updatedAtMillis = updatedAt
        ).toResponse()
    }

    fun deleteSpecification(id: String) {
        findSpecificationById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Материал не найден")
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM specifications WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun listMaterialRequests(objectId: String): List<MaterialRequestResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, recipient_email, sender_uid, sender_name, sender_email,
                       email_status, created_at_millis
                FROM material_requests
                WHERE object_id = ?
                ORDER BY created_at_millis DESC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, objectId)
                statement.executeQuery().use { result ->
                    val requests = mutableListOf<MaterialRequestResponse>()
                    while (result.next()) {
                        val request = result.toStoredMaterialRequest()
                        requests += request.toResponse(readMaterialRequestItems(connection, request.id))
                    }
                    return requests
                }
            }
        }
    }

    fun createMaterialRequest(request: CreateMaterialRequest, sender: StoredUser): MaterialRequestResponse {
        val groupedItems = request.items
            .groupBy { it.specificationId }
            .map { (specificationId, items) ->
                MaterialRequestItemInput(specificationId, items.sumOf { it.quantity })
            }
        val now = Instant.now().toEpochMilli()
        val requestId = UUID.randomUUID().toString()
        val senderName = "${sender.firstName} ${sender.lastName}".trim()
        val storedRequest = StoredMaterialRequest(
            id = requestId,
            objectId = request.objectId,
            recipientEmail = request.recipientEmail,
            senderUid = sender.uid,
            senderName = senderName,
            senderEmail = sender.email,
            emailStatus = EMAIL_PENDING,
            createdAtMillis = now
        )

        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO material_requests (
                        id, object_id, recipient_email, sender_uid, sender_name,
                        sender_email, email_status, created_at_millis
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, storedRequest.id)
                    statement.setString(2, storedRequest.objectId)
                    statement.setString(3, storedRequest.recipientEmail)
                    statement.setString(4, storedRequest.senderUid)
                    statement.setString(5, storedRequest.senderName)
                    statement.setString(6, storedRequest.senderEmail)
                    statement.setString(7, storedRequest.emailStatus)
                    statement.setLong(8, storedRequest.createdAtMillis)
                    statement.executeUpdate()
                }

                groupedItems.forEach { item ->
                    val specification = findSpecificationById(connection, item.specificationId)
                        ?: throw ApiException(HttpStatusCode.NotFound, "Материал не найден")
                    if (specification.objectId != request.objectId) {
                        throw ApiException(HttpStatusCode.BadRequest, "Материал относится к другому объекту")
                    }
                    if (specification.remainingQuantity + QUANTITY_EPSILON < item.quantity) {
                        throw ApiException(
                            HttpStatusCode.BadRequest,
                            "Недостаточно материала: ${specification.name}"
                        )
                    }
                    val remainingQuantity = specification.remainingQuantity - item.quantity
                    connection.prepareStatement(
                        """
                        UPDATE specifications
                        SET remaining_quantity = ?, updated_at_millis = ?
                        WHERE id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setDouble(1, remainingQuantity)
                        statement.setLong(2, now)
                        statement.setString(3, specification.id)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO material_request_items (
                            id, request_id, specification_id, specification_name, unit, quantity
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, UUID.randomUUID().toString())
                        statement.setString(2, storedRequest.id)
                        statement.setString(3, specification.id)
                        statement.setString(4, specification.name)
                        statement.setString(5, specification.unit)
                        statement.setDouble(6, item.quantity)
                        statement.executeUpdate()
                    }
                }

                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

        return findMaterialRequestById(requestId) ?: storedRequest.toResponse(emptyList())
    }

    fun updateMaterialRequestEmailStatus(id: String, emailStatus: String): MaterialRequestResponse {
        findMaterialRequestById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Заявка не найдена")
        connection().use { connection ->
            connection.prepareStatement("UPDATE material_requests SET email_status = ? WHERE id = ?").use { statement ->
                statement.setString(1, emailStatus)
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
        return findMaterialRequestById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Заявка не найдена")
    }

    fun deleteMaterialRequest(id: String, user: StoredUser) {
        val request = findMaterialRequestById(id) ?: throw ApiException(HttpStatusCode.NotFound, "Заявка не найдена")
        if (user.status != STATUS_ADMINISTRATOR && request.senderUid != user.uid) {
            throw ApiException(HttpStatusCode.Forbidden, "Можно удалить только свою заявку")
        }
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM material_request_items WHERE request_id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM material_requests WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun findSpecificationById(id: String): StoredSpecification? =
        connection().use { connection -> findSpecificationById(connection, id) }

    private fun findSpecificationById(connection: Connection, id: String): StoredSpecification? {
        connection.prepareStatement(
            """
            SELECT id, object_id, name, unit, initial_quantity, remaining_quantity,
                   created_by, created_at_millis, updated_at_millis
            FROM specifications
            WHERE id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                return if (result.next()) result.toStoredSpecification() else null
            }
        }
    }

    private fun findMaterialRequestById(id: String): MaterialRequestResponse? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, object_id, recipient_email, sender_uid, sender_name, sender_email,
                       email_status, created_at_millis
                FROM material_requests
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        val request = result.toStoredMaterialRequest()
                        request.toResponse(readMaterialRequestItems(connection, request.id))
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun readMaterialRequestItems(connection: Connection, requestId: String): List<MaterialRequestItemResponse> {
        connection.prepareStatement(
            """
            SELECT id, request_id, specification_id, specification_name, unit, quantity
            FROM material_request_items
            WHERE request_id = ?
            ORDER BY specification_name COLLATE NOCASE ASC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { result ->
                val items = mutableListOf<MaterialRequestItemResponse>()
                while (result.next()) {
                    items += result.toStoredMaterialRequestItem().toResponse()
                }
                return items
            }
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    companion object {
        private const val QUANTITY_EPSILON = 0.000_001

        fun fromEnvironment(): SpecificationRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            return SpecificationRepository(dbPath)
        }
    }
}

class MailService(
    private val config: MailConfig?
) {
    fun sendMaterialRequest(request: MaterialRequestResponse): String {
        val mailConfig = config ?: return EMAIL_NOT_CONFIGURED
        return runCatching {
            send(mailConfig, request)
            EMAIL_SENT
        }.getOrElse {
            System.err.println(
                "Failed to send material request email ${request.id} to ${request.recipientEmail}: ${it.message}"
            )
            EMAIL_FAILED
        }
    }

    private fun send(config: MailConfig, request: MaterialRequestResponse) {
        val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        var socket: Socket = if (config.ssl) {
            sslSocketFactory.createSocket(config.host, config.port)
        } else {
            Socket(config.host, config.port)
        }
        socket.soTimeout = 20_000

        try {
            var reader = socket.reader()
            var writer = socket.writer()

            reader.expect(220)
            writer.command("EHLO ${config.helloHost}")
            reader.expect(250)

            if (config.startTls && !config.ssl) {
                writer.command("STARTTLS")
                reader.expect(220)
                socket = sslSocketFactory.createSocket(socket, config.host, config.port, true)
                socket.soTimeout = 20_000
                reader = socket.reader()
                writer = socket.writer()
                writer.command("EHLO ${config.helloHost}")
                reader.expect(250)
            }

            if (config.username.isNotBlank() && config.password.isNotBlank()) {
                writer.command("AUTH LOGIN")
                reader.expect(334)
                writer.command(Base64.getEncoder().encodeToString(config.username.toByteArray(Charsets.UTF_8)))
                reader.expect(334)
                writer.command(Base64.getEncoder().encodeToString(config.password.toByteArray(Charsets.UTF_8)))
                reader.expect(235)
            }

            writer.command("MAIL FROM:<${config.from}>")
            reader.expect(250)
            writer.command("RCPT TO:<${request.recipientEmail}>")
            reader.expect(250, 251)
            writer.command("DATA")
            reader.expect(354)
            writer.write(message(config, request))
            writer.flush()
            reader.expect(250)
            runCatching {
                writer.command("QUIT")
                reader.expect(221, 250)
            }
        } finally {
            socket.close()
        }
    }

    private fun message(config: MailConfig, request: MaterialRequestResponse): String {
        val subject = "Заявка на материалы от ${request.senderName}".mimeHeader()
        val items = request.items.joinToString("\r\n") {
            "- ${it.specificationName}: ${it.quantity.quantityText()} ${it.unit}".trimEnd()
        }
        val body = """
            Новая заявка на материалы

            Отправитель: ${request.senderName}
            Email отправителя: ${request.senderEmail}
            ID объекта: ${request.objectId}

            Материалы:
            $items
        """.trimIndent()

        return buildString {
            append("From: <${config.from}>\r\n")
            append("To: <${request.recipientEmail}>\r\n")
            append("Subject: $subject\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n")
            append("\r\n")
            body.lineSequence().forEach { line ->
                append(if (line.startsWith(".")) ".$line" else line)
                append("\r\n")
            }
            append(".\r\n")
        }
    }

    private fun Socket.reader(): BufferedReader =
        BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

    private fun Socket.writer(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(getOutputStream(), Charsets.UTF_8))

    private fun BufferedWriter.command(command: String) {
        write(command)
        write("\r\n")
        flush()
    }

    private fun BufferedReader.expect(vararg expectedCodes: Int) {
        val first = readLine() ?: error("SMTP server closed connection")
        val code = first.take(3).toIntOrNull() ?: error("Invalid SMTP response")
        var line = first
        while (line.length > 3 && line[3] == '-') {
            line = readLine() ?: break
        }
        if (code !in expectedCodes) {
            error("Unexpected SMTP response: $code")
        }
    }

    private fun String.mimeHeader(): String =
        "=?UTF-8?B?${Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))}?="

    private fun Double.quantityText(): String {
        val whole = toLong()
        return if (kotlin.math.abs(this - whole) < 0.000_001) whole.toString() else toString()
    }

    companion object {
        fun fromEnvironment(): MailService {
            val host = System.getenv("BKS_SMTP_HOST").orEmpty()
            if (host.isBlank()) return MailService(null)

            val ssl = System.getenv("BKS_SMTP_SSL") == "true"
            val port = System.getenv("BKS_SMTP_PORT")?.toIntOrNull() ?: if (ssl) 465 else 587
            val username = System.getenv("BKS_SMTP_USERNAME").orEmpty()
            val password = System.getenv("BKS_SMTP_PASSWORD").orEmpty()
            val from = System.getenv("BKS_SMTP_FROM").orEmpty().ifBlank { username }
            val startTls = System.getenv("BKS_SMTP_STARTTLS")?.toBooleanStrictOrNull() ?: !ssl
            val helloHost = System.getenv("BKS_SMTP_HELO").orEmpty().ifBlank { "localhost" }

            if (from.isBlank()) return MailService(null)

            return MailService(
                MailConfig(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    from = from,
                    ssl = ssl,
                    startTls = startTls,
                    helloHost = helloHost
                )
            )
        }
    }
}

class PushNotificationService(
    private val config: FirebasePushConfig?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    @Volatile
    private var cachedAccessToken: CachedAccessToken? = null

    fun sendChatMessage(
        tokens: List<String>,
        messageId: String,
        chatId: String,
        title: String,
        body: String,
        avatarUrl: String?
    ) {
        val firebaseConfig = config ?: return
        val distinctTokens = tokens.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinctTokens.isEmpty()) return

        val accessToken = accessToken(firebaseConfig)
        distinctTokens.forEach { token ->
            runCatching {
                sendToToken(
                    config = firebaseConfig,
                    accessToken = accessToken,
                    token = token,
                    data = mapOf(
                        "type" to PUSH_TYPE_CHAT_MESSAGE,
                        "messageId" to messageId,
                        "chatId" to chatId,
                        "title" to title.take(120),
                        "body" to body.take(240),
                        "avatarUrl" to avatarUrl.orEmpty()
                    )
                )
            }
        }
    }

    fun sendRegistrationRequest(tokens: List<String>, applicantName: String, applicantUserId: String) {
        val firebaseConfig = config ?: return
        val distinctTokens = tokens.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinctTokens.isEmpty()) return

        val title = "Заявка на регистрацию"
        val body = "${applicantName.ifBlank { "Новый пользователь" }} хочет получить доступ"
        val accessToken = accessToken(firebaseConfig)
        distinctTokens.forEach { token ->
            runCatching {
                sendToToken(
                    config = firebaseConfig,
                    accessToken = accessToken,
                    token = token,
                    data = mapOf(
                        "type" to PUSH_TYPE_REGISTRATION_REQUEST,
                        "eventId" to applicantUserId,
                        "title" to title,
                        "body" to body
                    )
                )
            }
        }
    }

    @Synchronized
    private fun accessToken(config: FirebasePushConfig): String {
        val now = Instant.now().epochSecond
        cachedAccessToken?.takeIf { it.expiresAtEpochSecond - 60 > now }?.let { return it.value }

        val assertion = createJwtAssertion(config, now)
        val response = postForm(
            url = config.tokenUri,
            fields = mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion" to assertion
            )
        )
        val tokenResponse = json.decodeFromString(GoogleAccessTokenResponse.serializer(), response)
        val expiresAt = now + tokenResponse.expiresIn
        cachedAccessToken = CachedAccessToken(tokenResponse.accessToken, expiresAt)
        return tokenResponse.accessToken
    }

    private fun createJwtAssertion(config: FirebasePushConfig, nowEpochSecond: Long): String {
        val header = buildJsonObject {
            put("alg", JsonPrimitive("RS256"))
            put("typ", JsonPrimitive("JWT"))
        }
        val claims = buildJsonObject {
            put("iss", JsonPrimitive(config.clientEmail))
            put("scope", JsonPrimitive(FCM_SCOPE))
            put("aud", JsonPrimitive(config.tokenUri))
            put("iat", JsonPrimitive(nowEpochSecond))
            put("exp", JsonPrimitive(nowEpochSecond + 3600))
        }
        val unsigned = listOf(header, claims)
            .joinToString(".") { part ->
                base64Url(json.encodeToString(JsonObject.serializer(), part).toByteArray(StandardCharsets.UTF_8))
            }
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey(config.privateKey))
            update(unsigned.toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return "$unsigned.${base64Url(signature)}"
    }

    private fun sendToToken(
        config: FirebasePushConfig,
        accessToken: String,
        token: String,
        data: Map<String, String>,
        notificationTitle: String? = null,
        notificationBody: String? = null
    ) {
        val payload = buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put("token", JsonPrimitive(token))
                    if (!notificationTitle.isNullOrBlank() || !notificationBody.isNullOrBlank()) {
                        put(
                            "notification",
                            buildJsonObject {
                                put("title", JsonPrimitive(notificationTitle.orEmpty()))
                                put("body", JsonPrimitive(notificationBody.orEmpty()))
                            }
                        )
                    }
                    put(
                        "data",
                        buildJsonObject {
                            data.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                        }
                    )
                    put(
                        "android",
                        buildJsonObject {
                            put("priority", JsonPrimitive("HIGH"))
                            if (!notificationTitle.isNullOrBlank() || !notificationBody.isNullOrBlank()) {
                                put(
                                    "notification",
                                    buildJsonObject {
                                        put("channel_id", JsonPrimitive(FCM_ANDROID_CHANNEL_ID))
                                        put("sound", JsonPrimitive("default"))
                                        put("default_sound", JsonPrimitive(true))
                                        put("default_vibrate_timings", JsonPrimitive(true))
                                        put("notification_priority", JsonPrimitive("PRIORITY_HIGH"))
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        postJson(
            url = "https://fcm.googleapis.com/v1/projects/${config.projectId}/messages:send",
            authorization = "Bearer $accessToken",
            body = json.encodeToString(JsonObject.serializer(), payload)
        )
    }

    private fun postForm(url: String, fields: Map<String, String>): String {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return httpPost(
            url = url,
            contentType = "application/x-www-form-urlencoded",
            authorization = null,
            body = body
        )
    }

    private fun postJson(url: String, authorization: String, body: String): String =
        httpPost(
            url = url,
            contentType = "application/json; charset=UTF-8",
            authorization = authorization,
            body = body
        )

    private fun httpPost(url: String, contentType: String, authorization: String?, body: String): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            authorization?.let { setRequestProperty("Authorization", it) }
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            error("Firebase Cloud Messaging request failed: $status")
        }
        return response
    }

    private fun privateKey(pem: String): PrivateKey {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleaned)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    companion object {
        fun fromEnvironment(): PushNotificationService {
            val accountJson = System.getenv("BKS_FIREBASE_SERVICE_ACCOUNT_JSON")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("BKS_FIREBASE_SERVICE_ACCOUNT_PATH")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path -> runCatching { Files.readString(Path.of(path)) }.getOrNull() }
                ?: System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path -> runCatching { Files.readString(Path.of(path)) }.getOrNull() }
                ?: return PushNotificationService(null)

            return runCatching {
                val json = Json { ignoreUnknownKeys = true }
                val account = json.decodeFromString(FirebaseServiceAccount.serializer(), accountJson)
                val projectId = System.getenv("BKS_FIREBASE_PROJECT_ID")
                    ?.takeIf { it.isNotBlank() }
                    ?: account.projectId
                PushNotificationService(
                    FirebasePushConfig(
                        projectId = projectId,
                        clientEmail = account.clientEmail,
                        privateKey = account.privateKey,
                        tokenUri = account.tokenUri.ifBlank { "https://oauth2.googleapis.com/token" }
                    )
                )
            }.getOrElse {
                PushNotificationService(null)
            }
        }
    }
}

class ChatRepository(
    private val dbPath: Path,
    private val userRepository: UserRepository,
    private val filesDir: Path
) {
    private val photoFilesDir = filesDir.resolve("photos")
    private val attachmentFilesDir = filesDir.resolve("attachments")

    init {
        Files.createDirectories(dbPath.parent)
        Files.createDirectories(photoFilesDir)
        Files.createDirectories(attachmentFilesDir)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chats (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL DEFAULT 'DIRECT',
                        owner_id TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL DEFAULT '',
                        photo_storage_path TEXT,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                ensureColumn(connection, "chats", "owner_id", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "chats", "title", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(connection, "chats", "photo_storage_path", "TEXT")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_members (
                        chat_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        PRIMARY KEY (chat_id, user_id)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY,
                        chat_id TEXT NOT NULL,
                        sender_id TEXT NOT NULL,
                        text TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'SENT',
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL,
                        deleted_at_millis INTEGER NOT NULL DEFAULT 0,
                        is_pinned INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                ensureColumn(connection, "chat_messages", "is_pinned", "INTEGER NOT NULL DEFAULT 0")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_message_attachments (
                        id TEXT PRIMARY KEY,
                        message_id TEXT NOT NULL,
                        chat_id TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        storage_path TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_message_hidden (
                        message_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        hidden_at_millis INTEGER NOT NULL,
                        PRIMARY KEY (message_id, user_id)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS chat_members_user_idx ON chat_members(user_id)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS chat_messages_chat_idx ON chat_messages(chat_id, created_at_millis)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS chat_attachments_message_idx ON chat_message_attachments(message_id)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS chat_attachments_chat_idx ON chat_message_attachments(chat_id)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS chat_message_hidden_user_idx ON chat_message_hidden(user_id)")
            }
        }
    }

    fun listChats(userId: String, query: String): List<ChatResponse> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT c.id, c.type, c.owner_id, c.title, c.photo_storage_path, c.created_at_millis, c.updated_at_millis
                FROM chats c
                JOIN chat_members m ON m.chat_id = c.id
                WHERE m.user_id = ?
                ORDER BY c.updated_at_millis DESC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result ->
                    val chats = mutableListOf<ChatResponse>()
                    while (result.next()) {
                        val chat = result.toStoredChat()
                        val response = chat.toResponse(userId, connection)
                        if (query.isBlank() || response.matches(query)) chats += response
                    }
                    return chats
                }
            }
        }
    }

    fun getChat(chatId: String, userId: String): ChatResponse {
        requireMember(chatId, userId)
        connection().use { connection ->
            val chat = findById(chatId, connection)
                ?: throw ApiException(HttpStatusCode.NotFound, "Чат не найден")
            return chat.toResponse(userId, connection)
        }
    }

    fun createOrGetDirectChat(userId: String, peerUserId: String): ChatResponse {
        if (userId == peerUserId) {
            throw ApiException(HttpStatusCode.BadRequest, "Нельзя создать чат с самим собой")
        }
        val peer = userRepository.findById(peerUserId)
            ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
        peer.ensureActiveForApi()

        findDirectChat(userId, peerUserId)?.let { return it }

        val now = Instant.now().toEpochMilli()
        val chat = StoredChat(
            id = UUID.randomUUID().toString(),
            type = CHAT_DIRECT,
            ownerId = userId,
            title = "",
            photoStoragePath = null,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chats (id, type, owner_id, title, photo_storage_path, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chat.id)
                statement.setString(2, chat.type)
                statement.setString(3, chat.ownerId)
                statement.setString(4, chat.title)
                statement.setString(5, chat.photoStoragePath)
                statement.setLong(6, chat.createdAtMillis)
                statement.setLong(7, chat.updatedAtMillis)
                statement.executeUpdate()
            }
            listOf(userId, peerUserId).forEach { memberId ->
                connection.prepareStatement(
                    """
                    INSERT INTO chat_members (chat_id, user_id, created_at_millis)
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, chat.id)
                    statement.setString(2, memberId)
                    statement.setLong(3, now)
                    statement.executeUpdate()
                }
            }
            return chat.toResponse(userId, connection)
        }
    }

    fun createGroupChat(ownerId: String, request: CreateGroupChatRequest): ChatResponse {
        val memberIds = (listOf(ownerId) + request.memberUserIds).distinct()
        if (memberIds.size < 2) {
            throw ApiException(HttpStatusCode.BadRequest, "Добавьте хотя бы одного участника")
        }
        memberIds.forEach { memberId ->
            val member = userRepository.findById(memberId)
                ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
            member.ensureActiveForApi()
        }

        val now = Instant.now().toEpochMilli()
        val chatId = UUID.randomUUID().toString()
        val photoStoragePath = writeChatPhotoOrNull(chatId, request.photoFileName, request.photoBase64)
        val chat = StoredChat(
            id = chatId,
            type = CHAT_GROUP,
            ownerId = ownerId,
            title = request.title,
            photoStoragePath = photoStoragePath,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chats (id, type, owner_id, title, photo_storage_path, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chat.id)
                statement.setString(2, chat.type)
                statement.setString(3, chat.ownerId)
                statement.setString(4, chat.title)
                statement.setString(5, chat.photoStoragePath)
                statement.setLong(6, chat.createdAtMillis)
                statement.setLong(7, chat.updatedAtMillis)
                statement.executeUpdate()
            }
            memberIds.forEach { memberId ->
                insertMember(connection, chat.id, memberId, now)
            }
            return chat.toResponse(ownerId, connection)
        }
    }

    fun updateGroupChat(chatId: String, ownerId: String, request: UpdateGroupChatRequest): ChatResponse {
        val chat = requireGroupOwner(chatId, ownerId)
        val photoStoragePath = if (request.photoBase64.isBlank()) {
            chat.photoStoragePath
        } else {
            chat.photoStoragePath?.let { Files.deleteIfExists(resolvePhotoFile(it)) }
            writeChatPhotoOrNull(chatId, request.photoFileName, request.photoBase64)
        }
        val now = Instant.now().toEpochMilli()

        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE chats
                SET title = ?, photo_storage_path = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, request.title)
                statement.setString(2, photoStoragePath)
                statement.setLong(3, now)
                statement.setString(4, chatId)
                statement.executeUpdate()
            }
            return chat.copy(title = request.title, photoStoragePath = photoStoragePath, updatedAtMillis = now)
                .toResponse(ownerId, connection)
        }
    }

    fun addGroupMembers(chatId: String, ownerId: String, userIds: List<String>): ChatResponse {
        requireGroupOwner(chatId, ownerId)
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            userIds.distinct().forEach { userId ->
                val user = userRepository.findById(userId)
                    ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
                user.ensureActiveForApi()
                insertMember(connection, chatId, userId, now)
            }
            touchChat(connection, chatId, now)
            return getChat(chatId, ownerId)
        }
    }

    fun removeGroupMember(chatId: String, ownerId: String, targetUserId: String): ChatResponse {
        val chat = requireGroupOwner(chatId, ownerId)
        val normalizedTarget = targetUserId.trim()
        if (normalizedTarget.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Выберите пользователя")
        }
        if (normalizedTarget == chat.ownerId) {
            throw ApiException(HttpStatusCode.BadRequest, "Создателя нельзя удалить из чата")
        }
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?").use { statement ->
                statement.setString(1, chatId)
                statement.setString(2, normalizedTarget)
                if (statement.executeUpdate() == 0) {
                    throw ApiException(HttpStatusCode.NotFound, "Участник не найден")
                }
            }
            touchChat(connection, chatId, now)
            return getChat(chatId, ownerId)
        }
    }

    fun listMessages(chatId: String, userId: String): List<ChatMessageResponse> {
        requireMember(chatId, userId)
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, chat_id, sender_id, text, status, created_at_millis, updated_at_millis, deleted_at_millis, is_pinned
                FROM chat_messages
                WHERE chat_id = ?
                  AND id NOT IN (
                      SELECT message_id FROM chat_message_hidden WHERE user_id = ?
                  )
                ORDER BY created_at_millis ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chatId)
                statement.setString(2, userId)
                statement.executeQuery().use { result ->
                    val messages = mutableListOf<ChatMessageResponse>()
                    while (result.next()) {
                        val message = result.toStoredChatMessage()
                        messages += message.toResponse(attachmentsForMessage(connection, message.id))
                    }
                    return messages
                }
            }
        }
    }

    fun sendMessage(chatId: String, userId: String, request: SendMessageRequest): ChatMessageResponse {
        requireMember(chatId, userId)
        val now = Instant.now().toEpochMilli()
        val message = StoredChatMessage(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            senderId = userId,
            text = request.text,
            status = MESSAGE_SENT,
            createdAtMillis = now,
            updatedAtMillis = now,
            deletedAtMillis = 0L,
            isPinned = false
        )

        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chat_messages (id, chat_id, sender_id, text, status, created_at_millis, updated_at_millis, deleted_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, message.id)
                statement.setString(2, message.chatId)
                statement.setString(3, message.senderId)
                statement.setString(4, message.text)
                statement.setString(5, message.status)
                statement.setLong(6, message.createdAtMillis)
                statement.setLong(7, message.updatedAtMillis)
                statement.setLong(8, message.deletedAtMillis)
                statement.executeUpdate()
            }
            request.attachments.forEach { attachment ->
                insertAttachment(connection, message, attachment, now)
            }
            touchChat(connection, chatId, now)
            return message.toResponse(attachmentsForMessage(connection, message.id))
        }
    }

    fun editMessage(messageId: String, userId: String, text: String): ChatMessageResponse {
        val message = findMessage(messageId) ?: throw ApiException(HttpStatusCode.NotFound, "Сообщение не найдено")
        if (message.senderId != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "Можно редактировать только свои сообщения")
        }
        if (message.deletedAtMillis > 0L) {
            throw ApiException(HttpStatusCode.BadRequest, "Сообщение удалено")
        }
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE chat_messages
                SET text = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, text)
                statement.setLong(2, now)
                statement.setString(3, messageId)
                statement.executeUpdate()
            }
            touchChat(connection, message.chatId, now)
        }
        return message.copy(text = text, updatedAtMillis = now).toResponse(attachmentsForMessage(message.id))
    }

    fun deleteMessage(messageId: String, userId: String): ChatMessageResponse {
        val message = findMessage(messageId) ?: throw ApiException(HttpStatusCode.NotFound, "Сообщение не найдено")
        if (message.senderId != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "Можно удалять только свои сообщения")
        }
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE chat_messages
                SET text = '', status = ?, updated_at_millis = ?, deleted_at_millis = ?, is_pinned = 0
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, MESSAGE_DELETED)
                statement.setLong(2, now)
                statement.setLong(3, now)
                statement.setString(4, messageId)
                statement.executeUpdate()
            }
            touchChat(connection, message.chatId, now)
        }
        return message.copy(text = "", status = MESSAGE_DELETED, updatedAtMillis = now, deletedAtMillis = now, isPinned = false)
            .toResponse(attachmentsForMessage(message.id))
    }

    fun deleteMessageForUser(messageId: String, userId: String): String {
        val message = findMessage(messageId) ?: throw ApiException(HttpStatusCode.NotFound, "Сообщение не найдено")
        requireMember(message.chatId, userId)
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO chat_message_hidden (message_id, user_id, hidden_at_millis)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, messageId)
                statement.setString(2, userId)
                statement.setLong(3, now)
                statement.executeUpdate()
            }
        }
        return message.chatId
    }

    fun pinMessage(messageId: String, userId: String, pinned: Boolean): ChatMessageResponse {
        val message = findMessage(messageId) ?: throw ApiException(HttpStatusCode.NotFound, "Сообщение не найдено")
        requireMember(message.chatId, userId)
        if (message.deletedAtMillis > 0L) {
            throw ApiException(HttpStatusCode.BadRequest, "Сообщение удалено")
        }
        val now = Instant.now().toEpochMilli()
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE chat_messages
                SET is_pinned = ?, updated_at_millis = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, if (pinned) 1 else 0)
                statement.setLong(2, now)
                statement.setString(3, messageId)
                statement.executeUpdate()
            }
            touchChat(connection, message.chatId, now)
        }
        return message.copy(isPinned = pinned, updatedAtMillis = now).toResponse(attachmentsForMessage(message.id))
    }

    fun deleteChat(chatId: String, userId: String): List<String> {
        val chat = findById(chatId) ?: throw ApiException(HttpStatusCode.NotFound, "Чат не найден")
        requireMember(chatId, userId)
        if (chat.type == CHAT_GROUP && chat.ownerId != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "Удалить групповой чат может только создатель")
        }
        val previousMembers = memberIds(chatId)
        deleteChatCompletely(chat)
        return previousMembers
    }

    fun deleteChatsForUser(userId: String): List<String> {
        val chatIds = chatIdsForUser(userId)
        val affectedMembers = linkedSetOf<String>()
        chatIds.forEach { chatId ->
            val chat = findById(chatId) ?: return@forEach
            affectedMembers += memberIds(chatId)
            deleteChatCompletely(chat)
        }
        return affectedMembers.toList()
    }

    private fun deleteChatCompletely(chat: StoredChat) {
        val attachmentPaths = attachmentStoragePaths(chat.id)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM chat_message_attachments WHERE chat_id = ?").use { statement ->
                    statement.setString(1, chat.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chat_message_hidden WHERE message_id IN (SELECT id FROM chat_messages WHERE chat_id = ?)").use { statement ->
                    statement.setString(1, chat.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chat_messages WHERE chat_id = ?").use { statement ->
                    statement.setString(1, chat.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chat_members WHERE chat_id = ?").use { statement ->
                    statement.setString(1, chat.id)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM chats WHERE id = ?").use { statement ->
                    statement.setString(1, chat.id)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
        chat.photoStoragePath?.let { Files.deleteIfExists(resolvePhotoFile(it)) }
        attachmentPaths.forEach { Files.deleteIfExists(resolveAttachmentFile(it)) }
    }

    fun memberIds(chatId: String): List<String> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id
                FROM chat_members
                WHERE chat_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chatId)
                statement.executeQuery().use { result ->
                    val ids = mutableListOf<String>()
                    while (result.next()) ids += result.getString("user_id")
                    return ids
                }
            }
        }
    }

    private fun chatIdsForUser(userId: String): List<String> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT chat_id
                FROM chat_members
                WHERE user_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result ->
                    val ids = mutableListOf<String>()
                    while (result.next()) ids += result.getString("chat_id")
                    return ids
                }
            }
        }
    }

    fun findById(chatId: String): StoredChat? =
        connection().use { connection -> findById(chatId, connection) }

    fun findAttachment(attachmentId: String): StoredChatAttachment? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, message_id, chat_id, file_name, mime_type, storage_path, file_size, created_at_millis
                FROM chat_message_attachments
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, attachmentId)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredChatAttachment() else null
                }
            }
        }
    }

    fun requireMemberAccess(chatId: String, userId: String) {
        requireMember(chatId, userId)
    }

    fun resolvePhotoFile(storagePath: String): Path =
        photoFilesDir.resolve(storagePath).normalize()

    fun resolveAttachmentFile(storagePath: String): Path =
        attachmentFilesDir.resolve(storagePath).normalize()

    private fun findDirectChat(userId: String, peerUserId: String): ChatResponse? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT c.id, c.type, c.owner_id, c.title, c.photo_storage_path, c.created_at_millis, c.updated_at_millis
                FROM chats c
                JOIN chat_members m1 ON m1.chat_id = c.id AND m1.user_id = ?
                JOIN chat_members m2 ON m2.chat_id = c.id AND m2.user_id = ?
                WHERE c.type = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, peerUserId)
                statement.setString(3, CHAT_DIRECT)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredChat().toResponse(userId, connection) else null
                }
            }
        }
    }

    private fun findById(chatId: String, connection: Connection): StoredChat? {
        connection.prepareStatement(
            """
            SELECT id, type, owner_id, title, photo_storage_path, created_at_millis, updated_at_millis
            FROM chats
            WHERE id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, chatId)
            statement.executeQuery().use { result ->
                return if (result.next()) result.toStoredChat() else null
            }
        }
    }

    private fun insertMember(connection: Connection, chatId: String, userId: String, now: Long) {
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO chat_members (chat_id, user_id, created_at_millis)
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, chatId)
            statement.setString(2, userId)
            statement.setLong(3, now)
            statement.executeUpdate()
        }
    }

    private fun requireGroupOwner(chatId: String, userId: String): StoredChat {
        val chat = findById(chatId) ?: throw ApiException(HttpStatusCode.NotFound, "Чат не найден")
        if (chat.type != CHAT_GROUP) {
            throw ApiException(HttpStatusCode.BadRequest, "Это не групповой чат")
        }
        if (chat.ownerId != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "Управлять чатом может только создатель")
        }
        return chat
    }

    private fun requireMember(chatId: String, userId: String) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM chat_members
                WHERE chat_id = ? AND user_id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chatId)
                statement.setString(2, userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw ApiException(HttpStatusCode.NotFound, "Чат не найден")
                    }
                }
            }
        }
    }

    private fun findMessage(messageId: String): StoredChatMessage? {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, chat_id, sender_id, text, status, created_at_millis, updated_at_millis, deleted_at_millis, is_pinned
                FROM chat_messages
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, messageId)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toStoredChatMessage() else null
                }
            }
        }
    }

    private fun StoredChat.toResponse(currentUserId: String, connection: Connection): ChatResponse {
        val members = members(connection)
        val otherUser = members.firstOrNull { it.uid != currentUserId }
        val lastMessage = lastMessage(connection, currentUserId)
        return ChatResponse(
            id = id,
            type = type,
            title = if (type == CHAT_GROUP) title else "",
            photoUrl = if (type == CHAT_GROUP && photoStoragePath != null) "/chats/$id/photo" else null,
            ownerId = ownerId,
            otherUser = otherUser?.toPublicResponse(includePrivate = false),
            members = members.map { it.toPublicResponse(includePrivate = false) },
            lastMessage = lastMessage?.toResponse(attachmentsForMessage(connection, lastMessage.id)),
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun StoredChat.members(connection: Connection): List<StoredUser> {
        connection.prepareStatement(
            """
            SELECT user_id
            FROM chat_members
            WHERE chat_id = ?
            ORDER BY created_at_millis ASC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                val users = mutableListOf<StoredUser>()
                while (result.next()) {
                    userRepository.findById(result.getString("user_id"))?.let { users += it }
                }
                return users
            }
        }
    }

    private fun StoredChat.lastMessage(connection: Connection, currentUserId: String): StoredChatMessage? {
        connection.prepareStatement(
            """
            SELECT id, chat_id, sender_id, text, status, created_at_millis, updated_at_millis, deleted_at_millis, is_pinned
            FROM chat_messages
            WHERE chat_id = ?
              AND id NOT IN (
                  SELECT message_id FROM chat_message_hidden WHERE user_id = ?
              )
            ORDER BY created_at_millis DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, currentUserId)
            statement.executeQuery().use { result ->
                return if (result.next()) result.toStoredChatMessage() else null
            }
        }
    }

    private fun ChatResponse.matches(query: String): Boolean {
        val q = query.lowercase()
        val user = otherUser
        return title.lowercase().contains(q) ||
            user?.firstName.orEmpty().lowercase().contains(q) ||
            user?.lastName.orEmpty().lowercase().contains(q) ||
            user?.nickname.orEmpty().lowercase().contains(q) ||
            members.any {
                it.firstName.lowercase().contains(q) ||
                    it.lastName.lowercase().contains(q) ||
                    it.nickname.lowercase().contains(q)
            } ||
            lastMessage?.text.orEmpty().lowercase().contains(q)
    }

    private fun attachmentsForMessage(messageId: String): List<ChatAttachmentResponse> {
        connection().use { connection -> return attachmentsForMessage(connection, messageId) }
    }

    private fun attachmentsForMessage(connection: Connection, messageId: String): List<ChatAttachmentResponse> {
        connection.prepareStatement(
            """
            SELECT id, message_id, chat_id, file_name, mime_type, storage_path, file_size, created_at_millis
            FROM chat_message_attachments
            WHERE message_id = ?
            ORDER BY created_at_millis ASC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, messageId)
            statement.executeQuery().use { result ->
                val attachments = mutableListOf<ChatAttachmentResponse>()
                while (result.next()) attachments += result.toStoredChatAttachment().toResponse()
                return attachments
            }
        }
    }

    private fun attachmentStoragePaths(chatId: String): List<String> {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT storage_path
                FROM chat_message_attachments
                WHERE chat_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, chatId)
                statement.executeQuery().use { result ->
                    val paths = mutableListOf<String>()
                    while (result.next()) paths += result.getString("storage_path")
                    return paths
                }
            }
        }
    }

    private fun insertAttachment(
        connection: Connection,
        message: StoredChatMessage,
        input: ChatAttachmentInput,
        now: Long
    ) {
        val bytes = runCatching { Base64.getDecoder().decode(input.fileBase64) }
            .getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Некорректный файл")
        val id = UUID.randomUUID().toString()
        val extension = input.fileName.safeFileExtension()
        val storagePath = "${message.chatId}/${message.id}/$id.$extension"
        val target = resolveAttachmentFile(storagePath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)

        connection.prepareStatement(
            """
            INSERT INTO chat_message_attachments (
                id, message_id, chat_id, file_name, mime_type, storage_path, file_size, created_at_millis
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, message.id)
            statement.setString(3, message.chatId)
            statement.setString(4, input.fileName.take(160).ifBlank { "file.$extension" })
            statement.setString(5, input.mimeType.ifBlank { mimeTypeForName(input.fileName) })
            statement.setString(6, storagePath)
            statement.setLong(7, bytes.size.toLong())
            statement.setLong(8, now)
            statement.executeUpdate()
        }
    }

    private fun writeChatPhotoOrNull(chatId: String, fileName: String, base64: String): String? {
        if (base64.isBlank()) return null
        val bytes = runCatching {
            Base64.getDecoder().decode(base64)
        }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Некорректное фото чата")
        val extension = fileName.substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            ?: "jpg"
        val storagePath = "$chatId.$extension"
        Files.write(resolvePhotoFile(storagePath), bytes)
        return storagePath
    }

    private fun ensureColumn(connection: Connection, table: String, column: String, definition: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    if (result.getString("name") == column) return
                }
            }
        }

        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun touchChat(connection: Connection, chatId: String, updatedAt: Long) {
        connection.prepareStatement("UPDATE chats SET updated_at_millis = ? WHERE id = ?").use { statement ->
            statement.setLong(1, updatedAt)
            statement.setString(2, chatId)
            statement.executeUpdate()
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    companion object {
        fun fromEnvironment(userRepository: UserRepository): ChatRepository {
            val dbPath = Path.of(System.getenv("BKS_DB_PATH") ?: "build/bks-app.db")
            val filesPath = Path.of(System.getenv("BKS_CHAT_FILES_DIR") ?: "build/chat-files")
            return ChatRepository(dbPath, userRepository, filesPath)
        }
    }
}

class ChatRealtimeHub {
    private val sessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun join(userId: String, session: DefaultWebSocketServerSession) {
        val userSessions = sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        userSessions += session
        session.sendEvent(ChatSocketEvent(type = SOCKET_CONNECTED))
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            userSessions -= session
            if (userSessions.isEmpty()) sessions.remove(userId, userSessions)
        }
    }

    suspend fun notifyUsers(userIds: List<String>, event: ChatSocketEvent) {
        userIds.distinct().forEach { userId ->
            sessions[userId]?.toList().orEmpty().forEach { session ->
                runCatching { session.sendEvent(event) }.onFailure {
                    sessions[userId]?.remove(session)
                }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.sendEvent(event: ChatSocketEvent) {
        send(Frame.Text(json.encodeToString(ChatSocketEvent.serializer(), event)))
    }
}

class TokenService(private val secret: ByteArray) {
    fun createToken(uid: String): String {
        val expiresAt = Instant.now().plusSeconds(TOKEN_TTL_SECONDS).epochSecond
        val payload = "$uid:$expiresAt"
        val signature = hmac(payload)
        return "${base64Url(payload.toByteArray(Charsets.UTF_8))}.${base64Url(signature)}"
    }

    fun verifyToken(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 2) return null

        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val expectedSignature = hmac(payload)
        val actualSignature = runCatching {
            Base64.getUrlDecoder().decode(parts[1])
        }.getOrNull() ?: return null

        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) return null

        val payloadParts = payload.split(":")
        if (payloadParts.size != 2) return null

        val expiresAt = payloadParts[1].toLongOrNull() ?: return null
        if (Instant.now().epochSecond > expiresAt) return null

        return payloadParts[0]
    }

    private fun hmac(payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val TOKEN_TTL_SECONDS = 60L * 60L * 24L * 30L

        fun fromEnvironment(): TokenService {
            val secret = System.getenv("BKS_JWT_SECRET")
                ?: "replace-this-secret-before-production"
            return TokenService(secret.toByteArray(Charsets.UTF_8))
        }
    }
}

object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(password, salt)
        return listOf(
            "pbkdf2_sha256",
            ITERATIONS.toString(),
            base64Url(salt),
            base64Url(hash)
        ).joinToString("$")
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false

        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getUrlDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = pbkdf2(password, salt, iterations)

        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }
}

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun ResultSet.toStoredUser(): StoredUser =
    StoredUser(
        uid = getString("uid"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        firstName = getString("first_name"),
        lastName = getString("last_name"),
        nickname = getString("nickname"),
        avatarStoragePath = getString("avatar_storage_path"),
        bio = getString("bio"),
        phone = getString("phone"),
        role = getString("role"),
        status = getString("status"),
        accountStatus = getString("account_status"),
        blockedReason = getString("blocked_reason"),
        privacyProfileVisible = getInt("privacy_profile_visible") == 1,
        notificationsEnabled = getInt("notifications_enabled") == 1,
        lastSeenAt = getLong("last_seen_at"),
        updatedAt = getLong("updated_at"),
        createdAt = getLong("created_at")
    )

private fun ResultSet.toStoredProject(): StoredProject =
    StoredProject(
        id = getString("id"),
        title = getString("title"),
        objectId = getString("object_id"),
        fileName = getString("file_name"),
        storagePath = getString("storage_path"),
        createdBy = getString("created_by"),
        createdAtMillis = getLong("created_at_millis")
    )

private fun ResultSet.toStoredObject(): StoredObject =
    StoredObject(
        id = getString("id"),
        name = getString("name"),
        photoStoragePath = getString("photo_storage_path"),
        createdBy = getString("created_by"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )

private fun ResultSet.toStoredScheduleTask(): StoredScheduleTask =
    StoredScheduleTask(
        id = getString("id"),
        objectId = getString("object_id"),
        place = getString("place"),
        workType = getString("work_type"),
        color = getString("color"),
        createdBy = getString("created_by"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )

private fun ResultSet.toStoredScheduleProgressOrNull(): StoredScheduleProgress? {
    val progressUserId = getString("progress_user_id") ?: return null
    return StoredScheduleProgress(
        taskId = getString("progress_task_id"),
        userId = progressUserId,
        foremanName = getString("progress_foreman_name").orEmpty(),
        workDates = getString("progress_work_dates").orEmpty().split(",").filter { it.isNotBlank() },
        isDone = getInt("progress_is_done") == 1,
        updatedAtMillis = getLong("progress_updated_at_millis")
    )
}

private fun ResultSet.toStoredShiftReport(): StoredShiftReport =
    StoredShiftReport(
        id = getString("id"),
        objectId = getString("object_id"),
        senderUid = getString("sender_uid"),
        senderName = getString("sender_name"),
        senderEmail = getString("sender_email"),
        senderStatus = getString("sender_status"),
        createdAtMillis = getLong("created_at_millis")
    )

private fun ResultSet.toStoredShiftReportAnswer(): StoredShiftReportAnswer =
    StoredShiftReportAnswer(
        id = getString("id"),
        reportId = getString("report_id"),
        questionId = getString("question_id"),
        questionPrompt = getString("question_prompt"),
        questionType = getString("question_type"),
        value = getString("answer_value"),
        position = getInt("position")
    )

private fun ResultSet.toStoredSpecification(): StoredSpecification =
    StoredSpecification(
        id = getString("id"),
        objectId = getString("object_id"),
        name = getString("name"),
        unit = getString("unit"),
        initialQuantity = getDouble("initial_quantity"),
        remainingQuantity = getDouble("remaining_quantity"),
        createdBy = getString("created_by"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )

private fun ResultSet.toStoredMaterialRequest(): StoredMaterialRequest =
    StoredMaterialRequest(
        id = getString("id"),
        objectId = getString("object_id"),
        recipientEmail = getString("recipient_email"),
        senderUid = getString("sender_uid"),
        senderName = getString("sender_name"),
        senderEmail = getString("sender_email"),
        emailStatus = getString("email_status"),
        createdAtMillis = getLong("created_at_millis")
    )

private fun ResultSet.toStoredMaterialRequestItem(): StoredMaterialRequestItem =
    StoredMaterialRequestItem(
        id = getString("id"),
        requestId = getString("request_id"),
        specificationId = getString("specification_id"),
        specificationName = getString("specification_name"),
        unit = getString("unit"),
        quantity = getDouble("quantity")
    )

private fun ResultSet.toStoredChat(): StoredChat =
    StoredChat(
        id = getString("id"),
        type = getString("type"),
        ownerId = getString("owner_id").orEmpty(),
        title = getString("title").orEmpty(),
        photoStoragePath = getString("photo_storage_path"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )

private fun ResultSet.toStoredChatMessage(): StoredChatMessage =
    StoredChatMessage(
        id = getString("id"),
        chatId = getString("chat_id"),
        senderId = getString("sender_id"),
        text = getString("text"),
        status = getString("status"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis"),
        deletedAtMillis = getLong("deleted_at_millis"),
        isPinned = getInt("is_pinned") == 1
    )

private fun ResultSet.toStoredChatAttachment(): StoredChatAttachment =
    StoredChatAttachment(
        id = getString("id"),
        messageId = getString("message_id"),
        chatId = getString("chat_id"),
        fileName = getString("file_name"),
        mimeType = getString("mime_type"),
        storagePath = getString("storage_path"),
        fileSize = getLong("file_size"),
        createdAtMillis = getLong("created_at_millis")
    )

private fun StoredUser.toResponse(): UserResponse =
    UserResponse(
        uid = uid,
        email = email,
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarStoragePath?.let { "/users/$uid/avatar" },
        bio = bio,
        phone = phone,
        role = role,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason,
        privacyProfileVisible = privacyProfileVisible,
        notificationsEnabled = notificationsEnabled,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
        createdAt = createdAt
    )

private fun StoredUser.toPublicResponse(includePrivate: Boolean): PublicUserResponse {
    if (accountStatus == ACCOUNT_DELETED) {
        return PublicUserResponse(
            uid = uid,
            email = null,
            firstName = DELETED_USER_DISPLAY_NAME,
            lastName = "",
            nickname = "deleted",
            avatarUrl = null,
            bio = "",
            phone = null,
            role = ROLE_USER,
            status = ACCOUNT_DELETED,
            accountStatus = ACCOUNT_DELETED,
            blockedReason = null,
            isOnline = false,
            lastSeenAt = lastSeenAt,
            createdAt = createdAt
        )
    }
    return PublicUserResponse(
        uid = uid,
        email = email.takeIf { includePrivate },
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarStoragePath?.let { "/users/$uid/avatar" },
        bio = bio.takeIf { includePrivate || privacyProfileVisible }.orEmpty(),
        phone = phone.takeIf { includePrivate },
        role = role.takeIf { includePrivate } ?: ROLE_USER,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason.takeIf { includePrivate && accountStatus == ACCOUNT_BLOCKED },
        isOnline = lastSeenAt > 0 && Instant.now().toEpochMilli() - lastSeenAt < 120_000L,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt
    )
}

private fun UserResponse.toPublicResponse(includePrivate: Boolean): PublicUserResponse {
    if (accountStatus == ACCOUNT_DELETED) {
        return PublicUserResponse(
            uid = uid,
            email = null,
            firstName = DELETED_USER_DISPLAY_NAME,
            lastName = "",
            nickname = "deleted",
            avatarUrl = null,
            bio = "",
            phone = null,
            role = ROLE_USER,
            status = ACCOUNT_DELETED,
            accountStatus = ACCOUNT_DELETED,
            blockedReason = null,
            isOnline = false,
            lastSeenAt = lastSeenAt,
            createdAt = createdAt
        )
    }
    return PublicUserResponse(
        uid = uid,
        email = email.takeIf { includePrivate },
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarUrl,
        bio = bio.takeIf { includePrivate || privacyProfileVisible }.orEmpty(),
        phone = phone.takeIf { includePrivate },
        role = role.takeIf { includePrivate } ?: ROLE_USER,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason.takeIf { includePrivate && accountStatus == ACCOUNT_BLOCKED },
        isOnline = false,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt
    )
}

private fun StoredProject.toResponse(): ProjectResponse =
    ProjectResponse(
        id = id,
        title = title,
        objectId = objectId,
        fileName = fileName,
        fileUrl = "/projects/$id/file",
        storagePath = storagePath,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis
    )

private fun StoredObject.toResponse(): ObjectResponse =
    ObjectResponse(
        id = id,
        name = name,
        photoUrl = photoStoragePath?.let { "/objects/$id/photo" },
        photoStoragePath = photoStoragePath,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun StoredScheduleTask.toResponse(
    progress: StoredScheduleProgress?,
    progresses: List<StoredScheduleProgress> = emptyList()
): ScheduleTaskResponse =
    ScheduleTaskResponse(
        id = id,
        objectId = objectId,
        place = place,
        workType = workType,
        color = color,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        progress = progress?.toResponse(),
        progresses = progresses.map { it.toResponse() }
    )

private fun StoredScheduleProgress.toResponse(): ScheduleProgressResponse =
    ScheduleProgressResponse(
        taskId = taskId,
        userId = userId,
        foremanName = foremanName,
        workDates = workDates,
        isDone = isDone,
        updatedAtMillis = updatedAtMillis
    )

private fun StoredShiftQuestion.toResponse(): ShiftQuestionResponse =
    ShiftQuestionResponse(
        id = id,
        objectId = objectId,
        prompt = prompt,
        type = type,
        options = options,
        position = position,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun StoredShiftReport.toResponse(answers: List<ShiftReportAnswerResponse>): ShiftReportResponse =
    ShiftReportResponse(
        id = id,
        objectId = objectId,
        senderUid = senderUid,
        senderName = senderName,
        senderEmail = senderEmail,
        senderStatus = senderStatus,
        createdAtMillis = createdAtMillis,
        answers = answers
    )

private fun StoredShiftReportAnswer.toResponse(): ShiftReportAnswerResponse =
    ShiftReportAnswerResponse(
        id = id,
        reportId = reportId,
        questionId = questionId,
        questionPrompt = questionPrompt,
        questionType = questionType,
        value = value,
        position = position
    )

private fun StoredSpecification.toResponse(): SpecificationResponse =
    SpecificationResponse(
        id = id,
        objectId = objectId,
        name = name,
        unit = unit,
        initialQuantity = initialQuantity,
        remainingQuantity = remainingQuantity,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun StoredMaterialRequest.toResponse(items: List<MaterialRequestItemResponse>): MaterialRequestResponse =
    MaterialRequestResponse(
        id = id,
        objectId = objectId,
        recipientEmail = recipientEmail,
        senderUid = senderUid,
        senderName = senderName,
        senderEmail = senderEmail,
        emailStatus = emailStatus,
        createdAtMillis = createdAtMillis,
        items = items
    )

private fun StoredMaterialRequestItem.toResponse(): MaterialRequestItemResponse =
    MaterialRequestItemResponse(
        id = id,
        requestId = requestId,
        specificationId = specificationId,
        specificationName = specificationName,
        unit = unit,
        quantity = quantity
    )

private fun StoredChatMessage.toResponse(attachments: List<ChatAttachmentResponse> = emptyList()): ChatMessageResponse =
    ChatMessageResponse(
        id = id,
        chatId = chatId,
        senderId = senderId,
        text = text,
        status = status,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
        isPinned = isPinned,
        attachments = attachments
    )

private fun StoredChatAttachment.toResponse(): ChatAttachmentResponse =
    ChatAttachmentResponse(
        id = id,
        messageId = messageId,
        chatId = chatId,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        url = "/chats/attachments/$id",
        createdAtMillis = createdAtMillis
    )

class ApiException(
    val status: HttpStatusCode,
    val publicMessage: String
) : RuntimeException(publicMessage)

data class StoredUser(
    val uid: String,
    val email: String,
    val passwordHash: String,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val avatarStoragePath: String?,
    val bio: String,
    val phone: String,
    val role: String,
    val status: String,
    val accountStatus: String,
    val blockedReason: String,
    val privacyProfileVisible: Boolean,
    val notificationsEnabled: Boolean,
    val lastSeenAt: Long,
    val updatedAt: Long,
    val createdAt: Long
)

data class StoredProject(
    val id: String,
    val title: String,
    val objectId: String,
    val fileName: String,
    val storagePath: String,
    val createdBy: String,
    val createdAtMillis: Long
)

data class StoredObject(
    val id: String,
    val name: String,
    val photoStoragePath: String?,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredScheduleTask(
    val id: String,
    val objectId: String,
    val place: String,
    val workType: String,
    val color: String,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredScheduleProgress(
    val taskId: String,
    val userId: String,
    val foremanName: String,
    val workDates: List<String>,
    val isDone: Boolean,
    val updatedAtMillis: Long
)

data class StoredShiftQuestion(
    val id: String,
    val objectId: String,
    val prompt: String,
    val type: String,
    val options: List<String>,
    val position: Int,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredShiftReport(
    val id: String,
    val objectId: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val senderStatus: String,
    val createdAtMillis: Long
)

data class StoredShiftReportAnswer(
    val id: String,
    val reportId: String,
    val questionId: String,
    val questionPrompt: String,
    val questionType: String,
    val value: String,
    val position: Int
)

data class StoredSpecification(
    val id: String,
    val objectId: String,
    val name: String,
    val unit: String,
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredMaterialRequest(
    val id: String,
    val objectId: String,
    val recipientEmail: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val emailStatus: String,
    val createdAtMillis: Long
)

data class StoredMaterialRequestItem(
    val id: String,
    val requestId: String,
    val specificationId: String,
    val specificationName: String,
    val unit: String,
    val quantity: Double
)

data class StoredChat(
    val id: String,
    val type: String,
    val ownerId: String,
    val title: String,
    val photoStoragePath: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredChatMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long,
    val isPinned: Boolean = false
)

data class StoredChatAttachment(
    val id: String,
    val messageId: String,
    val chatId: String,
    val fileName: String,
    val mimeType: String,
    val storagePath: String,
    val fileSize: Long,
    val createdAtMillis: Long
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String,
    val nickname: String = "",
    val role: String = ROLE_USER,
    val status: String = ""
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class UserResponse(
    val uid: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String = "",
    val role: String = ROLE_USER,
    val status: String = STATUS_ELECTRICIAN,
    val accountStatus: String = ACCOUNT_ACTIVE,
    val blockedReason: String = "",
    val privacyProfileVisible: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
data class PublicUserResponse(
    val uid: String,
    val email: String? = null,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String? = null,
    val role: String = ROLE_USER,
    val status: String = STATUS_ELECTRICIAN,
    val accountStatus: String = ACCOUNT_ACTIVE,
    val blockedReason: String? = null,
    val isOnline: Boolean = false,
    val lastSeenAt: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
data class UpdateProfileRequest(
    val email: String = "",
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val bio: String = "",
    val phone: String = "",
    val avatarFileName: String = "",
    val avatarBase64: String = "",
    val privacyProfileVisible: Boolean = true,
    val notificationsEnabled: Boolean = true
)

@Serializable
data class RecoverAccessRequest(
    val email: String
)

@Serializable
data class RecoverAccessResponse(
    val message: String
)

@Serializable
data class UpdateUserAccessRequest(
    val accountStatus: String,
    val blockedReason: String = ""
)

@Serializable
data class SaveFcmTokenRequest(
    val token: String,
    val platform: String = "ANDROID"
)

@Serializable
data class SaveFcmTokenResponse(
    val saved: Boolean
)

@Serializable
data class UserAccessHistoryResponse(
    val id: String,
    val userId: String,
    val changedBy: String,
    val oldStatus: String,
    val newStatus: String,
    val reason: String,
    val createdAtMillis: Long
)

@Serializable
data class CreateProjectRequest(
    val objectId: String = "",
    val title: String,
    val fileName: String,
    val fileBase64: String
)

@Serializable
data class ProjectResponse(
    val id: String,
    val title: String,
    val objectId: String,
    val fileName: String,
    val fileUrl: String,
    val storagePath: String,
    val createdBy: String,
    val createdAtMillis: Long
)

@Serializable
data class SaveObjectRequest(
    val name: String,
    val photoFileName: String = "",
    val photoBase64: String = ""
)

@Serializable
data class ObjectResponse(
    val id: String,
    val name: String,
    val photoUrl: String? = null,
    val photoStoragePath: String? = null,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
data class SaveScheduleTaskRequest(
    val objectId: String = "",
    val place: String,
    val workType: String,
    val color: String = "#4F8EF7"
)

@Serializable
data class SaveScheduleProgressRequest(
    val foremanName: String = "",
    val workDates: List<String> = emptyList(),
    val isDone: Boolean = false
)

@Serializable
data class ScheduleTaskResponse(
    val id: String,
    val objectId: String,
    val place: String,
    val workType: String,
    val color: String,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progress: ScheduleProgressResponse? = null,
    val progresses: List<ScheduleProgressResponse> = emptyList()
)

@Serializable
data class ScheduleProgressResponse(
    val taskId: String,
    val userId: String,
    val foremanName: String,
    val workDates: List<String>,
    val isDone: Boolean,
    val updatedAtMillis: Long
)

@Serializable
data class SaveShiftFormRequest(
    val objectId: String = "",
    val questions: List<SaveShiftQuestionRequest>
)

@Serializable
data class SaveShiftQuestionRequest(
    val prompt: String,
    val type: String,
    val options: List<String> = emptyList()
)

@Serializable
data class SubmitShiftReportRequest(
    val objectId: String = "",
    val answers: List<SubmitShiftAnswerRequest>
)

@Serializable
data class SubmitShiftAnswerRequest(
    val questionId: String,
    val value: String
)

@Serializable
data class ShiftFormResponse(
    val objectId: String,
    val questions: List<ShiftQuestionResponse>
)

@Serializable
data class ShiftQuestionResponse(
    val id: String,
    val objectId: String,
    val prompt: String,
    val type: String,
    val options: List<String>,
    val position: Int,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
data class ShiftReportResponse(
    val id: String,
    val objectId: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val senderStatus: String,
    val createdAtMillis: Long,
    val answers: List<ShiftReportAnswerResponse>
)

@Serializable
data class ShiftReportAnswerResponse(
    val id: String,
    val reportId: String,
    val questionId: String,
    val questionPrompt: String,
    val questionType: String,
    val value: String,
    val position: Int
)

@Serializable
data class SaveSpecificationRequest(
    val objectId: String = "",
    val name: String,
    val unit: String = "",
    val initialQuantity: Double,
    val remainingQuantity: Double
)

@Serializable
data class SpecificationResponse(
    val id: String,
    val objectId: String,
    val name: String,
    val unit: String,
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
data class CreateMaterialRequest(
    val objectId: String = "",
    val recipientEmail: String,
    val items: List<MaterialRequestItemInput>
)

@Serializable
data class MaterialRequestItemInput(
    val specificationId: String,
    val quantity: Double
)

@Serializable
data class MaterialRequestResponse(
    val id: String,
    val objectId: String,
    val recipientEmail: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val emailStatus: String,
    val createdAtMillis: Long,
    val items: List<MaterialRequestItemResponse>
)

@Serializable
data class MaterialRequestItemResponse(
    val id: String,
    val requestId: String,
    val specificationId: String,
    val specificationName: String,
    val unit: String,
    val quantity: Double
)

data class MailConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    val ssl: Boolean,
    val startTls: Boolean,
    val helloHost: String
)

data class FirebasePushConfig(
    val projectId: String,
    val clientEmail: String,
    val privateKey: String,
    val tokenUri: String
)

data class CachedAccessToken(
    val value: String,
    val expiresAtEpochSecond: Long
)

@Serializable
data class FirebaseServiceAccount(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token"
)

@Serializable
data class GoogleAccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600
)

@Serializable
data class CreateDirectChatRequest(
    val peerUserId: String
)

@Serializable
data class CreateGroupChatRequest(
    val title: String,
    val memberUserIds: List<String>,
    val photoFileName: String = "",
    val photoBase64: String = ""
)

@Serializable
data class UpdateGroupChatRequest(
    val title: String,
    val photoFileName: String = "",
    val photoBase64: String = ""
)

@Serializable
data class UpdateChatMembersRequest(
    val userIds: List<String>
)

@Serializable
data class SendMessageRequest(
    val text: String = "",
    val clientId: String = "",
    val attachments: List<ChatAttachmentInput> = emptyList()
)

@Serializable
data class UpdateMessageRequest(
    val text: String
)

@Serializable
data class PinMessageRequest(
    val pinned: Boolean
)

@Serializable
data class ChatAttachmentInput(
    val fileName: String,
    val mimeType: String = "",
    val fileBase64: String
)

@Serializable
data class ChatAttachmentResponse(
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
data class ChatResponse(
    val id: String,
    val type: String,
    val title: String = "",
    val photoUrl: String? = null,
    val ownerId: String = "",
    val otherUser: PublicUserResponse? = null,
    val members: List<PublicUserResponse> = emptyList(),
    val lastMessage: ChatMessageResponse? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
data class ChatMessageResponse(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long,
    val isPinned: Boolean = false,
    val attachments: List<ChatAttachmentResponse> = emptyList()
)

@Serializable
data class ChatSocketEvent(
    val type: String,
    val chatId: String? = null,
    val objectId: String? = null,
    val messageId: String? = null,
    val message: ChatMessageResponse? = null,
    val materialRequest: MaterialRequestResponse? = null,
    val user: PublicUserResponse? = null,
    val clientId: String = ""
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserResponse
)

@Serializable
data class RegistrationResponse(
    val message: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String
)

@Serializable
data class ErrorResponse(
    val message: String
)

const val ROLE_USER = "USER"
const val ROLE_ADMIN = "ADMIN"
const val STATUS_ADMINISTRATOR = "ADMINISTRATOR"
const val STATUS_FOREMAN = "FOREMAN"
const val STATUS_ELECTRICIAN = "ELECTRICIAN"
const val ACCOUNT_ACTIVE = "ACTIVE"
const val ACCOUNT_BLOCKED = "BLOCKED"
const val ACCOUNT_INACTIVE = "INACTIVE"
const val ACCOUNT_DELETED = "DELETED"
const val CHAT_DIRECT = "DIRECT"
const val CHAT_GROUP = "GROUP"
const val MESSAGE_SENT = "SENT"
const val MESSAGE_DELETED = "DELETED"
const val SHIFT_QUESTION_TEXT = "TEXT"
const val SHIFT_QUESTION_CHOICE = "CHOICE"
const val EMAIL_PENDING = "PENDING"
const val EMAIL_SENT = "SENT"
const val EMAIL_NOT_CONFIGURED = "NOT_CONFIGURED"
const val EMAIL_FAILED = "FAILED"
const val SOCKET_CONNECTED = "CONNECTED"
const val SOCKET_CHAT_UPDATED = "CHAT_UPDATED"
const val SOCKET_MESSAGE_CREATED = "MESSAGE_CREATED"
const val SOCKET_MESSAGE_UPDATED = "MESSAGE_UPDATED"
const val SOCKET_MESSAGE_DELETED = "MESSAGE_DELETED"
const val SOCKET_MESSAGE_HIDDEN = "MESSAGE_HIDDEN"
const val SOCKET_REGISTRATION_REQUESTED = "REGISTRATION_REQUESTED"
const val SOCKET_SHIFT_REPORT_CREATED = "SHIFT_REPORT_CREATED"
const val SOCKET_MATERIAL_REQUEST_CREATED = "MATERIAL_REQUEST_CREATED"
const val PUSH_TYPE_CHAT_MESSAGE = "CHAT_MESSAGE"
const val PUSH_TYPE_REGISTRATION_REQUEST = "REGISTRATION_REQUEST"
const val FCM_ANDROID_CHANNEL_ID = "bks_chat_messages"
const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
const val ACCESS_REQUEST_CREATED_MESSAGE = "Заявка на регистрацию отправлена администратору"
const val DELETED_USER_DISPLAY_NAME = "Пользователь удален из системы"
const val MAX_UPLOAD_BASE64_LENGTH = 28_000_000
const val DEV_ADMIN_LOGIN = "admin"
const val DEV_ADMIN_PASSWORD = "admin"
const val DEV_ADMIN_EMAIL = "developer@bks.local"
const val DEV_ADMIN_NICKNAME = "developer-admin"

val VALID_ROLES = setOf(ROLE_USER, ROLE_ADMIN)
val VALID_STATUSES = setOf(STATUS_ADMINISTRATOR, STATUS_FOREMAN, STATUS_ELECTRICIAN)
val VALID_ACCOUNT_STATUSES = setOf(ACCOUNT_ACTIVE, ACCOUNT_BLOCKED, ACCOUNT_INACTIVE, ACCOUNT_DELETED)
val VALID_SHIFT_QUESTION_TYPES = setOf(SHIFT_QUESTION_TEXT, SHIFT_QUESTION_CHOICE)
