package com.bkc.screens.user_login.viewmodel.models

sealed class UserLoginEffect {
    data class NavigateToApp(val hasSelectedObject: Boolean) : UserLoginEffect()
}
