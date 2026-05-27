package com.bkc.screens.auth_start.viewmodel

sealed interface AuthStartIntent {
    object ClickLogin : AuthStartIntent
    object ClickRegister : AuthStartIntent
}

data class AuthStartState(
    val isLoading: Boolean = false
)

sealed interface AuthStartEffect {
    object NavigateToLogin : AuthStartEffect
    object NavigateToRegister : AuthStartEffect
}