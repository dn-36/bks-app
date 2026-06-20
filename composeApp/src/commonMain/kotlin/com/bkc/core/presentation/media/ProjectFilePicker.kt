package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable

@Composable
expect fun ProjectFilePicker(
    onDismiss: () -> Unit = {},
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
)
