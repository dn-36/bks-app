package com.bkc.screens.splash.viewmodel

import cafe.adriel.voyager.core.model.screenModelScope
import com.bkc.core.presentation.mvi.MviScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

data class SplashState(
    val isLoading: Boolean = true,
    val error: String? = null
)




