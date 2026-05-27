package com.bkc.screens.projects.viewmodel

sealed class ProjectsEffect {
    data class NavigateToPdf(
        val url: String,
        val title  : String
    ) : ProjectsEffect()
    data class ShowError(val message: String) : ProjectsEffect()
}