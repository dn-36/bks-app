package com.bkc.screens.splash.viewmodel

import cafe.adriel.voyager.core.model.screenModelScope
import com.bkc.core.presentation.mvi.MviScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

class SplashScreenModel : MviScreenModel<SplashState, SplashIntent, SplashEffect>(SplashState()),
    KoinComponent {



    init {
        onIntent(SplashIntent.Start)
    }

    override fun onIntent(intent: SplashIntent) {
        when (intent) {
            SplashIntent.Start, SplashIntent.Retry -> start()
        }
    }

    private fun start() {
        setState { it.copy(isLoading = true, error = null) }


        screenModelScope.launch {
            val minDelay = async { delay(2000) } // строго 2 секунды


            runCatching {
                minDelay.await()

            }.onSuccess {
                setState { it.copy(isLoading = false, error = null) }
                sendEffect(SplashEffect.NavigateNext)
            }.onFailure { e ->
                setState { it.copy(isLoading = false, error = e.message ?: "Ошибка загрузки") }
            }
        }
    }
}