package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.net.URL
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
actual fun ImageFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите фото"
                fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "webp")
                isAcceptAllFileFilterUsed = false
            }

            val approve = chooser.showOpenDialog(null)
            if (approve == JFileChooser.APPROVE_OPTION) {
                val file: File = chooser.selectedFile
                file.name to file.readBytes()
            } else {
                null
            }
        }

        result?.let { (name, bytes) -> onPicked(name, bytes) } ?: onDismiss()
    }
}

actual suspend fun loadImageBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = URL(url).openStream().use { it.readBytes() }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual suspend fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
