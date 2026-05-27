package com.bkc.screens.user_login.viewmodel.models

sealed class UserLoginIntent {
    data class LoginChanged(val value: String) : UserLoginIntent()
    data class PasswordChanged(val value: String) : UserLoginIntent()
    object RecoverAccess : UserLoginIntent()
    object Submit : UserLoginIntent()
}
