package com.bkc.screens.pdf_viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.interop.LocalUIViewController
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val viewController = LocalUIViewController.current

    DisposableEffect(Unit) {
        val delegate = DocumentPickerDelegate(onPicked)

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypePDF),
            asCopy = true
        )
        picker.delegate = delegate
        viewController.presentViewController(
            viewControllerToPresent = picker,
            animated = true,
            completion = null
        )

        onDispose {
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class DocumentPickerDelegate(
    val onPicked: (fileName: String, bytes: ByteArray) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val fileName = url.lastPathComponent ?: "document.pdf"

        val data = NSData.dataWithContentsOfURL(url) ?: return

        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }

        onPicked(fileName, bytes)
    }
}