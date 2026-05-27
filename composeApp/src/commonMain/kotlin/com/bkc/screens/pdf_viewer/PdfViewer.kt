package com.bkc.screens.pdf_viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bkc.screens.projects.ui.PdfThumbnailState

@Composable
expect fun PdfViewer(url: String) /*{
    val state by produceState<PdfThumbnailState>(
        initialValue = PdfThumbnailState.Loading,
        key1 = url
    ) {
        value = try {
            val image = renderPdfFirstPage(url)
            if (image != null) {
                PdfThumbnailState.Success(image)
            } else {
                PdfThumbnailState.Error("Не удалось открыть PDF")
            }
        } catch (t: Throwable) {
            PdfThumbnailState.Error(t.message ?: "PDF error")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            PdfThumbnailState.Loading -> CircularProgressIndicator()

            is PdfThumbnailState.Error -> Text("PDF error: ${s.message}")

            is PdfThumbnailState.Success -> {
                Image(
                    bitmap = s.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
*/
expect suspend fun renderPdfFirstPage(url: String): ImageBitmap?