package com.bkc.screens.projects_user

import cafe.adriel.voyager.core.model.screenModelScope
import com.bkc.core.domain.Project
import com.bkc.core.domain.repository.ProjectsRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.mvi.MviScreenModel
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.utils.containsIgnoreCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ProjectsUserState(
    val searchQuery: String = "",
    val listState: UiListState<Project> = UiListState.Loading,
    val canManageProjects: Boolean = false,
    val editor: ProjectEditorState? = null,
    val error: String? = null
)

data class ProjectEditorState(
    val title: String = "",
    val fileName: String? = null,
    val fileBytes: ByteArray? = null
)

sealed interface ProjectsUserIntent {
    data class SearchChanged(val value: String) : ProjectsUserIntent
    data object ClearSearch : ProjectsUserIntent
    data class ClickProject(val project: Project) : ProjectsUserIntent
    data class DeleteProject(val project: Project) : ProjectsUserIntent
    data object AddProject : ProjectsUserIntent
    data class ProjectTitleChanged(val value: String) : ProjectsUserIntent
    data class ProjectFilePicked(val fileName: String, val bytes: ByteArray) : ProjectsUserIntent
    data object SaveProject : ProjectsUserIntent
    data object DismissEditor : ProjectsUserIntent
}

sealed interface ProjectsUserEffect {
    data class OpenProject(val project: Project) : ProjectsUserEffect
}

class ProjectsUserScreenModel :
    MviScreenModel<ProjectsUserState, ProjectsUserIntent, ProjectsUserEffect>(ProjectsUserState()),
    KoinComponent {

    private val projectsRepository: ProjectsRepository by inject()
    private val userSessionStore: UserSessionStore by inject()
    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private var all: List<Project> = emptyList()

    init {
        screenModelScope.launch { loadPermissions() }
        screenModelScope.launch {
            setState { it.copy(listState = UiListState.Loading) }
            projectsRepository.observeProjects().collect { projects ->
                all = projects
                applyFilter(state.value.searchQuery)
            }
        }
        screenModelScope.launch {
            queryFlow.debounce(300).collect { q -> applyFilter(q) }
        }
    }

    override fun onIntent(intent: ProjectsUserIntent) {
        when (intent) {
            is ProjectsUserIntent.SearchChanged -> {
                setState { it.copy(searchQuery = intent.value) }
                queryFlow.tryEmit(intent.value)
            }
            ProjectsUserIntent.ClearSearch -> {
                setState { it.copy(searchQuery = "") }
                queryFlow.tryEmit("")
            }
            is ProjectsUserIntent.ClickProject -> sendEffect(ProjectsUserEffect.OpenProject(intent.project))
            is ProjectsUserIntent.DeleteProject -> {
                if (state.value.canManageProjects) deleteProject(intent.project)
            }
            ProjectsUserIntent.AddProject -> {
                if (state.value.canManageProjects) {
                    setState { it.copy(editor = ProjectEditorState(), error = null) }
                }
            }
            is ProjectsUserIntent.ProjectTitleChanged -> {
                setState { current ->
                    current.copy(editor = current.editor?.copy(title = intent.value), error = null)
                }
            }
            is ProjectsUserIntent.ProjectFilePicked -> {
                setState { current ->
                    val nextTitle = current.editor?.title?.takeIf { it.isNotBlank() }
                        ?: intent.fileName.substringBeforeLast('.', intent.fileName)
                    current.copy(
                        editor = current.editor?.copy(
                            title = nextTitle,
                            fileName = intent.fileName,
                            fileBytes = intent.bytes
                        ),
                        error = null
                    )
                }
            }
            ProjectsUserIntent.SaveProject -> saveProject()
            ProjectsUserIntent.DismissEditor -> setState { it.copy(editor = null, error = null) }
        }
    }

    private fun loadPermissions() {
        screenModelScope.launch {
            val user = userSessionStore.getUserOrNull()
            setState { it.copy(canManageProjects = user?.status == "ADMINISTRATOR") }
        }
    }

    private fun saveProject() {
        val editor = state.value.editor ?: return
        val title = editor.title.trim()
        val fileName = editor.fileName
        val fileBytes = editor.fileBytes
        if (title.isBlank() || fileName.isNullOrBlank() || fileBytes == null) {
            setState { it.copy(error = "Заполните название и выберите файл проекта") }
            return
        }

        screenModelScope.launch {
            runCatching {
                projectsRepository.addProject(title, fileName, fileBytes)
            }.onSuccess {
                setState { it.copy(editor = null, error = null) }
            }.onFailure { e ->
                setState { it.copy(error = e.message ?: "Ошибка сохранения проекта") }
            }
        }
    }

    private fun deleteProject(project: Project) {
        screenModelScope.launch {
            runCatching { projectsRepository.deleteProject(project) }
                .onFailure { e -> setState { it.copy(error = e.message ?: "Ошибка удаления проекта") } }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) all else all.filter {
            containsIgnoreCase(it.title, q) || containsIgnoreCase(it.fileName, q)
        }

        setState {
            it.copy(
                listState = when {
                    all.isEmpty() -> UiListState.Empty("Список пуст")
                    filtered.isEmpty() -> UiListState.Empty("Ничего не найдено")
                    else -> UiListState.Content(filtered)
                }
            )
        }
    }
}
