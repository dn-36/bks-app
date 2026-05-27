package com.bkc.screens.projects.viewmodel

import com.bkc.core.domain.Project
import com.bkc.screens.projects.ui.ProjectUi


data class ProjectsState(
    val isLoading: Boolean = false,
    val projects: List<Project> = emptyList(),
    val errorMessage: String? = null
)