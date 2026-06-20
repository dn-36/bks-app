package com.bkc.screens.pdf_viewer

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bkc.core.presentation.media.pickFile

@Composable
actual fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    Button(onClick = { pickFile("application/pdf,.pdf", onPicked) }) {
        Text("Выбрать PDF")
    }
}
