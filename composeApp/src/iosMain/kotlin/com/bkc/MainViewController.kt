package com.bkc

import androidx.compose.ui.window.ComposeUIViewController
import com.bkc.core.app.initKoin
import com.bkc.core.presentation.AppRoot

import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { AppRoot() }
}