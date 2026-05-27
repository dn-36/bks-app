package com.bkc.screens.projects.viewmodel

import com.bkc.core.domain.Project
import com.bkc.screens.projects.ui.ProjectUi


sealed class ProjectsIntent {
    object LoadProjects : ProjectsIntent()
    data class AddProject(val title: String, val fileName: String, val file: ByteArray) : ProjectsIntent()
    data class DeleteProject(val project: Project) : ProjectsIntent()
    data class OpenProject(val project: Project) : ProjectsIntent()
}
