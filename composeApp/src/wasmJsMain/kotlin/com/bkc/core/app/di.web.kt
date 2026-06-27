package com.bkc.core.app

import com.russhwolf.settings.Settings
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
            cookieEntries().keys.forEach { key ->
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
        removeCookie(storageKey(key))
    }

    override fun hasKey(key: String): Boolean = getStoredString(key) != null

    override fun putInt(key: String, value: Int) {
        putStoredString(key, value.toString())
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? =
        storage.getItem(storageKey(key))?.toIntOrNull()

    override fun putLong(key: String, value: Long) {
        putStoredString(key, value.toString())
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? =
        storage.getItem(storageKey(key))?.toLongOrNull()

    override fun putString(key: String, value: String) {
        putStoredString(key, value)
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        getStoredString(key)

    override fun putFloat(key: String, value: Float) {
        putStoredString(key, value.toString())
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? =
        storage.getItem(storageKey(key))?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) {
        putStoredString(key, value.toString())
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? =
        storage.getItem(storageKey(key))?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        putStoredString(key, value.toString())
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        storage.getItem(storageKey(key))?.toBooleanStrictOrNull()

    private fun putStoredString(key: String, value: String) {
        val fullKey = storageKey(key)
        storage.setItem(fullKey, value)
        putCookie(fullKey, value)
    }

    private fun getStoredString(key: String): String? {
        val fullKey = storageKey(key)
        storage.getItem(fullKey)?.let { return it }
        return getCookie(fullKey)?.also { storage.setItem(fullKey, it) }
    }

    private fun storageKey(key: String): String = "$prefix$key"

    private fun putCookie(key: String, value: String) {
        document.cookie = "$key=${value.toCookieValue()}; Max-Age=15552000; Path=/; SameSite=Lax"
    }

    private fun getCookie(key: String): String? =
        cookieEntries()[key]?.fromCookieValueOrNull()

    private fun removeCookie(key: String) {
        document.cookie = "$key=; Max-Age=0; Path=/; SameSite=Lax"
    }

    private fun cookieEntries(): Map<String, String> {
        val raw = document.cookie
        if (raw.isBlank()) return emptyMap()
        return raw.split(";")
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                val separator = trimmed.indexOf("=")
                if (separator <= 0) return@mapNotNull null
                trimmed.substring(0, separator) to trimmed.substring(separator + 1)
            }
            .toMap()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.toCookieValue(): String =
        Base64.UrlSafe.encode(encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.fromCookieValueOrNull(): String? =
        runCatching { Base64.UrlSafe.decode(this).decodeToString() }.getOrNull()
}
