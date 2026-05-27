package com.bkc.core.presentation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BottomBarVisibilityStore {
    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun hide() {
        _hidden.value = true
    }

    fun show() {
        _hidden.value = false
    }
}
