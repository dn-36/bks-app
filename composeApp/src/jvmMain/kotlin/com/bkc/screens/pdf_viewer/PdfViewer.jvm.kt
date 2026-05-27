package com.bkc.screens.pdf_viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.net.URL

@Composable
actual fun PdfViewer(url: String) {
    val state by produceState<PdfViewerState>(
        initialValue = PdfViewerState.Loading,
        key1 = url
    ) {
        value = try {
            val image = renderPdfFirstPage(url)
            if (image != null) {
                PdfViewerState.Success(image)
            } else {
                PdfViewerState.Error("Не удалось открыть PDF")
            }
        } catch (t: Throwable) {
            PdfViewerState.Error(t.message ?: "PDF error")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        when (val current = state) {
            PdfViewerState.Loading -> CircularProgressIndicator()
            is PdfViewerState.Error -> Text("PDF error: ${current.message}")
            is PdfViewerState.Success -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = downloadToTempFile(url)

    Loader.loadPDF(file).use { document ->
        val renderer = PDFRenderer(document)
        val bufferedImage = renderer.renderImageWithDPI(0, 140f, ImageType.RGB)
        bufferedImage.toComposeImageBitmap()
    }
}

private fun downloadToTempFile(url: String): File {
    val file = File.createTempFile("pdf_", ".pdf")
    URL(url).openStream().use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return file
}

private sealed interface PdfViewerState {
    data object Loading : PdfViewerState
    data class Success(val bitmap: ImageBitmap) : PdfViewerState
    data class Error(val message: String) : PdfViewerState
}
