package com.bkc.screens.project_1.domain.repo

import com.bkc.screens.project_1.model.Project
import kotlinx.coroutines.flow.Flow

data class PickedPdf(
    val fileName: String,
    val bytes: ByteArray
)

interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>> // realtime
    suspend fun addProject(pdf: PickedPdf, name: String? = null)
    suspend fun deleteProject(project: Project)
}