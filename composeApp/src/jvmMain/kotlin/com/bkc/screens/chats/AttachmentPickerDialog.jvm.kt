package com.bkc.screens.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bkc.core.presentation.media.ImageFilePicker
import com.bkc.core.presentation.media.ProjectFilePicker

@Composable
actual fun AttachmentPickerDialog(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    var pickImage by remember { mutableStateOf(false) }
    var pickFile by remember { mutableStateOf(false) }

    if (pickImage) {
        ImageFilePicker(onDismiss = onDismiss, onPicked = onPicked)
    }

    if (pickFile) {
        ProjectFilePicker(onDismiss = onDismiss, onPicked = onPicked)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить вложение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickImage = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Text("Фото")
                }
                Button(onClick = { pickFile = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                    Text("Видео или файл")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
