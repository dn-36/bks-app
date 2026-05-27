package com.bkc.screens.project_1.viewmodel

import com.bkc.screens.project_1.domain.repo.PickedPdf
import com.bkc.screens.project_1.model.Project
import kotlinx.coroutines.flow.Flow



interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>> // realtime
    suspend fun addProject(pdf: PickedPdf, name: String? = null)
    suspend fun deleteProject(project: Project)
}