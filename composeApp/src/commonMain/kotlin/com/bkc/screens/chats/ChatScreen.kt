package com.bkc.screens.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.domain.PlatformUser
import com.bkc.core.domain.chat.Chat
import com.bkc.core.domain.chat.ChatAttachment
import com.bkc.core.domain.chat.ChatMessage
import com.bkc.core.domain.chat.PendingChatAttachment
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.CachedAvatar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.media.AppVideoPlayer
import com.bkc.core.presentation.media.ImageFilePicker
import com.bkc.core.presentation.media.ProjectFilePicker
import com.bkc.core.presentation.media.decodeImageBitmap
import com.bkc.core.presentation.media.loadImageBitmap
import com.bkc.core.presentation.navigation.BottomBarVisibilityStore
import com.bkc.core.presentation.share.MessageShare
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.koin.mp.KoinPlatform.getKoin

class ChatScreen(
    private val chatId: String,
    private val title: String,
    private val peerAvatarUrl: String? = null
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val chatRepository = getKoin().get<ChatRepository>()
        val sessionStore = getKoin().get<UserSessionStore>()
        val clipboardManager = LocalClipboardManager.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val messageFlow = remember(chatId) { chatRepository.observeMessages(chatId) }
        val messages by messageFlow.collectAsState(emptyList())
        val listState = rememberLazyListState()
        var sendingMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var failedMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        val visibleMessages = remember(messages, sendingMessages, failedMessages) {
            (messages + sendingMessages + failedMessages).sortedBy { it.createdAtMillis }
        }
        val dialogAttachments = remember(visibleMessages) {
            visibleMessages.dialogAttachments()
        }
        val pinnedMessage = remember(visibleMessages) {
            visibleMessages.lastOrNull { it.isPinned && !it.isDeleted && it.status != "ERROR" }
        }

        var selfUid by remember { mutableStateOf("") }
        var authToken by remember { mutableStateOf("") }
        var ownAvatarUrl by remember { mutableStateOf<String?>(null) }
        var chat by remember { mutableStateOf<Chat?>(null) }
        var text by remember { mutableStateOf("") }
        var pendingAttachments by remember { mutableStateOf<List<PendingChatAttachment>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
        var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
        var mediaPreview by remember { mutableStateOf<MediaPreviewState?>(null) }
        var editText by remember { mutableStateOf("") }
        var showAttachmentPicker by remember { mutableStateOf(false) }
        var pickImage by remember { mutableStateOf(false) }
        var pickFile by remember { mutableStateOf(false) }
        var showGroupSettings by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            BottomBarVisibilityStore.hide()
            onDispose { BottomBarVisibilityStore.show() }
        }

        fun loadMessages() {
            scope.launch {
                isLoading = true
                error = null
                runCatching { chat = chatRepository.getChat(chatId) }
                    .onFailure { chat = null }
                runCatching { chatRepository.refreshMessages(chatId) }
                    .onFailure { error = it.message ?: "Ошибка загрузки сообщений" }
                isLoading = false
            }
        }

        fun send() {
            val value = text.trim()
            val attachments = pendingAttachments
            if (value.isBlank() && attachments.isEmpty()) return
            val now = Clock.System.now().toEpochMilliseconds()
            val localMessage = createLocalSendingMessage(
                chatId = chatId,
                senderId = selfUid,
                text = value,
                attachments = attachments,
                now = now
            )
            sendingMessages = sendingMessages + localMessage
            text = ""
            pendingAttachments = emptyList()
            scope.launch {
                error = null
                runCatching { chatRepository.sendMessage(chatId, value, attachments) }
                    .onSuccess {
                        sendingMessages = sendingMessages.filterNot { it.id == localMessage.id }
                    }
                    .onFailure {
                        sendingMessages = sendingMessages.filterNot { it.id == localMessage.id }
                        failedMessages = failedMessages + ChatMessage(
                            id = "local-error-$now",
                            chatId = chatId,
                            senderId = selfUid,
                            text = value.ifBlank { "Не удалось отправить вложение" },
                            status = "ERROR",
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            deletedAtMillis = 0L,
                            attachments = localMessage.attachments.map { attachment ->
                                attachment.copy(isUploading = false)
                            }
                        )
                        error = it.message ?: "Ошибка отправки сообщения"
                    }
            }
        }

        LaunchedEffect(chatId) {
            val user = sessionStore.getUserOrNull()
            selfUid = user?.uid.orEmpty()
            authToken = user?.authToken.orEmpty()
            ownAvatarUrl = user?.avatarUrl
            chatRepository.markChatRead(chatId)
            loadMessages()
        }

        if (pickImage) {
            ImageFilePicker { fileName, bytes ->
                pendingAttachments = pendingAttachments + PendingChatAttachment(fileName, mimeTypeForFile(fileName), bytes)
                pickImage = false
            }
        }

        if (pickFile) {
            ProjectFilePicker { fileName, bytes ->
                pendingAttachments = pendingAttachments + PendingChatAttachment(fileName, mimeTypeForFile(fileName), bytes)
                pickFile = false
            }
        }

        LaunchedEffect(visibleMessages.lastOrNull()?.id, visibleMessages.lastOrNull()?.updatedAtMillis, visibleMessages.size) {
            if (visibleMessages.isNotEmpty()) {
                chatRepository.markChatRead(chatId)
                listState.animateScrollToItem(visibleMessages.lastIndex)
            }
        }

        Scaffold(
            topBar = {
                val isGroup = chat?.type == "GROUP"
                ChatTopBar(
                    title = chat?.displayTitle().orEmpty().ifBlank { title.ifBlank { "Чат" } },
                    subtitle = if (isGroup) {
                        "${chat?.members.orEmpty().size} ${chat?.members.orEmpty().size.participantWord()} · групповой чат"
                    } else {
                        null
                    },
                    isGroup = isGroup,
                    onBack = { navigator?.pop() },
                    onSettings = if (chat?.type == "GROUP" && chat?.ownerId == selfUid) {
                        { showGroupSettings = true }
                    } else {
                        null
                    }
                )
            },
            bottomBar = {
                Surface(shadowElevation = 3.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (pendingAttachments.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                pendingAttachments.forEach { attachment ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = attachment.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { pendingAttachments = pendingAttachments - attachment },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Убрать файл", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = { showAttachmentPicker = true }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Добавить файл")
                            }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.weight(1f),
                                minLines = 1,
                                maxLines = 4,
                                placeholder = { Text("Сообщение") }
                            )
                            IconButton(onClick = { send() }, enabled = text.isNotBlank() || pendingAttachments.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                pinnedMessage?.let { pinned ->
                    PinnedMessageBar(
                        message = pinned,
                        onClick = {
                            val index = visibleMessages.indexOfFirst { it.id == pinned.id }
                            if (index >= 0) {
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        },
                        onUnpin = {
                            scope.launch {
                                error = null
                                runCatching { chatRepository.pinMessage(pinned.id, false) }
                                    .onFailure { error = it.message ?: "Ошибка открепления сообщения" }
                            }
                        }
                    )
                }

                when {
                    isLoading && visibleMessages.isEmpty() -> LoadingState()
                    visibleMessages.isEmpty() -> EmptyState("Сообщений пока нет", Modifier.fillMaxSize())
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleMessages, key = { it.id }) { message ->
                            val sender = chat?.members?.firstOrNull { it.uid == message.senderId }
                            MessageRow(
                                message = message,
                                isMine = message.senderId == selfUid,
                                ownAvatarUrl = ownAvatarUrl,
                                senderName = if (chat?.type == "GROUP" && message.senderId != selfUid) {
                                    sender?.displayName()
                                } else {
                                    null
                                },
                                peerAvatarUrl = if (chat?.type == "GROUP") {
                                    sender?.avatarUrl
                                } else {
                                    peerAvatarUrl
                                },
                                mediaAuthToken = authToken,
                                onActions = { actionMessage = message },
                                onOpenAttachment = { attachment ->
                                    val initialIndex = dialogAttachments.indexOfFirst { it.id == attachment.id }
                                    if (initialIndex >= 0) {
                                        mediaPreview = MediaPreviewState(
                                            attachments = dialogAttachments,
                                            initialIndex = initialIndex
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showAttachmentPicker) {
            AlertDialog(
                onDismissRequest = { showAttachmentPicker = false },
                title = { Text("Добавить вложение") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showAttachmentPicker = false
                                pickImage = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Text("Фото")
                        }
                        Button(
                            onClick = {
                                showAttachmentPicker = false
                                pickFile = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                            Text("Видео или файл")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAttachmentPicker = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        if (showGroupSettings && chat != null) {
            GroupSettingsDialog(
                chat = chat!!,
                selfUid = selfUid,
                onDismiss = { showGroupSettings = false },
                onUpdated = {
                    chat = it
                    showGroupSettings = false
                },
                onError = { error = it }
            )
        }

        actionMessage?.let { message ->
            val canCopy = !message.isDeleted && message.text.isNotBlank()
            val canShare = !message.isDeleted && message.shareText().isNotBlank()
            val canEditMessage = message.senderId == selfUid && !message.isDeleted && message.status != "ERROR" && message.text.isNotBlank()
            val canDeleteForAll = message.senderId == selfUid && !message.isDeleted && message.status != "ERROR"
            val canDeleteForMe = !message.isDeleted && message.status != "ERROR"
            val canPin = !message.isDeleted && message.status != "ERROR"
            AlertDialog(
                onDismissRequest = { actionMessage = null },
                title = { Text("Действия с сообщением") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = message.previewText(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (canPin) {
                            Button(
                                onClick = {
                                    actionMessage = null
                                    scope.launch {
                                        error = null
                                        runCatching { chatRepository.pinMessage(message.id, !message.isPinned) }
                                            .onFailure {
                                                error = it.message ?: if (message.isPinned) {
                                                    "Ошибка открепления сообщения"
                                                } else {
                                                    "Ошибка закрепления сообщения"
                                                }
                                            }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (message.isPinned) "Открепить сообщение" else "Закрепить сообщение")
                            }
                        }
                        if (canShare) {
                            Button(
                                onClick = {
                                    val sharedText = message.shareText()
                                    if (!MessageShare.shareText(sharedText)) {
                                        clipboardManager.setText(AnnotatedString(sharedText))
                                    }
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Поделиться")
                            }
                        }
                        if (canCopy) {
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Копировать текст")
                            }
                        }
                        if (canEditMessage) {
                            Button(
                                onClick = {
                                    actionMessage = null
                                    editingMessage = message
                                    editText = message.text
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Редактировать")
                            }
                        }
                        if (canDeleteForMe) {
                            TextButton(
                                onClick = {
                                    actionMessage = null
                                    scope.launch {
                                        error = null
                                        runCatching { chatRepository.deleteMessageForMe(message.id, message.chatId) }
                                            .onFailure { error = it.message ?: "Ошибка удаления сообщения" }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Удалить у себя", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (canDeleteForAll) {
                            TextButton(
                                onClick = {
                                    actionMessage = null
                                    scope.launch {
                                        error = null
                                        runCatching { chatRepository.deleteMessage(message.id) }
                                            .onFailure { error = it.message ?: "Ошибка удаления сообщения" }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Удалить у всех", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actionMessage = null }) {
                        Text("Отмена")
                    }
                }
            )
        }

        editingMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { editingMessage = null },
                title = { Text("Редактировать сообщение") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val value = editText.trim()
                            if (value.isBlank()) return@Button
                            scope.launch {
                                error = null
                                runCatching { chatRepository.editMessage(message.id, value) }
                                    .onSuccess {
                                        editingMessage = null
                                    }
                                    .onFailure { error = it.message ?: "Ошибка редактирования сообщения" }
                            }
                        }
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    TextButton(onClick = { editingMessage = null }) {
                        Text("Отмена")
                    }
                }
            )
        }

        mediaPreview?.let { preview ->
            MediaPreviewDialog(
                attachments = preview.attachments,
                initialIndex = preview.initialIndex,
                mediaAuthToken = authToken,
                onDismiss = { mediaPreview = null },
                onOpenExternal = { attachment ->
                    uriHandler.openUri(attachment.mediaUrl(authToken))
                }
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String? = null,
    isGroup: Boolean = false,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondary)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onSecondary
            )
        }
        if (isGroup) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.18f)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSecondary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Настройки чата",
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

@Composable
private fun PinnedMessageBar(
    message: ChatMessage,
    onClick: () -> Unit,
    onUnpin: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Закрепленное сообщение",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = message.previewText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onUnpin, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Открепить сообщение",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    isMine: Boolean,
    ownAvatarUrl: String?,
    senderName: String?,
    peerAvatarUrl: String?,
    mediaAuthToken: String,
    onActions: () -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit
) {
    val isError = message.status == "ERROR"
    val isSending = message.status == "SENDING"
    val hasActions = !message.isDeleted && !isError && !isSending
    val actionsModifier = if (hasActions) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = onActions
        )
    } else {
        Modifier
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (isMine) Alignment.End else Alignment.Start
        ),
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isMine) {
            CachedAvatar(avatarUrl = peerAvatarUrl, size = 34.dp, iconSize = 24.dp)
        }
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .then(actionsModifier)
                .background(
                    color = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(10.dp)
        ) {
            if (message.isPinned && !message.isDeleted) {
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Закреплено",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            senderName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (message.isDeleted || message.text.isNotBlank()) {
                val messageText = if (message.isDeleted) "Сообщение удалено" else message.text
                val contentColor = if (message.isDeleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                if (message.isDeleted) {
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor
                        )
                    }
                }
            }
            if (!message.isDeleted && message.attachments.isNotEmpty()) {
                AttachmentList(message.attachments, mediaAuthToken, onOpenAttachment)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = message.updatedAtMillis.formatChatTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isMine && isError) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Ошибка отправки",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
        if (isMine) {
            CachedAvatar(avatarUrl = ownAvatarUrl, size = 34.dp, iconSize = 24.dp)
        }
    }
}

@Composable
private fun AttachmentList(
    attachments: List<ChatAttachment>,
    mediaAuthToken: String,
    onOpenAttachment: (ChatAttachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            when {
                attachment.isImage() -> ImageAttachmentPreview(
                    attachment = attachment,
                    mediaAuthToken = mediaAuthToken,
                    onClick = { if (attachment.canOpenAttachment()) onOpenAttachment(attachment) }
                )
                attachment.isVideo() -> VideoAttachmentPreview(
                    attachment = attachment,
                    onClick = { if (attachment.canOpenAttachment()) onOpenAttachment(attachment) }
                )
                else -> FileAttachmentRow(
                    attachment = attachment,
                    onClick = { if (attachment.canOpenAttachment()) onOpenAttachment(attachment) }
                )
            }
        }
    }
}

@Composable
private fun ImageAttachmentPreview(
    attachment: ChatAttachment,
    mediaAuthToken: String,
    onClick: () -> Unit
) {
    val imageKey = attachment.localBytes?.size?.let { "${attachment.id}:$it" } ?: attachment.url
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = imageKey, key2 = mediaAuthToken) {
        value = attachment.localBytes?.let { decodeImageBitmap(it) }
            ?: loadImageBitmap(attachment.mediaUrl(mediaAuthToken))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!,
                    contentDescription = attachment.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text(
                        text = "Фото",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (attachment.isUploading) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(12.dp).size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoAttachmentPreview(
    attachment: ChatAttachment,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.82f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Открыть видео",
                        tint = Color.White,
                        modifier = Modifier.padding(12.dp).size(34.dp)
                    )
                }
                Text(
                    text = attachment.fileName.ifBlank { "Видео" },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                if (attachment.isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun FileAttachmentRow(attachment: ChatAttachment, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = attachment.fileSize.formatFileSize(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (attachment.isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPreviewDialog(
    attachments: List<ChatAttachment>,
    initialIndex: Int,
    mediaAuthToken: String,
    onDismiss: () -> Unit,
    onOpenExternal: (ChatAttachment) -> Unit
) {
    if (attachments.isEmpty()) return
    val pageCount = attachments.size
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val attachment = attachments[page]
                    FullscreenAttachmentPage(
                        attachment = attachment,
                        mediaAuthToken = mediaAuthToken,
                        showPrevious = page > 0,
                        showNext = page < pageCount - 1,
                        onPrevious = {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                        },
                        onNext = {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1))
                            }
                        },
                        onOpenExternal = { onOpenExternal(attachment) }
                    )
                }

                Text(
                    text = "${pagerState.currentPage + 1} / $pageCount",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                }

                if (pageCount > 1) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Предыдущее вложение", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1))
                            }
                        },
                        enabled = pagerState.currentPage < pageCount - 1,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Следующее вложение", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenAttachmentPage(
    attachment: ChatAttachment,
    mediaAuthToken: String,
    showPrevious: Boolean,
    showNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenExternal: () -> Unit
) {
    when {
        attachment.isImage() -> FullscreenImage(attachment, mediaAuthToken)
        attachment.isVideo() -> FullscreenVideo(
            attachment = attachment,
            mediaAuthToken = mediaAuthToken,
            showPrevious = showPrevious,
            showNext = showNext,
            onPrevious = onPrevious,
            onNext = onNext
        )
        else -> FullscreenFileCard(attachment, onOpenExternal)
    }
}

@Composable
private fun FullscreenImage(
    attachment: ChatAttachment,
    mediaAuthToken: String
) {
    val imageKey = attachment.localBytes?.size?.let { "${attachment.id}:$it" } ?: attachment.url
    val imageState by produceState<ImageLoadState>(initialValue = ImageLoadState.Loading, key1 = imageKey, key2 = mediaAuthToken) {
        val bitmap = attachment.localBytes?.let { decodeImageBitmap(it) }
            ?: loadImageBitmap(attachment.mediaUrl(mediaAuthToken))
        value = if (bitmap != null) ImageLoadState.Loaded(bitmap) else ImageLoadState.Error
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        when (val state = imageState) {
            ImageLoadState.Loading -> CircularProgressIndicator(color = Color.White)
            is ImageLoadState.Loaded -> {
                androidx.compose.foundation.Image(
                    bitmap = state.bitmap,
                    contentDescription = attachment.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            ImageLoadState.Error -> Text("Не удалось загрузить фото", color = Color.White)
        }
    }
}

@Composable
private fun FullscreenVideo(
    attachment: ChatAttachment,
    mediaAuthToken: String,
    showPrevious: Boolean,
    showNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 52.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppVideoPlayer(
            url = attachment.mediaUrl(mediaAuthToken),
            modifier = Modifier.fillMaxWidth().weight(1f),
            showPrevious = showPrevious,
            showNext = showNext,
            onPrevious = onPrevious,
            onNext = onNext
        )
        Text(
            text = attachment.fileName.ifBlank { "Видео" },
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun FullscreenFileCard(
    attachment: ChatAttachment,
    onOpenExternal: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.18f)
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(18.dp).size(52.dp)
            )
        }
        Text(
            text = attachment.fileName.ifBlank { "Файл" },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = attachment.fileSize.formatFileSize(),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )
        Button(onClick = onOpenExternal) {
            Icon(Icons.Default.OpenInNew, contentDescription = null)
            Text("Открыть файл")
        }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Loaded(val bitmap: ImageBitmap) : ImageLoadState
    data object Error : ImageLoadState
}

private data class MediaPreviewState(
    val attachments: List<ChatAttachment>,
    val initialIndex: Int
)

private fun ChatAttachment.isImage(): Boolean =
    mimeType.startsWith("image/")

private fun ChatAttachment.isVideo(): Boolean =
    mimeType.startsWith("video/")

private fun ChatAttachment.canOpenAttachment(): Boolean =
    !isUploading && (url.isNotBlank() || isImage() && localBytes != null)

private fun List<ChatMessage>.dialogAttachments(): List<ChatAttachment> =
    filterNot { it.isDeleted }
        .flatMap { message ->
            message.attachments.filter { attachment -> attachment.canOpenAttachment() }
        }

private fun ChatMessage.previewText(): String {
    if (isDeleted) return "Сообщение удалено"
    if (text.isNotBlank()) return text
    val attachment = attachments.firstOrNull() ?: return "Сообщение"
    return when {
        attachment.isImage() -> "Фото"
        attachment.isVideo() -> "Видео"
        else -> attachment.fileName.ifBlank { "Файл" }
    }
}

private fun ChatMessage.shareText(): String =
    buildString {
        if (text.isNotBlank()) append(text)
        attachments.forEach { attachment ->
            if (isNotEmpty()) append('\n')
            append(attachment.fileName.ifBlank { "Вложение" })
            append(": ")
            append(attachment.url)
        }
    }.trim()

@Composable
private fun GroupSettingsDialog(
    chat: Chat,
    selfUid: String,
    onDismiss: () -> Unit,
    onUpdated: (Chat) -> Unit,
    onError: (String) -> Unit
) {
    val chatRepository = getKoin().get<ChatRepository>()
    val accountRepository = getKoin().get<AccountRepository>()
    val scope = rememberCoroutineScope()
    var title by remember(chat.id, chat.title) { mutableStateOf(chat.title) }
    var users by remember { mutableStateOf<List<PlatformUser>>(emptyList()) }
    var selectedUserIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var photoFileName by remember { mutableStateOf<String?>(null) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickPhoto by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { accountRepository.listUsers("", "ACTIVE", adminMode = false) }
            .onSuccess { users = it.filter { user -> chat.members.none { member -> member.uid == user.uid } && user.uid != selfUid } }
            .onFailure { onError(it.message ?: "Ошибка загрузки пользователей") }
    }

    if (pickPhoto) {
        ImageFilePicker { fileName, bytes ->
            photoFileName = fileName
            photoBytes = bytes
            pickPhoto = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки группы") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Button(onClick = { pickPhoto = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(photoFileName ?: "Изменить фото")
                    }
                }
                item {
                    Text("Участники", style = MaterialTheme.typography.titleSmall)
                }
                items(chat.members, key = { it.uid }) { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CachedAvatar(member.avatarUrl, size = 32.dp)
                        Text(
                            text = member.displayName(),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (member.uid != selfUid) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        runCatching { chatRepository.removeGroupMember(chat.id, member.uid) }
                                            .onSuccess(onUpdated)
                                            .onFailure { onError(it.message ?: "Ошибка удаления участника") }
                                        isSaving = false
                                    }
                                },
                                enabled = !isSaving
                            ) {
                                Text("Удалить")
                            }
                        }
                    }
                }
                if (users.isNotEmpty()) {
                    item {
                        Text("Добавить", style = MaterialTheme.typography.titleSmall)
                    }
                    items(users, key = { it.uid }) { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CachedAvatar(user.avatarUrl, size = 32.dp)
                            Text(
                                text = user.displayName(),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = {
                                    selectedUserIds = if (user.uid in selectedUserIds) {
                                        selectedUserIds - user.uid
                                    } else {
                                        selectedUserIds + user.uid
                                    }
                                }
                            ) {
                                Text(if (user.uid in selectedUserIds) "Убрать" else "Добавить")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        runCatching {
                            var updated = chatRepository.updateGroupChat(chat.id, title, photoFileName, photoBytes)
                            if (selectedUserIds.isNotEmpty()) {
                                updated = chatRepository.addGroupMembers(chat.id, selectedUserIds.toList())
                            }
                            updated
                        }.onSuccess(onUpdated)
                            .onFailure { onError(it.message ?: "Ошибка сохранения группы") }
                        isSaving = false
                    }
                },
                enabled = !isSaving
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun Chat.displayTitle(): String =
    if (type == "GROUP") this.title.ifBlank { "Групповой чат" } else {
        val user = otherUser
        "${user?.firstName.orEmpty()} ${user?.lastName.orEmpty()}".trim()
            .ifBlank { user?.nickname.orEmpty() }
            .ifBlank { "Чат" }
    }

private fun PlatformUser.displayName(): String =
    "${firstName} ${lastName}".trim().ifBlank { nickname.ifBlank { "Пользователь" } }

private fun Int.participantWord(): String {
    val mod100 = this % 100
    val mod10 = this % 10
    return when {
        mod100 in 11..14 -> "участников"
        mod10 == 1 -> "участник"
        mod10 in 2..4 -> "участника"
        else -> "участников"
    }
}

private fun Long.formatFileSize(): String =
    when {
        this >= 1024L * 1024L -> "${this / (1024L * 1024L)} МБ"
        this >= 1024L -> "${this / 1024L} КБ"
        else -> "$this Б"
    }

private fun createLocalSendingMessage(
    chatId: String,
    senderId: String,
    text: String,
    attachments: List<PendingChatAttachment>,
    now: Long
): ChatMessage {
    val messageId = "local-sending-$now"
    return ChatMessage(
        id = messageId,
        chatId = chatId,
        senderId = senderId,
        text = text,
        status = "SENDING",
        createdAtMillis = now,
        updatedAtMillis = now,
        deletedAtMillis = 0L,
        attachments = attachments.mapIndexed { index, attachment ->
            ChatAttachment(
                id = "$messageId-attachment-$index",
                messageId = messageId,
                chatId = chatId,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                fileSize = attachment.bytes.size.toLong(),
                url = "",
                createdAtMillis = now,
                localBytes = attachment.bytes,
                isUploading = true
            )
        }
    )
}

private fun ChatAttachment.mediaUrl(token: String): String {
    if (url.isBlank()) return url
    val namedUrl = "${url.trimEnd('/')}/${fileName.ifBlank { id }.encodePathSegment()}"
    return namedUrl.withTokenQuery(token)
}

private fun String.withTokenQuery(token: String): String {
    val cleanToken = token.trim()
    if (isBlank() || cleanToken.isBlank()) return this
    if (!startsWith("http://") && !startsWith("https://")) return this
    if (contains("?token=") || contains("&token=")) return this
    val separator = if (contains("?")) "&" else "?"
    return "$this${separator}token=${cleanToken.encodeQueryComponent()}"
}

private fun String.encodePathSegment(): String =
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

private fun String.encodeQueryComponent(): String =
    buildString {
        this@encodeQueryComponent.forEach { char ->
            append(
                when (char) {
                    ' ' -> "%20"
                    '%' -> "%25"
                    '@' -> "%40"
                    '#' -> "%23"
                    '&' -> "%26"
                    '?' -> "%3F"
                    '+' -> "%2B"
                    '/' -> "%2F"
                    '=' -> "%3D"
                    else -> char.toString()
                }
            )
        }
    }

private fun mimeTypeForFile(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
