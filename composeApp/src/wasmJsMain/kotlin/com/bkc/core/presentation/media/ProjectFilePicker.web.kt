package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun ProjectFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        pickFile(
            accept = "*/*",
            onPicked = onPicked,
            onDismiss = onDismiss
        )
    }
}
