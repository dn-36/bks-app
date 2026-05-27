package com.bkc.core.presentation.requests

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MaterialRequestsRealtimeStore {
    private val _changedObjectIds = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changedObjectIds = _changedObjectIds.asSharedFlow()

    fun notifyChanged(objectId: String) {
        if (objectId.isBlank()) return
        _changedObjectIds.tryEmit(objectId)
    }
}
