package com.bkc.core.data

import com.bkc.core.domain.ScheduleProgress
import com.bkc.core.domain.ScheduleTask
import com.bkc.core.domain.repository.ScheduleRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ServerScheduleRepository(
    private val userSessionStore: UserSessionStore
) : ScheduleRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val tasks = MutableStateFlow<List<ScheduleTask>>(emptyList())

    override fun observeTasks(): Flow<List<ScheduleTask>> {
        scope.launch { refreshTasks() }
        return tasks
    }

    override suspend fun addTask(place: String, workType: String, color: String) {
        val response = client.post("${ApiConfig.BASE_URL}/schedules") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveScheduleTaskRequest(
                    objectId = requireSelectedObjectId(),
                    place = place,
                    workType = workType,
                    color = color
                )
            )
        }
        response.requireScheduleSuccess()
        refreshTasks()
    }

    override suspend fun updateTask(taskId: String, place: String, workType: String, color: String) {
        val response = client.put("${ApiConfig.BASE_URL}/schedules/$taskId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveScheduleTaskRequest(
                    objectId = requireSelectedObjectId(),
                    place = place,
                    workType = workType,
                    color = color
                )
            )
        }
        response.requireScheduleSuccess()
        refreshTasks()
    }

    override suspend fun deleteTask(taskId: String) {
        val response = client.delete("${ApiConfig.BASE_URL}/schedules/$taskId") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireScheduleSuccess()
        refreshTasks()
    }

    override suspend fun saveProgress(task: ScheduleTask, workDates: List<String>, isDone: Boolean) {
        val user = userSessionStore.getUserOrNull()
            ?: throw IllegalStateException("Требуется авторизация")
        val response = client.put("${ApiConfig.BASE_URL}/schedules/${task.id}/progress") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                SaveScheduleProgressRequest(
                    foremanName = "${user.firstName} ${user.lastName}".trim(),
                    workDates = workDates,
                    isDone = isDone
                )
            )
        }
        response.requireScheduleSuccess()
        refreshTasks()
    }

    private suspend fun refreshTasks() {
        val response = client.get("${ApiConfig.BASE_URL}/schedules?objectId=${requireSelectedObjectId()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }
        response.requireScheduleSuccess()
        tasks.value = response.body<List<ScheduleTaskDto>>().map { it.toDomain() }
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    private suspend fun requireSelectedObjectId(): String =
        userSessionStore.getUserOrNull()?.selectedObjectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Выберите объект")
}

private suspend fun HttpResponse.requireScheduleSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ScheduleErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun ScheduleTaskDto.toDomain(): ScheduleTask =
    ScheduleTask(
        id = id,
        objectId = objectId,
        place = place,
        workType = workType,
        color = color,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        progress = progress?.toDomain(),
        progresses = progresses.map { it.toDomain() }.ifEmpty {
            progress?.toDomain()?.let { listOf(it) }.orEmpty()
        }
    )

private fun ScheduleProgressDto.toDomain(): ScheduleProgress =
    ScheduleProgress(
        taskId = taskId,
        userId = userId,
        foremanName = foremanName,
        workDates = workDates,
        isDone = isDone,
        updatedAtMillis = updatedAtMillis
    )

@Serializable
private data class SaveScheduleTaskRequest(
    val objectId: String,
    val place: String,
    val workType: String,
    val color: String
)

@Serializable
private data class SaveScheduleProgressRequest(
    val foremanName: String,
    val workDates: List<String>,
    val isDone: Boolean
)

@Serializable
private data class ScheduleTaskDto(
    val id: String,
    val objectId: String,
    val place: String,
    val workType: String,
    val color: String,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progress: ScheduleProgressDto? = null,
    val progresses: List<ScheduleProgressDto> = emptyList()
)

@Serializable
private data class ScheduleProgressDto(
    val taskId: String,
    val userId: String,
    val foremanName: String,
    val workDates: List<String>,
    val isDone: Boolean,
    val updatedAtMillis: Long
)

@Serializable
private data class ScheduleErrorResponse(
    val message: String? = null
)
