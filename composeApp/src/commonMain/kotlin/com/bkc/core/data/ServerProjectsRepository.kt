package com.bkc.core.data

import com.bkc.core.domain.Project
import com.bkc.core.domain.repository.ProjectsRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.network.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ServerProjectsRepository(
    private val userSessionStore: UserSessionStore
) : ProjectsRepository {

    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val projects = MutableStateFlow<List<Project>>(emptyList())

    override fun observeProjects(): Flow<List<Project>> {
        scope.launch { refreshProjects() }
        return projects
    }

    override suspend fun addProject(title: String, fileName: String, file: ByteArray) {
        val response = client.post("${ApiConfig.BASE_URL}/projects") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
            contentType(ContentType.Application.Json)
            setBody(
                CreateProjectRequest(
                    objectId = requireSelectedObjectId(),
                    title = title,
                    fileName = fileName,
                    fileBase64 = encodeFile(file)
                )
            )
        }

        response.requireSuccess()
        runCatching { response.body<ProjectDto>().toDomain() }
            .onSuccess { created ->
                projects.value = (projects.value.filterNot { it.id == created.id } + created)
            }
        runCatching { refreshProjects() }
    }

    override suspend fun deleteProject(project: Project) {
        val response = client.delete("${ApiConfig.BASE_URL}/projects/${project.id}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }

        response.requireSuccess()
        refreshProjects()
    }

    private suspend fun refreshProjects() {
        val response = client.get("${ApiConfig.BASE_URL}/projects?objectId=${requireSelectedObjectId()}") {
            header(HttpHeaders.Authorization, "Bearer ${requireToken()}")
        }

        response.requireSuccess()
        projects.value = response.body<List<ProjectDto>>().map { it.toDomain() }
    }

    private suspend fun requireToken(): String =
        userSessionStore.getUserOrNull()?.authToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Требуется авторизация")

    private suspend fun requireSelectedObjectId(): String =
        userSessionStore.getUserOrNull()?.selectedObjectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Выберите объект")

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeFile(bytes: ByteArray): String = Base64.encode(bytes)
}

private suspend fun HttpResponse.requireSuccess() {
    if (status.value in 200..299) return

    val message = runCatching {
        body<ErrorResponse>().message
    }.getOrNull()

    throw IllegalStateException(message ?: "Ошибка сервера (${status.value})")
}

private fun ProjectDto.toDomain(): Project =
    Project(
        id = id,
        title = title,
        objectId = objectId,
        fileName = fileName,
        fileUrl = fileUrl.toAbsoluteApiUrl(),
        storagePath = storagePath,
        createdBy = createdBy,
        createdAtMillis = createdAtMillis
    )

private fun String.toAbsoluteApiUrl(): String {
    if (startsWith("http://") || startsWith("https://")) return this
    return "${ApiConfig.BASE_URL.trimEnd('/')}/${trimStart('/')}"
}

@Serializable
private data class CreateProjectRequest(
    val objectId: String,
    val title: String,
    val fileName: String,
    val fileBase64: String
)

@Serializable
private data class ProjectDto(
    val id: String,
    val title: String,
    val objectId: String = "",
    val fileName: String = "",
    val fileUrl: String,
    val storagePath: String,
    val createdBy: String,
    val createdAtMillis: Long
)

@Serializable
private data class ErrorResponse(
    val message: String? = null
)
