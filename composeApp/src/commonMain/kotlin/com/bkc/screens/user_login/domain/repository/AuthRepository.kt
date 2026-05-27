package com.bkc.screens.user_login.domain.repository

import com.bkc.core.data.local_storage.models.UserProfile


interface AuthRepository {
    suspend fun login(email: String, password: String): AuthSession
    suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
        role: String,
        status: String
    ): String
    suspend fun recoverAccess(email: String): String
    suspend fun loadProfile(uid: String): UserProfile?
}

data class AuthSession(
    val token: String,
    val user: UserProfile
)
