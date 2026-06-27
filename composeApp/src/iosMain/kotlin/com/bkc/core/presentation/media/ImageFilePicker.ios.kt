package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.Foundation.*
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun ImageFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val viewController = LocalUIViewController.current

    DisposableEffect(Unit) {
        val delegate = ImagePickerDelegate(onDismiss, onPicked)
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter()
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration)
        picker.delegate = delegate
        viewController.presentViewController(picker, animated = true, completion = null)

        onDispose { }
    }
}

actual suspend fun loadImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        val nsUrl = NSURL.URLWithString(url) ?: return@withContext null
        val data = NSData.dataWithContentsOfURL(nsUrl) ?: return@withContext null
        val bytes = data.toByteArray()
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual suspend fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return bytes
}

private class ImagePickerDelegate(
    private val onDismiss: () -> Unit,
    private val onPicked: (fileName: String, bytes: ByteArray) -> Unit
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: run {
            onDismiss()
            return
        }
        val provider = result.itemProvider
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val bytes = data?.toByteArray() ?: run {
                onDismiss()
                return@loadDataRepresentationForTypeIdentifier
            }
            onPicked("object.jpg", bytes)
        }
    }
}
