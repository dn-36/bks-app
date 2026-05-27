package com.bkc.core.data

import com.bkc.core.domain.SHIFT_QUESTION_CHOICE
import com.bkc.core.domain.ShiftAnswerInput
import com.bkc.core.domain.ShiftForm
import com.bkc.core.domain.ShiftQuestion
import com.bkc.core.domain.ShiftQuestionInput
import com.bkc.core.domain.ShiftReport
import com.bkc.core.domain.ShiftReportAnswer
import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.network.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

class ServerShiftTaskRepository(
    private val userSessionStore: UserSessionStore
) : ShiftTaskRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    override suspend fun getForm(): ShiftForm {
        val response = client.get("${ApiConfig.BASE_URL}/shift-form?objectId=${requireSelectedObjectId()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireShiftSuccess()
        return response.body<ShiftFormDto>().toDomain()
    }

    override suspend fun saveForm(questions: List<ShiftQuestionInput>): ShiftForm {
        val response = client.put("${ApiConfig.BASE_URL}/shift-form") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveShiftFormRequest(
                    objectId = requireSelectedObjectId(),
                    questions = questions.map { it.toDto() }
                )
            )
        }
        response.requireShiftSuccess()
        return response.body<ShiftFormDto>().toDomain()
    }

    override suspend fun submitReport(answers: List<ShiftAnswerInput>): ShiftReport {
        val response = client.post("${ApiConfig.BASE_URL}/shift-reports") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SubmitShiftReportRequest(
                    objectId = requireSelectedObjectId(),
                    answers = answers.map { SubmitShiftAnswerRequest(it.questionId, it.value) }
                )
            )
        }
        response.requireShiftSuccess()
        return response.body<ShiftReportDto>().toDomain()
    }

    override suspend fun getReports(query: String): List<ShiftReport> {
        val response = client.get(
            "${ApiConfig.BASE_URL}/shift-reports?objectId=${requireSelectedObjectId()}&query=${query.encodeQuery()}"
        ) {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireShiftSuccess()
        return response.body<List<ShiftReportDto>>().map { it.toDomain() }
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    private suspend fun requireSelectedObjectId(): String =
        userSessionStore.getUserOrNull()?.selectedObjectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Выберите объект")

    private fun String.encodeQuery(): String =
        encodeToByteArray().joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            when (value.toChar()) {
                in 'a'..'z', in 'A'..'Z', in '0'..'9', '-', '_', '.', '~' -> value.toChar().toString()
                else -> "%" + value.toString(16).uppercase().padStart(2, '0')
            }
        }
}

private suspend fun HttpResponse.requireShiftSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ShiftErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun ShiftQuestionInput.toDto(): SaveShiftQuestionRequest =
    SaveShiftQuestionRequest(
        prompt = prompt,
        type = type,
        options = options.takeIf { type == SHIFT_QUESTION_CHOICE }.orEmpty()
    )

private fun ShiftFormDto.toDomain(): ShiftForm =
    ShiftForm(
        objectId = objectId,
        questions = questions.map { it.toDomain() }
    )

private fun ShiftQuestionDto.toDomain(): ShiftQuestion =
    ShiftQuestion(
        id = id,
        objectId = objectId,
        prompt = prompt,
        type = type,
        options = options,
        position = position,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

private fun ShiftReportDto.toDomain(): ShiftReport =
    ShiftReport(
        id = id,
        objectId = objectId,
        senderUid = senderUid,
        senderName = senderName,
        senderEmail = senderEmail,
        senderStatus = senderStatus,
        createdAtMillis = createdAtMillis,
        answers = answers.map { it.toDomain() }
    )

private fun ShiftReportAnswerDto.toDomain(): ShiftReportAnswer =
    ShiftReportAnswer(
        id = id,
        reportId = reportId,
        questionId = questionId,
        questionPrompt = questionPrompt,
        questionType = questionType,
        value = value,
        position = position
    )

@Serializable
private data class SaveShiftFormRequest(
    val objectId: String,
    val questions: List<SaveShiftQuestionRequest>
)

@Serializable
private data class SaveShiftQuestionRequest(
    val prompt: String,
    val type: String,
    val options: List<String> = emptyList()
)

@Serializable
private data class SubmitShiftReportRequest(
    val objectId: String,
    val answers: List<SubmitShiftAnswerRequest>
)

@Serializable
private data class SubmitShiftAnswerRequest(
    val questionId: String,
    val value: String
)

@Serializable
private data class ShiftFormDto(
    val objectId: String,
    val questions: List<ShiftQuestionDto>
)

@Serializable
private data class ShiftQuestionDto(
    val id: String,
    val objectId: String,
    val prompt: String,
    val type: String,
    val options: List<String> = emptyList(),
    val position: Int,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
private data class ShiftReportDto(
    val id: String,
    val objectId: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val senderStatus: String,
    val createdAtMillis: Long,
    val answers: List<ShiftReportAnswerDto> = emptyList()
)

@Serializable
private data class ShiftReportAnswerDto(
    val id: String,
    val reportId: String,
    val questionId: String,
    val questionPrompt: String,
    val questionType: String,
    val value: String,
    val position: Int
)

@Serializable
private data class ShiftErrorResponse(
    val message: String? = null
)
