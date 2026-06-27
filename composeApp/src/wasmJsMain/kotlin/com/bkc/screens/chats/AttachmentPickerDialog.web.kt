package com.bkc.screens.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.bkc.core.presentation.media.decodeBase64Payload
import com.bkc.core.presentation.media.readFileAsDataUrl
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLLabelElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.dom.events.Event
import org.w3c.files.File

@Composable
actual fun AttachmentPickerDialog(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    DisposableEffect(Unit) {
        val backdrop = document.createElement("div") as HTMLDivElement
        val panel = document.createElement("div") as HTMLDivElement
        val title = document.createElement("div") as HTMLDivElement
        val photo = fileInputButton("Фото", "image/*")
        val file = fileInputButton("Видео или файл", "*/*")
        val cancel = document.createElement("button")

        backdrop.style.position = "fixed"
        backdrop.style.left = "0"
        backdrop.style.top = "0"
        backdrop.style.right = "0"
        backdrop.style.bottom = "0"
        backdrop.style.zIndex = "2147483647"
        backdrop.style.background = "rgba(0, 0, 0, 0.32)"
        backdrop.style.display = "flex"
        backdrop.style.alignItems = "center"
        backdrop.style.justifyContent = "center"

        panel.style.width = "min(360px, calc(100vw - 32px))"
        panel.style.padding = "20px"
        panel.style.background = "white"
        panel.style.borderRadius = "8px"
        panel.style.boxShadow = "0 24px 64px rgba(0, 0, 0, 0.28)"
        panel.style.display = "flex"
        panel.style.flexDirection = "column"
        panel.style.setProperty("gap", "10px")
        panel.style.font = "16px sans-serif"
        panel.style.color = "#111827"

        title.textContent = "Добавить вложение"
        title.style.fontSize = "20px"
        title.style.fontWeight = "600"
        title.style.marginBottom = "6px"

        cancel.textContent = "Отмена"
        cancel.setAttribute("type", "button")
        cancel.setAttribute(
            "style",
            "margin-top:6px;padding:10px 14px;border:0;background:transparent;color:#1f2937;font:16px sans-serif;cursor:pointer;text-align:right;"
        )

        fun cleanup() {
            photo.input.onchange = null
            file.input.onchange = null
            backdrop.remove()
        }

        fun handlePicked(selected: File?) {
            if (selected == null) {
                cleanup()
                onDismiss()
                return
            }
            readFileAsDataUrl(
                file = selected,
                onLoaded = { fileName, dataUrl ->
                    dataUrl.decodeBase64Payload()?.let { bytes ->
                        onPicked(fileName, bytes)
                    } ?: onDismiss()
                    cleanup()
                },
                onError = {
                    cleanup()
                    onDismiss()
                }
            )
        }

        photo.input.onchange = {
            handlePicked(photo.input.files?.item(0))
            Unit
        }
        file.input.onchange = {
            handlePicked(file.input.files?.item(0))
            Unit
        }

        backdrop.addEventListener("click", {
            cleanup()
            onDismiss()
        })
        panel.addEventListener("click", { event: Event ->
            event.stopPropagation()
        })
        cancel.addEventListener("click", {
            cleanup()
            onDismiss()
        })

        panel.appendChild(title)
        panel.appendChild(photo.label)
        panel.appendChild(file.label)
        panel.appendChild(cancel)
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)

        onDispose {
            cleanup()
        }
    }
}

private data class FileInputButton(
    val label: HTMLLabelElement,
    val input: HTMLInputElement
)

private fun fileInputButton(text: String, accept: String): FileInputButton {
    val label = document.createElement("label") as HTMLLabelElement
    val input = document.createElement("input") as HTMLInputElement
    val caption = document.createElement("span") as HTMLSpanElement

    label.setAttribute(
        "style",
        "position:relative;display:block;width:100%;padding:12px 16px;box-sizing:border-box;border:0;border-radius:6px;background:#2563eb;color:white;font:600 16px sans-serif;text-align:center;cursor:pointer;"
    )

    input.type = "file"
    input.accept = accept
    input.setAttribute(
        "style",
        "position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer;"
    )

    caption.textContent = text

    label.appendChild(input)
    label.appendChild(caption)
    return FileInputButton(label, input)
}
