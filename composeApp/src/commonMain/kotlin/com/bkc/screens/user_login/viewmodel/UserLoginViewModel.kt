package com.bkc.screens.user_login.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bkc.core.data.local_storage.SavedUser
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.notifications.AppNotifications
import com.bkc.screens.user_login.domain.repository.AuthRepository
import com.bkc.screens.user_login.viewmodel.models.UserLoginEffect
import com.bkc.screens.user_login.viewmodel.models.UserLoginIntent
import com.bkc.screens.user_login.viewmodel.models.UserLoginState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UserLoginViewModel(
    private val authRepository: AuthRepository,
    private val userSessionStore: UserSessionStore,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _state = MutableStateFlow(UserLoginState())
    val state: StateFlow<UserLoginState> = _state.asStateFlow()

    private val _effects = Channel<UserLoginEffect>(Channel.BUFFERED)
    val effects: Flow<UserLoginEffect> = _effects.receiveAsFlow()

    fun process(intent: UserLoginIntent) {
        when (intent) {
            is UserLoginIntent.LoginChanged ->
                _state.update { it.copy(login = intent.value, error = null, message = null) }

            is UserLoginIntent.PasswordChanged ->
                _state.update { it.copy(password = intent.value, error = null, message = null) }

            UserLoginIntent.RecoverAccess -> recoverAccess()
            UserLoginIntent.Submit -> submit()
        }
    }

    private fun recoverAccess() {
        val email = state.value.login.trim()
        if (email.isBlank()) {
            _state.update { it.copy(error = "Укажите email для восстановления") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, message = null) }
            runCatching { authRepository.recoverAccess(email) }
                .onSuccess { message -> _state.update { it.copy(message = message) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Ошибка восстановления доступа") } }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun submit() {
        val s = state.value
        if (s.login.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Заполните логин и пароль") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            runCatching {
                authRepository.login(s.login.trim(), s.password)
            }.onSuccess { session ->
                val userData = session.user
                val previousUser = userSessionStore.getUserOrNull()?.takeIf { it.uid == userData.uid }
                // ✅ сохраняем пользователя
                userSessionStore.saveUser(
                    SavedUser(
                        uid = userData.uid,
                        email = userData.email ?: "",
                        firstName = userData.firstName,
                        lastName = userData.lastName,
                        nickname = userData.nickname,
                        avatarUrl = userData.avatarUrl,
                        bio = userData.bio,
                        phone = userData.phone,
                        authToken = session.token,
                        role = userData.role,
                        status = userData.status,
                        accountStatus = userData.accountStatus,
                        blockedReason = userData.blockedReason,
                        privacyProfileVisible = userData.privacyProfileVisible,
                        notificationsEnabled = userData.notificationsEnabled,
                        createdAt = userData.createdAt,
                        selectedObjectId = previousUser?.selectedObjectId,
                        selectedObjectName = previousUser?.selectedObjectName,
                        selectedObjectPhotoUrl = previousUser?.selectedObjectPhotoUrl
                    )
                )
                AppNotifications.requestPermissionIfNeeded()
                AppNotifications.registerPushToken()
                chatRepository.startRealtime()

                _effects.send(UserLoginEffect.NavigateToApp(previousUser?.selectedObjectId != null))
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Ошибка входа") }
            }

            _state.update { it.copy(isLoading = false) }
        }
    }
}
