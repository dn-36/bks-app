package com.bkc.core.presentation.registration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RegistrationRequestsStore {
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    fun setPendingCount(count: Int) {
        _pendingCount.value = count.coerceAtLeast(0)
    }

    fun addPendingRequest() {
        _pendingCount.update { it + 1 }
    }

    fun clear() {
        _pendingCount.value = 0
    }
}
