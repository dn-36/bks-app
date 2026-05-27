package com.bkc.screens.splash.viewmodel

sealed interface SplashIntent {
    data object Start : SplashIntent
    data object Retry : SplashIntent
}