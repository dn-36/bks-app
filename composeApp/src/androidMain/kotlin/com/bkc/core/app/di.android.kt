package com.bkc.core.app

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual object SettingsProvider {

    // Нужно один раз проинициализировать (например в Application)
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual fun factory(): Settings.Factory {
        check(::appContext.isInitialized) { "SettingsProvider.init(context) was not called" }
        return SharedPreferencesSettings.Factory(appContext)
    }
}