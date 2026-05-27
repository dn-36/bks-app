package com.bkc.screens.splash.viewmodel

sealed interface SplashEffect {
    data object NavigateNext : SplashEffect
}