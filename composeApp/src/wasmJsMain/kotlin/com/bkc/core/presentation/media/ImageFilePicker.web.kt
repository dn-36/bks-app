package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.browser.document
import org.jetbrains.skia.Image
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.events.Event
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop

private val imageClient = HttpClient()

@Composable
actual fun ImageFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    NativeWebFilePicker(
        accept = "image/*",
        onDismiss = onDismiss,
        onPicked = onPicked
    )
}

actual suspend fun loadImageBitmap(url: String): ImageBitmap? =
    runCatching {
        val response = imageClient.get(url)
        if (!response.status.isSuccess()) return@runCatching null

        Image.makeFromEncoded(response.bodyAsBytes()).toComposeImageBitmap()
    }.getOrNull()

actual suspend fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

@Composable
internal fun NativeWebFilePicker(
    accept: String,
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    DisposableEffect(accept) {
        val wrapper = document.createElement("div") as HTMLDivElement
        val button = document.createElement("button") as HTMLButtonElement

        wrapper.style.position = "fixed"
        wrapper.style.left = "50%"
        wrapper.style.bottom = "24px"
        wrapper.style.transform = "translateX(-50%)"
        wrapper.style.zIndex = "2147483647"
        wrapper.style.padding = "10px"
        wrapper.style.background = "white"
        wrapper.style.border = "1px solid #d1d5db"
        wrapper.style.borderRadius = "8px"
        wrapper.style.boxShadow = "0 12px 32px rgba(0, 0, 0, 0.22)"

        button.type = "button"
        button.textContent = "Выбрать файл"
        button.style.padding = "10px 14px"
        button.style.border = "0"
        button.style.borderRadius = "6px"
        button.style.background = "#2563eb"
        button.style.color = "white"
        button.style.font = "600 16px sans-serif"
        button.style.cursor = "pointer"
        button.style.maxWidth = "calc(100vw - 48px)"

        fun cleanup() {
            button.onclick = null
            wrapper.remove()
        }

        button.onclick = {
            openWebFilePicker(
                accept = accept,
                onDismiss = {
                    cleanup()
                    onDismiss()
                },
                onPicked = { fileName, bytes ->
                    cleanup()
                    onPicked(fileName, bytes)
                }
            )
            Unit
        }

        wrapper.appendChild(button)
        document.body?.appendChild(wrapper)

        onDispose {
            cleanup()
        }
    }
}

internal fun openWebFilePicker(
    accept: String,
    onDismiss: () -> Unit = {},
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val input = document.createElement("input") as HTMLInputElement
    var completed = false

    input.type = "file"
    input.accept = accept
    input.style.position = "fixed"
    input.style.left = "-10000px"
    input.style.top = "-10000px"
    input.style.width = "1px"
    input.style.height = "1px"
    input.style.opacity = "0"
    input.setAttribute("aria-hidden", "true")

    fun cleanup() {
        input.onchange = null
        input.remove()
    }

    input.onchange = {
        val file = input.files?.item(0)
        if (file != null) {
            readFileAsDataUrl(
                file = file,
                onLoaded = { fileName, dataUrl ->
                    completed = true
                    dataUrl.decodeBase64Payload()?.let { bytes ->
                        onPicked(fileName, bytes)
                    } ?: onDismiss()
                    cleanup()
                },
                onError = {
                    completed = true
                    cleanup()
                    onDismiss()
                }
            )
        } else {
            completed = true
            cleanup()
            onDismiss()
        }
        Unit
    }

    input.addEventListener("cancel", { _: Event ->
        if (!completed) {
            completed = true
            cleanup()
            onDismiss()
        }
    })

    document.body?.appendChild(input)
    input.click()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun String.decodeBase64Payload(): ByteArray? {
    val payload = substringAfter(',', missingDelimiterValue = "")
    if (payload.isBlank()) return null
    return runCatching { Base64.decode(payload) }.getOrNull()
}

@OptIn(ExperimentalWasmJsInterop::class)
internal fun readFileAsDataUrl(
    file: File,
    onLoaded: (fileName: String, dataUrl: String) -> Unit,
    onError: () -> Unit
) {
    val reader = FileReader()
    reader.onload = {
        val result = reader.result?.toString().orEmpty()
        if (result.isNotBlank()) {
            onLoaded(file.name.ifBlank { "project-file" }, result)
        } else {
            onError()
        }
        Unit
    }
    reader.onerror = {
        onError()
        Unit
    }
    reader.onabort = {
        onError()
        Unit
    }
    reader.readAsDataURL(file)
}
