package com.bkc.screens.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.bkc.core.presentation.media.openWebFilePicker

@Composable
actual fun DirectChatAttachmentInput(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
): (() -> Unit)? {
    val currentOnPicked = rememberUpdatedState(onPicked)
    return remember {
        {
            openWebFilePicker(
                accept = "*/*",
                onPicked = { fileName, bytes ->
                    currentOnPicked.value(fileName, bytes)
                }
            )
            Unit
        }
    }
}
