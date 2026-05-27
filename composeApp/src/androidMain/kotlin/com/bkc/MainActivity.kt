package com.bkc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bkc.core.app.SettingsProvider
import com.bkc.core.app.koinModules
import com.bkc.core.presentation.AppRoot
import com.bkc.core.presentation.notifications.AppNotifications
import com.bkc.core.presentation.share.MessageShare
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsProvider.init(this)
        AppNotifications.init(this)
        MessageShare.init(this)
        AppNotifications.requestPermissionIfNeeded()

        startKoin {
            androidLogger()
            androidContext(this@MainActivity)
            modules(koinModules)
        }
        AppNotifications.registerPushToken()
        AppNotifications.openChatFromIntent(intent)
        setContent { AppRoot().Component() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppNotifications.openChatFromIntent(intent)
    }
}
