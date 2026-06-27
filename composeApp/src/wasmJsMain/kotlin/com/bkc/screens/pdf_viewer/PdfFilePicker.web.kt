package com.bkc.screens.pdf_viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.bkc.core.presentation.media.openWebFilePicker
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement

@Composable
actual fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    DisposableEffect(Unit) {
        val wrapper = document.createElement("div") as HTMLDivElement
        val button = document.createElement("button") as HTMLButtonElement

        wrapper.style.position = "fixed"
        wrapper.style.left = "50%"
        wrapper.style.top = "50%"
        wrapper.style.transform = "translate(-50%, -50%)"
        wrapper.style.zIndex = "2147483647"
        wrapper.style.padding = "8px"
        wrapper.style.background = "white"

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

        button.onclick = {
            openWebFilePicker(
                accept = "application/pdf,.pdf",
                onPicked = onPicked
            )
            Unit
        }

        wrapper.appendChild(button)
        document.body?.appendChild(wrapper)

        onDispose {
            button.onclick = null
            wrapper.remove()
        }
    }
}
