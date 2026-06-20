package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.browser.document
import org.jetbrains.skia.Image
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop

private val imageClient = HttpClient()

@Composable
actual fun ImageFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        pickFile("image/*", onPicked)
    }
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

internal fun pickFile(
    accept: String,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit,
    onDismiss: () -> Unit = {}
) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = accept
    input.style.display = "none"

    input.onchange = {
        val file = input.files?.item(0)
        if (file != null) {
            readFileAsDataUrl(
                file = file,
                onLoaded = { fileName, dataUrl ->
                    dataUrl.decodeBase64Payload()?.let { bytes ->
                        onPicked(fileName, bytes)
                    } ?: onDismiss()
                    input.remove()
                },
                onError = {
                    input.remove()
                    onDismiss()
                }
            )
        } else {
            input.remove()
            onDismiss()
        }
        Unit
    }

    document.body?.appendChild(input)
    input.click()
}

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeBase64Payload(): ByteArray? {
    val payload = substringAfter(',', missingDelimiterValue = "")
    if (payload.isBlank()) return null
    return runCatching { Base64.decode(payload) }.getOrNull()
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun readFileAsDataUrl(
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
