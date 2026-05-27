package com.bkc.screens.project_1.data.repo

import com.bkc.screens.project_1.data.sourse.ProjectDataSource
import com.bkc.screens.project_1.domain.repo.PickedPdf
import com.bkc.screens.project_1.domain.repo.ProjectRepository
import com.bkc.screens.project_1.model.Project
import kotlinx.coroutines.flow.Flow

class ProjectRepositoryImpl(
    private val ds: ProjectDataSource
) : ProjectRepository {
    override fun observeProjects(): Flow<List<Project>> = ds.observeProjects()
    override suspend fun addProject(pdf: PickedPdf, name: String?) = ds.uploadPdfAndCreateProject(pdf, name)
    override suspend fun deleteProject(project: Project) = ds.deleteProject(project)
}