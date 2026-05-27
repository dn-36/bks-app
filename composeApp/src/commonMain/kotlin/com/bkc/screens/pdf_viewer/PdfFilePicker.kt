package com.bkc.screens.pdf_viewer

import androidx.compose.runtime.Composable

@Composable
expect fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
)