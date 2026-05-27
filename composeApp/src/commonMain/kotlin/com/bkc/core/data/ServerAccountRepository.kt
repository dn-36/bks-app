package com.bkc.core.data

import com.bkc.core.data.local_storage.models.UserProfile
import com.bkc.core.domain.PlatformUser
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.network.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ServerAccountRepository(
    private val userSessionStore: UserSessionStore
) : AccountRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    override suspend fun loadMe(): UserProfile {
        val response = client.get("${ApiConfig.BASE_URL}/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireAccountSuccess()
        return response.body<UserDto>().toProfile()
    }

    override suspend fun updateMe(
        email: String,
        firstName: String,
        lastName: String,
        nickname: String,
        bio: String,
        phone: String,
        avatarFileName: String?,
        avatarBytes: ByteArray?,
        privacyProfileVisible: Boolean,
        notificationsEnabled: Boolean
    ): UserProfile {
        val response = client.put("${ApiConfig.BASE_URL}/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                UpdateProfileRequest(
                    email = email,
                    firstName = firstName,
                    lastName = lastName,
                    nickname = nickname,
                    bio = bio,
                    phone = phone,
                    avatarFileName = avatarFileName.orEmpty(),
                    avatarBase64 = avatarBytes.toBase64OrEmpty(),
                    privacyProfileVisible = privacyProfileVisible,
                    notificationsEnabled = notificationsEnabled
                )
            )
        }
        response.requireAccountSuccess()
        return response.body<UserDto>().toProfile()
    }

    override suspend fun listUsers(query: String, accountStatus: String?, adminMode: Boolean): List<PlatformUser> {
        val path = if (adminMode) "admin/users" else "users"
        val queryString = buildString {
            append("?query=${query.encodeQueryComponent()}")
            accountStatus?.takeIf { it.isNotBlank() }?.let { append("&accountStatus=$it") }
        }
        val response = client.get("${ApiConfig.BASE_URL}/$path$queryString") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireAccountSuccess()
        return response.body<List<PublicUserDto>>().map { it.toDomain() }
    }

    override suspend fun updateUserAccess(uid: String, accountStatus: String, blockedReason: String) {
        val response = client.put("${ApiConfig.BASE_URL}/admin/users/$uid/access") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserAccessRequest(accountStatus, blockedReason))
        }
        response.requireAccountSuccess()
    }

    override suspend fun deleteUser(uid: String, deleteData: Boolean) {
        val response = client.delete("${ApiConfig.BASE_URL}/admin/users/$uid?deleteData=$deleteData") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireAccountSuccess()
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray?.toBase64OrEmpty(): String =
        if (this == null || isEmpty()) "" else Base64.encode(this)
}

private suspend fun HttpResponse.requireAccountSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<AccountErrorResponse>().message
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
        avatarUrl = avatarUrl?.toAbsoluteAccountApiUrl(),
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

private fun PublicUserDto.toDomain(): PlatformUser =
    PlatformUser(
        uid = uid,
        email = email,
        firstName = firstName,
        lastName = lastName,
        nickname = nickname,
        avatarUrl = avatarUrl?.toAbsoluteAccountApiUrl(),
        bio = bio,
        phone = phone,
        role = role,
        status = status,
        accountStatus = accountStatus,
        blockedReason = blockedReason,
        isOnline = isOnline,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt
    )

private fun String.toAbsoluteAccountApiUrl(): String {
    if (startsWith("http://") || startsWith("https://")) return this
    return "${ApiConfig.BASE_URL.trimEnd('/')}/${trimStart('/')}"
}

private fun String.encodeQueryComponent(): String =
    replace(" ", "%20")
        .replace("@", "%40")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("?", "%3F")

@Serializable
private data class UpdateProfileRequest(
    val email: String,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val bio: String,
    val phone: String,
    val avatarFileName: String,
    val avatarBase64: String,
    val privacyProfileVisible: Boolean,
    val notificationsEnabled: Boolean
)

@Serializable
private data class UpdateUserAccessRequest(
    val accountStatus: String,
    val blockedReason: String = ""
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
private data class PublicUserDto(
    val uid: String,
    val email: String? = null,
    val firstName: String,
    val lastName: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val bio: String = "",
    val phone: String? = null,
    val role: String = "USER",
    val status: String = "ELECTRICIAN",
    val accountStatus: String = "ACTIVE",
    val blockedReason: String? = null,
    val isOnline: Boolean = false,
    val lastSeenAt: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
private data class AccountErrorResponse(
    val message: String? = null
)
