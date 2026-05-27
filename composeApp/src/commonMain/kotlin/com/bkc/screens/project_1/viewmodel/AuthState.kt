package com.bkc.screens.project_1.viewmodel

import kotlinx.coroutines.flow.StateFlow

data class AuthState(
    val isLoggedIn: Boolean,
    val userEmail: String? = null
)

interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun login(email: String, password: String)
    suspend fun logout()
    suspend fun getIdToken(): String? // нужен для Desktop REST / запросов
}
