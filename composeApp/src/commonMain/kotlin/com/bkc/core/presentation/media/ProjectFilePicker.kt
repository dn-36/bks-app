package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable

@Composable
expect fun ProjectFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
)
