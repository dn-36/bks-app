package com.bkc.core.domain.models

data class LoginResult(
    val firstName: String,
    val lastName: String,
    val login: String,
)

interface AuthRepository {
    suspend fun login(login: String, password: String): LoginResult
}