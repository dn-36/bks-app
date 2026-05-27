package com.bkc.screens.pdf_viewer
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bkc.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import java.net.URL



actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    Logger.d("readPdfFromUri",url)
    val file = downloadToCache(getKoin().get(), url)
    renderAndroidPdfFirstPage(file)?.asImageBitmap()
}



fun renderAndroidPdfFirstPage(file: File): Bitmap? {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)

    return try {
        renderer.openPage(0).use { page ->
            val scale = 2
            val bitmap = Bitmap.createBitmap(
                page.width * scale,
                page.height * scale,
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    } finally {
        renderer.close()
        pfd.close()
    }
}



fun downloadToCache(context: Context, source: String): File {
    val outFile = File(context.cacheDir, "tmp_${source.hashCode()}.pdf")
    val uri = Uri.parse(source)

    when (uri.scheme) {
        "http", "https" -> {
            URL(source).openStream().use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        "content" -> {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Cannot open content uri: $source")
        }

        "file" -> {
            val path = uri.path ?: error("Empty file path")
            File(path).inputStream().use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        else -> {
            File(source).inputStream().use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    return outFile
}