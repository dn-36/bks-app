package com.bkc.screens.pdf_viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
actual fun PdfViewer(url: String) {
    val state by produceState<PdfViewerState>(
        initialValue = PdfViewerState.Loading,
        key1 = url
    ) {
        value = try {
            val pages = renderPdfPages(url)
            if (pages.isNotEmpty()) {
                PdfViewerState.Success(pages)
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
            is PdfViewerState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(current.pages) { index, page ->
                    Image(
                        bitmap = page,
                        contentDescription = "Страница ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}

actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    renderPdfPages(url, maxPages = 1).firstOrNull()
}

private suspend fun renderPdfPages(url: String, maxPages: Int = Int.MAX_VALUE): List<ImageBitmap> = withContext(Dispatchers.IO) {
    val file = downloadToCache(url)

    Loader.loadPDF(file).use { document ->
        val renderer = PDFRenderer(document)
        val pageCount = document.numberOfPages.coerceAtMost(maxPages)
        List(pageCount) { index ->
            renderer.renderImageWithDPI(index, 140f, ImageType.RGB).toComposeImageBitmap()
        }
    }
}

private fun downloadToCache(url: String): File {
    val cacheDir = File(System.getProperty("user.home"), ".bkc/pdf-cache").apply { mkdirs() }
    val file = File(cacheDir, "${url.cacheKey()}.pdf")
    if (file.exists() && file.length() > 0L) return file

    val partFile = File(cacheDir, "${file.name}.part")
    partFile.delete()
    val connection = URL(url).openConnection()

    try {
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException("Не удалось загрузить PDF: HTTP $code")
            }
        }

        connection.getInputStream().use { input ->
            partFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (connection is HttpURLConnection) {
            connection.disconnect()
        }

        if (partFile.length() == 0L) {
            throw IllegalStateException("PDF файл пустой")
        }

        if (file.exists()) file.delete()
        check(partFile.renameTo(file)) { "Не удалось сохранить PDF в кэш" }
        return file
    } catch (t: Throwable) {
        partFile.delete()
        if (connection is HttpURLConnection) {
            connection.disconnect()
        }
        if (file.exists() && file.length() > 0L) return file
        throw t
    }
}

private fun String.cacheKey(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}

private sealed interface PdfViewerState {
    data object Loading : PdfViewerState
    data class Success(val pages: List<ImageBitmap>) : PdfViewerState
    data class Error(val message: String) : PdfViewerState
}
