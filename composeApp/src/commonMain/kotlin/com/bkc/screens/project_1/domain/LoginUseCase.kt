package com.bkc.screens.project_1.domain

import com.bkc.screens.project_1.domain.repo.PickedPdf
import com.bkc.screens.project_1.model.Project
import com.bkc.screens.project_1.viewmodel.AuthRepository
import com.bkc.screens.project_1.viewmodel.ProjectRepository
import kotlinx.coroutines.flow.Flow

class LoginUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String, password: String) = repo.login(email, password)
}

class LogoutUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke() = repo.logout()
}

class GetProjectsUseCase(private val repo: ProjectRepository) {
    operator fun invoke(): Flow<List<Project>> = repo.observeProjects()
}

class AddProjectUseCase(private val repo: ProjectRepository) {
    suspend operator fun invoke(pdf: PickedPdf, name: String? = null) = repo.addProject(pdf, name)
}

class DeleteProjectUseCase(private val repo: ProjectRepository) {
    suspend operator fun invoke(project: Project) = repo.deleteProject(project)
}