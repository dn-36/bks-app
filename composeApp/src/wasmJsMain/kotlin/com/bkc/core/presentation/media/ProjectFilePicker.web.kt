package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable

@Composable
actual fun ProjectFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    NativeWebFilePicker(
        accept = "*/*",
        onDismiss = onDismiss,
        onPicked = onPicked
    )
}
