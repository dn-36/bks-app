package com.bkc.screens.auth_start.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthStartViewModel : ViewModel() {

    private val _state = MutableStateFlow(AuthStartState())
    val state = _state.asStateFlow()

    private val _effect = MutableSharedFlow<AuthStartEffect>()
    val effect = _effect.asSharedFlow()

    fun process(intent: AuthStartIntent) {
        when (intent) {
            AuthStartIntent.ClickLogin -> viewModelScope.launch {
                _effect.emit(AuthStartEffect.NavigateToLogin)
            }

            AuthStartIntent.ClickRegister -> viewModelScope.launch {
                _effect.emit(AuthStartEffect.NavigateToRegister)
            }
        }
    }
}