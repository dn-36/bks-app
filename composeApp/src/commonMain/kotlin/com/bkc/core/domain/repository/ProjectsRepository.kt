package com.bkc.core.domain.repository

import com.bkc.core.domain.Project
import kotlinx.coroutines.flow.Flow

interface ProjectsRepository {

    fun observeProjects(): Flow<List<Project>>

    suspend fun addProject(
        title: String,
        fileName: String,
        file: ByteArray
    )

    suspend fun deleteProject(project: Project)
}
