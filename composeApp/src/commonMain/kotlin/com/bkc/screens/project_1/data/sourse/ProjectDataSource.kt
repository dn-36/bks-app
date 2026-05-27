package com.bkc.screens.project_1.data.sourse

import com.bkc.screens.project_1.domain.repo.PickedPdf
import com.bkc.screens.project_1.model.Project
import kotlinx.coroutines.flow.Flow

interface ProjectDataSource {
    fun observeProjects(): Flow<List<Project>>
    suspend fun uploadPdfAndCreateProject(pdf: PickedPdf, name: String?): Unit
    suspend fun deleteProject(project: Project): Unit
}