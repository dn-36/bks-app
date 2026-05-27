package com.bkc.screens.user_register.viewmodel.models

sealed class RegisterIntent {
    data class FirstNameChanged(val value: String) : RegisterIntent()
    data class LastNameChanged(val value: String) : RegisterIntent()
    data class EmailChanged(val value: String) : RegisterIntent()
    data class PasswordChanged(val value: String) : RegisterIntent()
    data class StatusChanged(val value: RegisterStatus) : RegisterIntent()
    object Submit : RegisterIntent()
}
