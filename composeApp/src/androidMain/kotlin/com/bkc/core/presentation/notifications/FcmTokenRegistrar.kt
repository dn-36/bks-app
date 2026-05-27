package com.bkc.core.presentation.notifications

import android.content.Context
import com.bkc.core.network.ApiConfig
import com.google.firebase.messaging.FirebaseMessaging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object FcmTokenRegistrar {
    private const val SETTINGS_NAME = "app_settings"
    private const val KEY_AUTH_TOKEN = "user.authToken"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
        }
    }

    fun registerCurrentToken(context: Context?) {
        val appContext = context?.applicationContext ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            registerToken(appContext, token)
        }
    }

    fun unregisterCurrentToken(context: Context?) {
        val appContext = context?.applicationContext ?: return
        val authToken = currentAuthToken(appContext)
        if (authToken.isBlank()) return

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            unregisterToken(authToken, token)
        }
    }

    fun registerToken(context: Context?, token: String) {
        val appContext = context?.applicationContext ?: return
        val normalizedToken = token.trim()
        if (normalizedToken.isBlank()) return

        scope.launch {
            val authToken = currentAuthToken(appContext)
            if (authToken.isBlank()) return@launch

            runCatching {
                client.post("${ApiConfig.BASE_URL}/notifications/fcm-token") {
                    bearerAuth(authToken)
                    contentType(ContentType.Application.Json)
                    setBody(SaveFcmTokenRequest(token = normalizedToken))
                }
            }
        }
    }

    private fun unregisterToken(authToken: String, token: String) {
        val normalizedToken = token.trim()
        if (authToken.isBlank() || normalizedToken.isBlank()) return

        scope.launch {
            runCatching {
                client.delete("${ApiConfig.BASE_URL}/notifications/fcm-token") {
                    bearerAuth(authToken)
                    contentType(ContentType.Application.Json)
                    setBody(SaveFcmTokenRequest(token = normalizedToken))
                }
            }
            runCatching {
                FirebaseMessaging.getInstance().deleteToken()
            }
        }
    }

    private fun currentAuthToken(context: Context): String =
        context
            .getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUTH_TOKEN, "")
            .orEmpty()
            .trim()
}

@Serializable
private data class SaveFcmTokenRequest(
    val token: String,
    val platform: String = "ANDROID"
)
