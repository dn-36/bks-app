package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.interop.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun ProjectFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val viewController = LocalUIViewController.current

    DisposableEffect(Unit) {
        val delegate = ProjectDocumentPickerDelegate(onPicked, onDismiss)
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeItem),
            asCopy = true
        )
        picker.delegate = delegate
        viewController.presentViewController(picker, animated = true, completion = null)

        onDispose { }
    }
}

private class ProjectDocumentPickerDelegate(
    private val onPicked: (fileName: String, bytes: ByteArray) -> Unit,
    private val onDismiss: () -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val fileName = url.lastPathComponent ?: "project-file"
        val data = NSData.dataWithContentsOfURL(url) ?: return
        onPicked(fileName, data.toByteArray())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDismiss()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return bytes
}
