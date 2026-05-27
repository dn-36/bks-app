package com.bkc.core.app

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin

actual object SettingsProvider {
    actual fun factory(): Settings.Factory {
        return PreferencesSettings.Factory()
    }
}

fun initKoin() {
    startKoin {
        modules(koinModules)
    }
}
