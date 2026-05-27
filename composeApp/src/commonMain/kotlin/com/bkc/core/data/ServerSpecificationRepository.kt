package com.bkc.core.data

import com.bkc.core.domain.MaterialRequest
import com.bkc.core.domain.MaterialRequestItem
import com.bkc.core.domain.MaterialRequestItemInput
import com.bkc.core.domain.Specification
import com.bkc.core.domain.repository.SpecificationRepository
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ServerSpecificationRepository(
    private val userSessionStore: UserSessionStore
) : SpecificationRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    override suspend fun getSpecifications(): List<Specification> {
        val response = client.get("${ApiConfig.BASE_URL}/specifications?objectId=${requireSelectedObjectId()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireSpecificationSuccess()
        return response.body<List<SpecificationDto>>().map { it.toDomain() }
    }

    override suspend fun addSpecification(
        name: String,
        unit: String,
        initialQuantity: Double,
        remainingQuantity: Double
    ) {
        val response = client.post("${ApiConfig.BASE_URL}/specifications") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveSpecificationRequest(
                    objectId = requireSelectedObjectId(),
                    name = name,
                    unit = unit,
                    initialQuantity = initialQuantity,
                    remainingQuantity = remainingQuantity
                )
            )
        }
        response.requireSpecificationSuccess()
    }

    override suspend fun updateSpecification(
        id: String,
        name: String,
        unit: String,
        initialQuantity: Double,
        remainingQuantity: Double
    ) {
        val response = client.put("${ApiConfig.BASE_URL}/specifications/$id") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveSpecificationRequest(
                    objectId = requireSelectedObjectId(),
                    name = name,
                    unit = unit,
                    initialQuantity = initialQuantity,
                    remainingQuantity = remainingQuantity
                )
            )
        }
        response.requireSpecificationSuccess()
    }

    override suspend fun deleteSpecification(id: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/specifications/$id") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireSpecificationSuccess()
    }

    override suspend fun getMaterialRequests(): List<MaterialRequest> {
        val response = client.get("${ApiConfig.BASE_URL}/material-requests?objectId=${requireSelectedObjectId()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireSpecificationSuccess()
        return response.body<List<MaterialRequestDto>>().map { it.toDomain() }
    }

    override suspend fun submitMaterialRequest(
        recipientEmail: String,
        items: List<MaterialRequestItemInput>
    ): MaterialRequest {
        val response = client.post("${ApiConfig.BASE_URL}/material-requests") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateMaterialRequest(
                    objectId = requireSelectedObjectId(),
                    recipientEmail = recipientEmail,
                    items = items.map { MaterialRequestItemInputDto(it.specificationId, it.quantity) }
                )
            )
        }
        response.requireSpecificationSuccess()
        return response.body<MaterialRequestDto>().toDomain()
    }

    override suspend fun deleteMaterialRequest(id: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/material-requests/$id") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireSpecificationSuccess()
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    private suspend fun requireSelectedObjectId(): String =
        userSessionStore.getUserOrNull()?.selectedObjectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Выберите объект")
}

private suspend fun HttpResponse.requireSpecificationSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<SpecificationErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun SpecificationDto.toDomain(): Specification =
    Specification(
        id = id,
        objectId = objectId,
        name = name,
        unit = unit,
        initialQuantity = initialQuantity,
        remainingQuantity = remainingQuantity,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun MaterialRequestDto.toDomain(): MaterialRequest =
    MaterialRequest(
        id = id,
        objectId = objectId,
        recipientEmail = recipientEmail,
        senderUid = senderUid,
        senderName = senderName,
        senderEmail = senderEmail,
        emailStatus = emailStatus,
        createdAtMillis = createdAtMillis,
        items = items.map { it.toDomain() }
    )

private fun MaterialRequestItemDto.toDomain(): MaterialRequestItem =
    MaterialRequestItem(
        id = id,
        requestId = requestId,
        specificationId = specificationId,
        specificationName = specificationName,
        unit = unit,
        quantity = quantity
    )

@Serializable
private data class SaveSpecificationRequest(
    val objectId: String,
    val name: String,
    val unit: String,
    val initialQuantity: Double,
    val remainingQuantity: Double
)

@Serializable
private data class CreateMaterialRequest(
    val objectId: String,
    val recipientEmail: String,
    val items: List<MaterialRequestItemInputDto>
)

@Serializable
private data class MaterialRequestItemInputDto(
    val specificationId: String,
    val quantity: Double
)

@Serializable
private data class SpecificationDto(
    val id: String,
    val objectId: String,
    val name: String,
    val unit: String = "",
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
private data class MaterialRequestDto(
    val id: String,
    val objectId: String,
    val recipientEmail: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val emailStatus: String,
    val createdAtMillis: Long,
    val items: List<MaterialRequestItemDto> = emptyList()
)

@Serializable
private data class MaterialRequestItemDto(
    val id: String,
    val requestId: String,
    val specificationId: String,
    val specificationName: String,
    val unit: String = "",
    val quantity: Double
)

@Serializable
private data class SpecificationErrorResponse(
    val message: String? = null
)
