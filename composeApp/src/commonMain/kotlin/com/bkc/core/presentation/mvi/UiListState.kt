package com.bkc.core.presentation.mvi

sealed interface UiListState<out T> {
    data object Loading : UiListState<Nothing>
    data class Content<T>(val items: List<T>) : UiListState<T>
    data class Empty(val message: String) : UiListState<Nothing>
    data class Error(val message: String) : UiListState<Nothing>
}