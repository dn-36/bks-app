package com.bkc.screens.user_register.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.screens.user_login.domain.repository.AuthRepository
import com.bkc.screens.user_register.viewmodel.models.RegisterEffect
import com.bkc.screens.user_register.viewmodel.models.RegisterIntent
import com.bkc.screens.user_register.viewmodel.models.RegisterState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val userSessionStore: UserSessionStore
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterState())
    val state: StateFlow<RegisterState> = _state.asStateFlow()

    private val _effects = Channel<RegisterEffect>(Channel.BUFFERED)
    val effects: Flow<RegisterEffect> = _effects.receiveAsFlow()

    fun process(intent: RegisterIntent) {
        when (intent) {
            is RegisterIntent.FirstNameChanged -> _state.update { it.copy(firstName = intent.value, error = null, message = null) }
            is RegisterIntent.LastNameChanged -> _state.update { it.copy(lastName = intent.value, error = null, message = null) }
            is RegisterIntent.EmailChanged -> _state.update { it.copy(email = intent.value, error = null, message = null) }
            is RegisterIntent.PasswordChanged -> _state.update { it.copy(password = intent.value, error = null, message = null) }
            is RegisterIntent.StatusChanged -> _state.update { it.copy(status = intent.value, error = null, message = null) }
            RegisterIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val s = state.value

        if (s.firstName.isBlank() || s.lastName.isBlank() || s.email.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Заполните все поля") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, message = null) }

            runCatching {
                authRepository.register(
                   s.email.trim(),
                   s.password,
                   s.firstName,
                   s.lastName,
                   s.status.roleServerValue,
                   s.status.serverValue
               )
            }.onSuccess { message ->
                _state.update {
                    it.copy(
                        firstName = "",
                        lastName = "",
                        email = "",
                        password = "",
                        message = message
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "Ошибка регистрации") }
            }

            _state.update { it.copy(isLoading = false) }
        }
    }
}
