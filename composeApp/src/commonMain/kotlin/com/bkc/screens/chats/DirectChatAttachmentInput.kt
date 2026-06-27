package com.bkc.screens.chats

import androidx.compose.runtime.Composable

@Composable
expect fun DirectChatAttachmentInput(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
): (() -> Unit)?
