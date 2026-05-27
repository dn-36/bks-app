package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
expect fun ImageFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
)

expect suspend fun loadImageBitmap(url: String): ImageBitmap?

expect suspend fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
