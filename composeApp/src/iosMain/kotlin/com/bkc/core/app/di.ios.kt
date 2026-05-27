package com.bkc.core.app

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings

actual object SettingsProvider {
    actual fun factory(): Settings.Factory =
        NSUserDefaultsSettings.Factory()
}