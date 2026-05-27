package com.bkc.core.data

import com.bkc.core.domain.WorkObject
import com.bkc.core.domain.repository.ObjectRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.network.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

class ServerObjectRepository(
    private val userSessionStore: UserSessionStore
) : ObjectRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    override suspend fun getObjects(): List<WorkObject> {
        val response = client.get("${ApiConfig.BASE_URL}/objects") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireObjectSuccess()
        return response.body<List<ObjectDto>>().map { it.toDomain() }
    }

    override suspend fun addObject(name: String, photoFileName: String?, photoBytes: ByteArray?) {
        val response = client.post("${ApiConfig.BASE_URL}/objects") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(SaveObjectRequest(name, photoFileName.orEmpty(), photoBytes.toBase64OrEmpty()))
        }
        response.requireObjectSuccess()
    }

    override suspend fun updateObject(id: String, name: String, photoFileName: String?, photoBytes: ByteArray?) {
        val response = client.put("${ApiConfig.BASE_URL}/objects/$id") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(SaveObjectRequest(name, photoFileName.orEmpty(), photoBytes.toBase64OrEmpty()))
        }
        response.requireObjectSuccess()
    }

    override suspend fun deleteObject(id: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/objects/$id") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireObjectSuccess()
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray?.toBase64OrEmpty(): String =
        if (this == null || isEmpty()) "" else Base64.encode(this)
}

private suspend fun HttpResponse.requireObjectSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ObjectErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun ObjectDto.toDomain(): WorkObject =
    WorkObject(
        id = id,
        name = name,
        photoUrl = photoUrl?.toAbsoluteObjectApiUrl()
    )

private fun String.toAbsoluteObjectApiUrl(): String {
    if (startsWith("http://") || startsWith("https://")) return this
    return "${ApiConfig.BASE_URL.trimEnd('/')}/${trimStart('/')}"
}

@Serializable
private data class SaveObjectRequest(
    val name: String,
    val photoFileName: String = "",
    val photoBase64: String = ""
)

@Serializable
private data class ObjectDto(
    val id: String,
    val name: String,
    val photoUrl: String? = null
)

@Serializable
private data class ObjectErrorResponse(
    val message: String? = null
)
