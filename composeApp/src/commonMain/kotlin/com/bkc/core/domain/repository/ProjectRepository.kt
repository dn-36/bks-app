package com.bkc.core.domain.repository

import com.bkc.core.domain.Project


interface ProjectRepository { suspend fun getProjects(): List<Project> }
