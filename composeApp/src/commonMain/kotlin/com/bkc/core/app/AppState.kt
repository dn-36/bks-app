package com.bkc.core.app

import com.bkc.core.domain.WorkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppState(
    val organizationName: String = "ООО \"БКС\"",
    val selectedObjectId: String? = null,
    val selectedObjectName: String = "Выберите объект",
    val selectedObjectPhotoUrl: String? = null
)

class AppStateStore {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun selectObject(obj: WorkObject) {
        _state.value = _state.value.copy(
            selectedObjectId = obj.id,
            selectedObjectName = obj.name,
            selectedObjectPhotoUrl = obj.photoUrl
        )
    }

    fun selectObject(id: String?, name: String?, photoUrl: String?) {
        _state.value = _state.value.copy(
            selectedObjectId = id,
            selectedObjectName = name ?: "Выберите объект",
            selectedObjectPhotoUrl = photoUrl
        )
    }
}
