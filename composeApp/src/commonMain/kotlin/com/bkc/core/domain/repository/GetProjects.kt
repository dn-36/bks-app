package com.bkc.core.domain.repository

import com.bkc.core.domain.Project
import kotlinx.coroutines.flow.first


//class GetProjects(private val repo: ProjectRepository) { suspend operator fun invoke(): List<Project> = repo.getProjects() }
class GetProjects(private val repo: ProjectsRepository) {
    suspend operator fun invoke(): List<Project> = repo.observeProjects().first()
}
