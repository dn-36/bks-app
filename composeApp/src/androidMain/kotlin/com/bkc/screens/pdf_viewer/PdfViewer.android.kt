package com.bkc.screens.pdf_viewer

import android.graphics.Bitmap
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun PdfViewer(url: String) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    LaunchedEffect(url) {
        loading = true
        error = null
        pages = emptyList()

        try {
            println("PDF SOURCE = $url")

            val file = withContext(Dispatchers.IO) {
                downloadToCache(context, url)
            }

            pages = withContext(Dispatchers.IO) {
                renderAndroidPdfPages(file)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text("PDF error: $error")
            pages.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(pages) { index, page ->
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = "Страница ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
            else -> Text("PDF error: пустой документ")
        }
    }
}
