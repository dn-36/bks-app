package com.bkc.core.domain

data class ScheduleTask(
    val id: String,
    val objectId: String,
    val place: String,
    val workType: String,
    val color: String,
    val createdBy: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progress: ScheduleProgress? = null,
    val progresses: List<ScheduleProgress> = emptyList()
) {
    val allProgresses: List<ScheduleProgress>
        get() = if (progresses.isNotEmpty()) progresses else listOfNotNull(progress)

    fun progressFor(userId: String?): ScheduleProgress? {
        if (userId.isNullOrBlank()) return progress
        return allProgresses.firstOrNull { it.userId == userId }
    }
}

data class ScheduleProgress(
    val taskId: String,
    val userId: String,
    val foremanName: String,
    val workDates: List<String>,
    val isDone: Boolean,
    val updatedAtMillis: Long
)
