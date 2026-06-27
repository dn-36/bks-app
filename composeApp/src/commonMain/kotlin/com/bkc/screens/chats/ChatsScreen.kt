package com.bkc.screens.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.PlatformUser
import com.bkc.core.domain.chat.Chat
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.CachedAvatar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.media.ImageFilePicker
import com.bkc.core.presentation.notifications.NotificationOpenStore
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.users.UsersScreen
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform.getKoin

private enum class ChatCreateMode {
    Choice,
    Group
}

class ChatsScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        val chatRepository = getKoin().get<ChatRepository>()
        val accountRepository = getKoin().get<AccountRepository>()
        val chatFlow = remember { chatRepository.observeChats() }
        val chats by chatFlow.collectAsState(emptyList())
        val unreadChatCounts by remember { chatRepository.observeUnreadChatCounts() }.collectAsState(emptyMap())
        val pendingChat by NotificationOpenStore.pendingChat.collectAsState()
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var createMode by remember { mutableStateOf<ChatCreateMode?>(null) }
        var chatToDelete by remember { mutableStateOf<Chat?>(null) }

        fun load(newQuery: String = query) {
            scope.launch {
                isLoading = true
                error = null
                runCatching { chatRepository.refreshChats(newQuery) }
                    .onFailure { error = it.message ?: "Ошибка загрузки чатов" }
                isLoading = false
            }
        }

        LaunchedEffect(Unit) {
            load()
        }

        LaunchedEffect(pendingChat) {
            val target = pendingChat ?: return@LaunchedEffect
            chatRepository.markChatRead(target.chatId)
            navigator?.push(ChatScreen(target.chatId, target.title, target.avatarUrl))
            NotificationOpenStore.consume(target)
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = query,
                    onSearchChange = {
                        query = it
                        load(it)
                    },
                    onClear = {
                        query = ""
                        load("")
                    },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { createMode = ChatCreateMode.Choice },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Создать чат") },
                    text = { Text("Создать чат") }
                )
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

                when {
                    isLoading && chats.isEmpty() -> LoadingState()
                    chats.isEmpty() -> EmptyState("Чаты не найдены", Modifier.fillMaxSize())
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(chats, key = { it.id }) { chat ->
                            ChatCard(
                                chat = chat,
                                unreadCount = unreadChatCounts[chat.id] ?: 0,
                                onClick = {
                                    chatRepository.markChatRead(chat.id)
                                    navigator?.push(ChatScreen(chat.id, chat.title(), chat.avatarUrl()))
                                },
                                onDelete = { chatToDelete = chat }
                            )
                        }
                    }
                }
            }
        }

        if (createMode == ChatCreateMode.Choice) {
            AlertDialog(
                onDismissRequest = { createMode = null },
                title = { Text("Создать чат") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                createMode = null
                                navigator?.push(UsersScreen(showBackButton = true))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Личный чат")
                        }
                        Button(
                            onClick = {
                                createMode = ChatCreateMode.Group
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Групповой чат")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { createMode = null }) {
                        Text("Отмена")
                    }
                }
            )
        }

        if (createMode == ChatCreateMode.Group) {
            CreateGroupChatDialog(
                accountRepository = accountRepository,
                chatRepository = chatRepository,
                onDismiss = { createMode = null },
                onCreated = { chat ->
                    createMode = null
                    navigator?.push(ChatScreen(chat.id, chat.title(), chat.avatarUrl()))
                }
            )
        }

        chatToDelete?.let { chat ->
            AlertDialog(
                onDismissRequest = { chatToDelete = null },
                title = { Text("Удалить чат?") },
                text = { Text("Чат и все сообщения будут удалены.") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                error = null
                                runCatching { chatRepository.deleteChat(chat.id) }
                                    .onSuccess { chatToDelete = null }
                                    .onFailure { error = it.message ?: "Ошибка удаления чата" }
                            }
                        }
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { chatToDelete = null }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatCard(
    chat: Chat,
    unreadCount: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChatAvatar(chat)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chat.title(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = chat.lastMessage?.createdAtMillis?.formatChatTime().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (unreadCount > 0) {
                        Badge { Text(unreadCount.badgeText()) }
                    }
                }
                Text(
                    text = chat.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = chat.lastMessagePreview(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun Int.badgeText(): String =
    if (this > 99) "99+" else toString()

@Composable
private fun ChatAvatar(chat: Chat) {
    if (chat.type == "GROUP" && chat.photoUrl.isNullOrBlank()) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = "Групповой чат",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    } else {
        CachedAvatar(avatarUrl = chat.avatarUrl(), size = 52.dp)
    }
}

@Composable
private fun CreateGroupChatDialog(
    accountRepository: AccountRepository,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onCreated: (Chat) -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<PlatformUser>>(emptyList()) }
    var selectedUserIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var photoFileName by remember { mutableStateOf<String?>(null) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickPhoto by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { accountRepository.listUsers("", "ACTIVE", adminMode = false) }
            .onSuccess { users = it }
            .onFailure { dialogError = it.message ?: "Ошибка загрузки пользователей" }
    }

    if (pickPhoto) {
        ImageFilePicker(
            onDismiss = { pickPhoto = false }
        ) { fileName, bytes ->
            photoFileName = fileName
            photoBytes = bytes
            pickPhoto = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый групповой чат") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dialogError?.let { message ->
                    item {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            dialogError = null
                        },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Button(onClick = { pickPhoto = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Text(photoFileName ?: "Выбрать фото")
                    }
                }
                item {
                    Text("Участники", style = MaterialTheme.typography.titleSmall)
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
                                dialogError = null
                            }
                        ) {
                            Text(if (user.uid in selectedUserIds) "Убрать" else "Добавить")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank() || selectedUserIds.isEmpty()) {
                        dialogError = "Введите название и выберите участников"
                        return@Button
                    }
                    scope.launch {
                        isSaving = true
                        dialogError = null
                        runCatching {
                            chatRepository.createGroupChat(
                                title = title,
                                memberUserIds = selectedUserIds.toList(),
                                photoFileName = photoFileName,
                                photoBytes = photoBytes
                            )
                        }.onSuccess(onCreated)
                            .onFailure { dialogError = it.message ?: "Ошибка создания чата" }
                        isSaving = false
                    }
                },
                enabled = !isSaving
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

internal fun Chat.title(): String {
    if (type == "GROUP") return this.title.ifBlank { "Групповой чат" }
    val user = otherUser ?: return "Личный чат"
    return "${user.firstName} ${user.lastName}".trim().ifBlank {
        user.nickname.ifBlank { "Пользователь" }
    }
}

private fun Chat.avatarUrl(): String? =
    if (type == "GROUP") photoUrl else otherUser?.avatarUrl

private fun Chat.subtitle(): String =
    if (type == "GROUP") {
        "Групповой чат · ${members.size} ${members.size.participantWord()}"
    } else {
        listOfNotNull(
            "Личная переписка",
            otherUser?.nickname?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ).joinToString(" · ")
    }

private fun Chat.lastMessagePreview(): String {
    val message = lastMessage ?: return "Сообщений пока нет"
    val preview = when {
        message.isDeleted -> "Сообщение удалено"
        message.text.isNotBlank() -> message.text
        message.attachments.isNotEmpty() -> message.attachments.first().fileName
        else -> ""
    }
    if (type != "GROUP" || preview.isBlank()) return preview
    val senderName = members.firstOrNull { it.uid == message.senderId }?.firstName.orEmpty()
    return senderName.takeIf { it.isNotBlank() }?.let { "$it: $preview" } ?: preview
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

internal fun Long.formatChatTime(): String {
    if (this <= 0L) return ""
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dateTime.dayOfMonth.twoDigits()}.${dateTime.monthNumber.twoDigits()} ${dateTime.hour.twoDigits()}:${dateTime.minute.twoDigits()}"
}

private fun Int.twoDigits(): String =
    if (this < 10) "0$this" else toString()
