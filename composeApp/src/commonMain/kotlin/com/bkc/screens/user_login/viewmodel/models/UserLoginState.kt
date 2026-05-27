package com.bkc.screens.user_login.viewmodel.models

data class UserLoginState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
