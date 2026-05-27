package com.bkc.core.presentation.reports

import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ShiftReportNotificationsStore {
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun setUnreadCount(count: Int) {
        _unreadCount.value = count.coerceAtLeast(0)
    }

    fun addUnreadReport() {
        _unreadCount.update { it + 1 }
    }

    fun clear() {
        _unreadCount.value = 0
    }
}

suspend fun refreshShiftReportUnreadCount(
    repository: ShiftTaskRepository,
    sessionStore: UserSessionStore,
    settings: Settings
) {
    val user = sessionStore.getUserOrNull()
    val objectId = user?.selectedObjectId.orEmpty()
    if (user?.status != "ADMINISTRATOR" || objectId.isBlank()) {
        ShiftReportNotificationsStore.clear()
        return
    }

    val seenAt = settings.getLong(shiftReportSeenKey(objectId), 0L)
    val reports = repository.getReports("")
    ShiftReportNotificationsStore.setUnreadCount(reports.count { it.createdAtMillis > seenAt })
}

suspend fun markShiftReportsSeen(
    reports: List<com.bkc.core.domain.ShiftReport>,
    sessionStore: UserSessionStore,
    settings: Settings
) {
    val objectId = sessionStore.getUserOrNull()?.selectedObjectId.orEmpty()
    if (objectId.isBlank()) return
    val latestReportAt = reports.maxOfOrNull { it.createdAtMillis } ?: return
    settings.putLong(shiftReportSeenKey(objectId), latestReportAt)
    ShiftReportNotificationsStore.clear()
}

private fun shiftReportSeenKey(objectId: String): String =
    "shiftReports.lastSeen.$objectId"
