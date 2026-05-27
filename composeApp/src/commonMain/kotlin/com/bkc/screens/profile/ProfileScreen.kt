package com.bkc.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.data.local_storage.models.UserProfile
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.media.ImageFilePicker
import com.bkc.core.presentation.media.decodeImageBitmap
import com.bkc.core.presentation.media.loadImageBitmap
import com.bkc.core.presentation.navigation.LogoutNavigationStore
import com.bkc.core.presentation.notifications.AppNotifications
import com.bkc.core.presentation.registration.RegistrationRequestsStore
import com.bkc.core.presentation.utils.toUserStatusTitle
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.users.UsersScreen
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

class ProfileScreen : Screen {
    @Composable
    override fun Content() {
        val appStateStore: AppStateStore = getKoin().get()
        val appState by appStateStore.state.collectAsState()
        val navigator = LocalNavigator.current
        val accountRepository = getKoin().get<AccountRepository>()
        val sessionStore = getKoin().get<UserSessionStore>()
        val scope = rememberCoroutineScope()
        val pendingRegistrations by RegistrationRequestsStore.pendingCount.collectAsState()

        var query by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(true) }
        var isSaving by remember { mutableStateOf(false) }
        var isLoggingOut by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var profile by remember { mutableStateOf<UserProfile?>(null) }
        var email by remember { mutableStateOf("") }
        var firstName by remember { mutableStateOf("") }
        var lastName by remember { mutableStateOf("") }
        var avatarFileName by remember { mutableStateOf<String?>(null) }
        var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
        var pickAvatar by remember { mutableStateOf(false) }

        fun applyProfile(next: UserProfile) {
            profile = next
            email = next.email.orEmpty()
            firstName = next.firstName
            lastName = next.lastName
            avatarFileName = null
            avatarBytes = null
        }

        LaunchedEffect(Unit) {
            isLoading = true
            runCatching { accountRepository.loadMe() }
                .onSuccess {
                    applyProfile(it)
                    if (it.status == "ADMINISTRATOR") {
                        runCatching { accountRepository.listUsers("", "INACTIVE", adminMode = true).size }
                            .onSuccess { count -> RegistrationRequestsStore.setPendingCount(count) }
                    }
                }
                .onFailure { error = it.message ?: "Ошибка загрузки профиля" }
            isLoading = false
        }

        if (pickAvatar) {
            ImageFilePicker { fileName, bytes ->
                avatarFileName = fileName
                avatarBytes = bytes
                pickAvatar = false
            }
        }

        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                organization = appState.organizationName,
                objectName = appState.selectedObjectName,
                searchQuery = query,
                onSearchChange = { query = it },
                onClear = { query = "" },
                onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
            )

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        ProfileAvatar(
                            avatarUrl = profile?.avatarUrl,
                            avatarBytes = avatarBytes,
                            size = (maxWidth.value / 3f).dp,
                            onClick = { pickAvatar = true }
                        )
                    }
                    Button(
                        onClick = { pickAvatar = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (profile?.avatarUrl.isNullOrBlank() && avatarBytes == null) "Добавить фото" else "Изменить фото")
                    }
                    avatarFileName?.let {
                        Text(
                            text = "Выбрано: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text("Основная информация", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(firstName, { firstName = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("Фамилия") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    profile?.let {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Почта") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = it.status.toUserStatusTitle(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Статус") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (it.status == "ADMINISTRATOR") {
                            Button(
                                onClick = { navigator?.push(UsersScreen(showBackButton = true, initialFilter = "INACTIVE")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Пользователи")
                                    if (pendingRegistrations > 0) {
                                        Spacer(Modifier.size(8.dp))
                                        Badge {
                                            Text(if (pendingRegistrations > 99) "99+" else pendingRegistrations.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
                                error = "Введите корректный email"
                                message = null
                                return@Button
                            }
                            scope.launch {
                                isSaving = true
                                error = null
                                message = null
                                runCatching {
                                    accountRepository.updateMe(
                                        email = email,
                                        firstName = firstName,
                                        lastName = lastName,
                                        nickname = profile?.nickname.orEmpty(),
                                        bio = profile?.bio.orEmpty(),
                                        phone = profile?.phone.orEmpty(),
                                        avatarFileName = avatarFileName,
                                        avatarBytes = avatarBytes,
                                        privacyProfileVisible = profile?.privacyProfileVisible ?: true,
                                        notificationsEnabled = profile?.notificationsEnabled ?: true
                                    )
                                }.onSuccess { updated ->
                                    applyProfile(updated)
                                    val current = sessionStore.getUserOrNull()
                                    if (current != null) {
                                        sessionStore.saveUser(
                                            current.copy(
                                                firstName = updated.firstName,
                                                lastName = updated.lastName,
                                                email = updated.email ?: current.email,
                                                nickname = updated.nickname,
                                                avatarUrl = updated.avatarUrl,
                                                bio = updated.bio,
                                                phone = updated.phone,
                                                accountStatus = updated.accountStatus,
                                                blockedReason = updated.blockedReason,
                                                privacyProfileVisible = updated.privacyProfileVisible,
                                                notificationsEnabled = updated.notificationsEnabled,
                                                createdAt = updated.createdAt
                                            )
                                        )
                                    }
                                    message = "Профиль сохранен"
                                }.onFailure {
                                    error = it.message ?: "Ошибка сохранения профиля"
                                }
                                isSaving = false
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Сохранить")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoggingOut = true
                                error = null
                                message = null
                                AppNotifications.unregisterPushToken()
                                sessionStore.clear()
                                appStateStore.selectObject(null, null, null)
                                LogoutNavigationStore.requestLogoutNavigation()
                            }
                        },
                        enabled = !isSaving && !isLoggingOut,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoggingOut) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Выйти из аккаунта")
                    }

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar(
    avatarUrl: String?,
    avatarBytes: ByteArray?,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(initialValue = null, avatarUrl, avatarBytes) {
        value = avatarBytes?.let { decodeImageBitmap(it) }
            ?: avatarUrl?.let { loadImageBitmap(it) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(size * 0.7f))
        }
    }
}
