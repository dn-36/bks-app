package com.bkc.screens.users

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.CachedAvatar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.registration.RegistrationRequestsStore
import com.bkc.screens.chats.ChatScreen
import com.bkc.screens.objects.ObjectsScreen
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

private const val ACCOUNT_ACTIVE = "ACTIVE"
private const val ACCOUNT_BLOCKED = "BLOCKED"
private const val ACCOUNT_INACTIVE = "INACTIVE"

private enum class UsersSection {
    Requests,
    Users
}

class UsersScreen(
    private val showBackButton: Boolean = true,
    val initialFilter: String = ""
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        val accountRepository = getKoin().get<AccountRepository>()
        val chatRepository = getKoin().get<ChatRepository>()
        val sessionStore = getKoin().get<UserSessionStore>()
        val scope = rememberCoroutineScope()
        val pendingRegistrations by RegistrationRequestsStore.pendingCount.collectAsState()

        var query by remember { mutableStateOf("") }
        var section by remember {
            mutableStateOf(if (initialFilter == ACCOUNT_INACTIVE) UsersSection.Requests else UsersSection.Users)
        }
        var users by remember { mutableStateOf<List<PlatformUser>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var isAdmin by remember { mutableStateOf(false) }
        var selfUid by remember { mutableStateOf("") }
        var blockTarget by remember { mutableStateOf<PlatformUser?>(null) }
        var blockReason by remember { mutableStateOf("") }
        var deleteTarget by remember { mutableStateOf<PlatformUser?>(null) }
        var deleteAllUserData by remember { mutableStateOf(false) }

        fun load(targetSection: UsersSection = section) {
            scope.launch {
                isLoading = true
                error = null
                val accountStatus = if (targetSection == UsersSection.Requests) ACCOUNT_INACTIVE else null
                val result = runCatching {
                    accountRepository.listUsers(query, accountStatus, adminMode = isAdmin)
                }
                if (result.isSuccess) {
                    val loadedUsers = result.getOrThrow()
                    users = loadedUsers
                    if (isAdmin) {
                        val pendingCount = if (targetSection == UsersSection.Requests && query.isBlank()) {
                            loadedUsers.size
                        } else {
                            runCatching {
                                accountRepository.listUsers("", ACCOUNT_INACTIVE, adminMode = true).size
                            }.getOrNull()
                        }
                        pendingCount?.let { RegistrationRequestsStore.setPendingCount(it) }
                    }
                } else {
                    error = result.exceptionOrNull()?.message ?: "Ошибка загрузки пользователей"
                }
                isLoading = false
            }
        }

        LaunchedEffect(Unit) {
            val user = sessionStore.getUserOrNull()
            isAdmin = user?.status == "ADMINISTRATOR"
            selfUid = user?.uid.orEmpty()
            if (!isAdmin && section == UsersSection.Requests) {
                section = UsersSection.Users
            }
            load()
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = query,
                    onSearchChange = {
                        query = it
                        load()
                    },
                    onClear = {
                        query = ""
                        load()
                    },
                    showSearch = true,
                    statusText = if (isAdmin && pendingRegistrations > 0) {
                        "Заявки: ${if (pendingRegistrations > 99) "99+" else pendingRegistrations}"
                    } else {
                        null
                    },
                    onBackClick = if (showBackButton) {
                        {
                            val popped = runCatching { navigator?.pop() == true }.getOrDefault(false)
                            if (!popped) {
                                runCatching { navigator?.parent?.pop() }
                            }
                        }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isAdmin) {
                        SectionButton(
                            text = if (pendingRegistrations > 0) {
                                "Заявки (${if (pendingRegistrations > 99) "99+" else pendingRegistrations})"
                            } else {
                                "Заявки"
                            },
                            selected = section == UsersSection.Requests,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                section = UsersSection.Requests
                                load(UsersSection.Requests)
                            }
                        )
                    }
                    SectionButton(
                        text = "Пользователи",
                        selected = section == UsersSection.Users,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            section = UsersSection.Users
                            load(UsersSection.Users)
                        }
                    )
                    Button(onClick = { load() }) {
                        Text("Обновить")
                    }
                }

                if (section == UsersSection.Requests) {
                    Text(
                        text = "Пользователи, ожидающие доступ к системе",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                }

                when {
                    isLoading -> LoadingState()
                    users.isEmpty() -> EmptyState(
                        if (section == UsersSection.Requests) "Заявок нет" else "Пользователи не найдены",
                        Modifier.fillMaxSize()
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(users, key = { it.uid }) { user ->
                            UserCard(
                                user = user,
                                isSelf = user.uid == selfUid,
                                isAdmin = isAdmin,
                                onChat = {
                                    scope.launch {
                                        message = "Открываем чат"
                                        error = null
                                        runCatching { chatRepository.createDirectChat(user.uid) }
                                            .onSuccess { chat ->
                                                message = null
                                                navigator?.push(ChatScreen(chat.id, user.displayName(), user.avatarUrl))
                                            }
                                            .onFailure {
                                                message = null
                                                error = it.message ?: "Ошибка создания чата"
                                            }
                                    }
                                },
                                onBlock = {
                                    blockReason = ""
                                    blockTarget = user
                                },
                                onUnblock = {
                                    scope.launch {
                                        runCatching { accountRepository.updateUserAccess(user.uid, ACCOUNT_ACTIVE, "") }
                                            .onSuccess {
                                                message = "Пользователь разблокирован"
                                                load()
                                            }
                                            .onFailure { error = it.message ?: "Ошибка изменения статуса" }
                                    }
                                },
                                onApprove = {
                                    scope.launch {
                                        runCatching { accountRepository.updateUserAccess(user.uid, ACCOUNT_ACTIVE, "") }
                                            .onSuccess {
                                                message = "Доступ выдан"
                                                load()
                                            }
                                            .onFailure { error = it.message ?: "Ошибка выдачи доступа" }
                                    }
                                },
                                onReject = {
                                    scope.launch {
                                        runCatching {
                                            accountRepository.updateUserAccess(
                                                user.uid,
                                                ACCOUNT_BLOCKED,
                                                "Заявка на регистрацию отклонена администратором"
                                            )
                                        }.onSuccess {
                                            message = "Заявка отклонена"
                                            load()
                                        }.onFailure { error = it.message ?: "Ошибка отказа в доступе" }
                                    }
                                },
                                onDelete = {
                                    deleteAllUserData = false
                                    deleteTarget = user
                                }
                            )
                        }
                    }
                }
            }
        }

        blockTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { blockTarget = null },
                title = { Text("Заблокировать пользователя") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${target.firstName} ${target.lastName}")
                        OutlinedTextField(
                            value = blockReason,
                            onValueChange = { blockReason = it },
                            label = { Text("Причина блокировки") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { accountRepository.updateUserAccess(target.uid, ACCOUNT_BLOCKED, blockReason) }
                                    .onSuccess {
                                        message = "Пользователь заблокирован"
                                        blockTarget = null
                                        load()
                                    }
                                    .onFailure { error = it.message ?: "Ошибка блокировки" }
                            }
                        }
                    ) { Text("Заблокировать") }
                },
                dismissButton = {
                    TextButton(onClick = { blockTarget = null }) {
                        Text("Отмена")
                    }
                }
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Удалить пользователя") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(target.displayName())
                        Text(
                            text = "Пользователь будет помечен как удаленный. В чатах, графиках и отчетах останется метка, что пользователь удален из системы.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = deleteAllUserData,
                                onCheckedChange = { deleteAllUserData = it }
                            )
                            Text(
                                text = "Сразу удалить все данные: график, сменные задания и чаты",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { accountRepository.deleteUser(target.uid, deleteAllUserData) }
                                    .onSuccess {
                                        message = if (deleteAllUserData) {
                                            "Пользователь и его данные удалены"
                                        } else {
                                            "Пользователь помечен как удаленный"
                                        }
                                        deleteTarget = null
                                        deleteAllUserData = false
                                        load()
                                    }
                                    .onFailure { error = it.message ?: "Ошибка удаления пользователя" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text("Удалить") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        deleteAllUserData = false
                    }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(44.dp)) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(44.dp)) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UserCard(
    user: PlatformUser,
    isSelf: Boolean,
    isAdmin: Boolean,
    onChat: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserAvatar(user.avatarUrl)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${user.firstName} ${user.lastName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${user.nickname}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${accessTitle(user.accountStatus)} · ${if (user.isOnline) "онлайн" else "оффлайн"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isSelf) {
                Column(horizontalAlignment = Alignment.End) {
                    if (user.accountStatus == ACCOUNT_ACTIVE) {
                        TextButton(onClick = onChat) {
                            Text("Чат")
                        }
                    }
                    if (isAdmin) {
                        when (user.accountStatus) {
                            ACCOUNT_INACTIVE -> {
                                TextButton(onClick = onApprove) { Text("Доступ") }
                                TextButton(onClick = onReject) { Text("Отказать") }
                            }
                            ACCOUNT_BLOCKED -> TextButton(onClick = onUnblock) { Text("Разблок.") }
                            else -> TextButton(onClick = onBlock) { Text("Блок") }
                        }
                        TextButton(onClick = onDelete) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserAvatar(avatarUrl: String?) {
    CachedAvatar(avatarUrl = avatarUrl, size = 52.dp)
}

private fun accessTitle(status: String): String =
    when (status) {
        ACCOUNT_ACTIVE -> "активен"
        ACCOUNT_BLOCKED -> "заблокирован"
        ACCOUNT_INACTIVE -> "ожидает доступа"
        else -> status
    }

private fun PlatformUser.displayName(): String =
    "${firstName} ${lastName}".trim().ifBlank {
        nickname.ifBlank { "Пользователь" }
    }
