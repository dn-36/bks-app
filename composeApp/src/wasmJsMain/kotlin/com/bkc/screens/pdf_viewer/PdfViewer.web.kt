package com.bkc.screens.pdf_viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.browser.window

@Composable
actual fun PdfViewer(url: String) {
    LaunchedEffect(url) {
        window.location.href = url
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Открываем PDF...")
    }
}

actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = null
