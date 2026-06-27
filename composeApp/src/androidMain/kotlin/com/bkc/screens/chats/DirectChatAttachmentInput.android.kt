package com.bkc.screens.chats

import androidx.compose.runtime.Composable

@Composable
actual fun DirectChatAttachmentInput(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
): (() -> Unit)? = null
