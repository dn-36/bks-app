package com.bkc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.res.painterResource
import com.bkc.core.app.initKoin
import com.bkc.core.presentation.AppRoot
import com.bkc.core.presentation.notifications.AppNotifications

fun main() {
    initKoin()
    application {
        var visible by remember { mutableStateOf(true) }

        AppNotifications.init(
            onOpenApp = { visible = true },
            onExitApp = ::exitApplication
        )

        Window(
            onCloseRequest = { visible = false },
            title = "BKS APP",
            icon = painterResource("app-icon.png"),
            visible = visible
        ) {
            AppRoot().Component()
        }
    }
}
