package com.bkc.screens.chats

import androidx.compose.runtime.Composable

@Composable
expect fun AttachmentPickerDialog(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
)
