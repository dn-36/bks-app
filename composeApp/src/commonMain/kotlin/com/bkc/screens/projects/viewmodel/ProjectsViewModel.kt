package com.bkc.screens.projects.viewmodel

import AddProjectUseCase
import DeleteProjectUseCase
import ObserveProjectsUseCase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val observeProjects: ObserveProjectsUseCase,
    private val addProject: AddProjectUseCase,
    private val deleteProject: DeleteProjectUseCase
) : ScreenModel {

    var state by mutableStateOf(ProjectsState())
        private set

    private val _effects = Channel<ProjectsEffect>()
    val effects = _effects.receiveAsFlow()

    init {
        observe()
    }

    private fun observe() {
        screenModelScope.launch {
            observeProjects().collect {
                state = state.copy(
                    projects = it
                )
            }
        }
    }

    fun process(intent: ProjectsIntent) {
        when (intent) {

            is ProjectsIntent.AddProject -> {

                screenModelScope.launch {
                    addProject(intent.title, intent.fileName, intent.file)
                }
            }

            is ProjectsIntent.DeleteProject -> {
                screenModelScope.launch {
                    deleteProject(intent.project)
                }
            }

            is ProjectsIntent.OpenProject -> {
                screenModelScope.launch {
                    _effects.send(
                        ProjectsEffect.NavigateToPdf(
                            intent.project.fileUrl,
                            intent.project.title
                        )
                    )
                }
            }

            else -> Unit
        }
    }
}
