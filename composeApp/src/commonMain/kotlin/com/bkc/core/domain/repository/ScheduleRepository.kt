package com.bkc.core.domain.repository

import com.bkc.core.domain.ScheduleTask
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeTasks(): Flow<List<ScheduleTask>>
    suspend fun addTask(place: String, workType: String, color: String)
    suspend fun updateTask(taskId: String, place: String, workType: String, color: String)
    suspend fun deleteTask(taskId: String)
    suspend fun saveProgress(task: ScheduleTask, workDates: List<String>, isDone: Boolean)
}
