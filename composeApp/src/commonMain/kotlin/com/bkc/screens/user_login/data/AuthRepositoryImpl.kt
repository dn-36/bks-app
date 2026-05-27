package com.bkc.screens.user_login.data

import com.bkc.core.data.local_storage.models.UserProfile
import com.bkc.core.network.ApiConfig
import com.bkc.screens.user_login.domain.repository.AuthSession
import com.bkc.screens.user_login.domain.repository.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AuthRepositoryImpl : AuthRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    private var authToken: String? = null
    private var cachedUser: UserProfile? = null

    override suspend fun login(email: String, password: String): AuthSession {
        val response = client.post("${ApiConfig.BASE_URL}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }

        response.requireSuccess()

        val body = response.body<AuthResponse>()
        val user = body.user.toProfile()
        authToken = body.token
        cachedUser = user

        return AuthSession(
            token = body.token,
            user = user
        )
    }

    override suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
        role: String,
        status: String
    ): String {
        val response = client.post("${ApiConfig.BASE_URL}/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = email,
                    password = password,
                    firstName = firstName,
                    lastName = lastName,
                    role = role,
                    status = status
                )
            )
        }

        response.requireSuccess()
        return response.body<RegistrationResponse>().message
    }

    override suspend fun recoverAccess(email: String): String {
        val response = client.post("${ApiConfig.BASE_URL}/auth/recover") {
            contentType(ContentType.Application.Json)
            setBody(RecoverAccessRequest(email = email))
        }

        response.requireSuccess()
        return response.body<RecoverAccessResponse>().message
    }

    override suspend fun loadProfile(uid: String): UserProfile? {
        cachedUser?.takeIf { it.uid == uid }?.let { return it }

        val token = authToken ?: return null
        val response = client.get("${ApiConfig.BASE_URL}/auth/users/$uid") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        response.requireSuccess()

        return response.body<UserDto>().toProfile().also {
            cachedUser = it
        }
    }
}

private suspend fun HttpResponse.requireSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun UserDto.toProfile(): UserProfile =
    UserProfile(
        uid = uid,
        email = email,
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarUrl?.toAbsoluteApiUrl(),
        bio = bio,
        phone = phone,
        role = role,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason,
        privacyProfileVisible = privacyProfileVisible,
        notificationsEnabled = notificationsEnabled,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
        createdAt = createdAt
    )

private fun String.toAbsoluteApiUrl(): String {
    if (startsWith("http://") || startsWith("https://")) return this
    return "${ApiConfig.BASE_URL.trimEnd('/')}/${trimStart('/')}"
}

@Serializable
private data class RegisterRequest(
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String,
    val role: String,
    val status: String
)

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
private data class RecoverAccessRequest(
    val email: String
)

@Serializable
private data class RecoverAccessResponse(
    val message: String
)

@Serializable
private data class RegistrationResponse(
    val message: String
)

@Serializable
private data class AuthResponse(
    val token: String,
    val user: UserDto
)

@Serializable
private data class UserDto(
    val uid: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String = "",
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String = "",
    val privacyProfileVisible: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
private data class ErrorResponse(
    val message: String? = null
)
