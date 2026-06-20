package com.bkc.core.app

import com.russhwolf.settings.Settings
import kotlinx.browser.window
import org.koin.core.context.startKoin

actual object SettingsProvider {
    actual fun factory(): Settings.Factory =
        object : Settings.Factory {
            override fun create(name: String?): Settings = LocalStorageSettings(name ?: "settings")
        }
}

fun initKoin() {
    startKoin {
        modules(koinModules)
    }
}

private class LocalStorageSettings(
    private val name: String
) : Settings {
    private val storage = window.localStorage
    private val prefix = "$name."

    override val keys: Set<String>
        get() = buildSet {
            for (index in 0 until storage.length) {
                val key = storage.key(index) ?: continue
                if (key.startsWith(prefix)) {
                    add(key.removePrefix(prefix))
                }
            }
        }

    override val size: Int
        get() = keys.size

    override fun clear() {
        keys.forEach(::remove)
    }

    override fun remove(key: String) {
        storage.removeItem(storageKey(key))
    }

    override fun hasKey(key: String): Boolean = storage.getItem(storageKey(key)) != null

    override fun putInt(key: String, value: Int) {
        storage.setItem(storageKey(key), value.toString())
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? =
        storage.getItem(storageKey(key))?.toIntOrNull()

    override fun putLong(key: String, value: Long) {
        storage.setItem(storageKey(key), value.toString())
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? =
        storage.getItem(storageKey(key))?.toLongOrNull()

    override fun putString(key: String, value: String) {
        storage.setItem(storageKey(key), value)
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        storage.getItem(storageKey(key))

    override fun putFloat(key: String, value: Float) {
        storage.setItem(storageKey(key), value.toString())
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? =
        storage.getItem(storageKey(key))?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) {
        storage.setItem(storageKey(key), value.toString())
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? =
        storage.getItem(storageKey(key))?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        storage.setItem(storageKey(key), value.toString())
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        storage.getItem(storageKey(key))?.toBooleanStrictOrNull()

    private fun storageKey(key: String): String = "$prefix$key"
}
