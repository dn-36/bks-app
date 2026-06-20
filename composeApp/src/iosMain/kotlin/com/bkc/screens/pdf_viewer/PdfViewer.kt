package com.bkc.screens.pdf_viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.UIKitView
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView

@Composable
actual fun PdfViewer(url: String) {
    UIKitView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PDFView().apply {
                autoScales = true
                displayMode = 1
                displayDirection = 0
                document = pdfDocument(url)
            }
        },
        update = { view ->
            view.document = pdfDocument(url)
        }
    )
}

actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = null

private fun pdfDocument(url: String): PDFDocument? {
    val nsUrl = when {
        url.startsWith("http://") || url.startsWith("https://") -> NSURL.URLWithString(url)
        url.startsWith("file://") -> NSURL.URLWithString(url)
        else -> NSURL.fileURLWithPath(url)
    } ?: return null

    return PDFDocument(nsUrl)
}
