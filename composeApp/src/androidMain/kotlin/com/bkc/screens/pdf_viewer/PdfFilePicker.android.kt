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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest



actual suspend fun renderPdfFirstPage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    Logger.d("readPdfFromUri",url)
    val file = downloadToCache(getKoin().get(), url)
    renderAndroidPdfFirstPage(file)?.asImageBitmap()
}



fun renderAndroidPdfFirstPage(file: File): Bitmap? {
    return renderAndroidPdfPages(file, maxPages = 1).firstOrNull()
}

fun renderAndroidPdfPages(file: File, maxPages: Int = Int.MAX_VALUE): List<Bitmap> {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)

    return try {
        val pageCount = renderer.pageCount.coerceAtMost(maxPages)
        List(pageCount) { index ->
            renderer.openPage(index).use { page ->
                val scale = 2
                val bitmap = Bitmap.createBitmap(
                    page.width * scale,
                    page.height * scale,
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } finally {
        renderer.close()
        pfd.close()
    }
}



fun downloadToCache(context: Context, source: String): File {
    val cacheDir = File(context.cacheDir, "pdf-cache").apply { mkdirs() }
    val outFile = File(cacheDir, "${source.cacheKey()}.pdf")
    if (outFile.exists() && outFile.length() > 0L) return outFile

    val partFile = File(cacheDir, "${outFile.name}.part")
    partFile.delete()
    val uri = Uri.parse(source)

    try {
        when (uri.scheme) {
            "http", "https" -> {
                val connection = URL(source).openConnection()
                if (connection is HttpURLConnection) {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        connection.disconnect()
                        error("Не удалось загрузить PDF: HTTP $code")
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
            }

            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    partFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Cannot open content uri: $source")
            }

            "file" -> {
                val path = uri.path ?: error("Empty file path")
                File(path).inputStream().use { input ->
                    partFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            else -> {
                File(source).inputStream().use { input ->
                    partFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        if (partFile.length() == 0L) error("PDF файл пустой")
        if (outFile.exists()) outFile.delete()
        check(partFile.renameTo(outFile)) { "Не удалось сохранить PDF в кэш" }
        return outFile
    } catch (t: Throwable) {
        partFile.delete()
        if (outFile.exists() && outFile.length() > 0L) return outFile
        throw t
    }
}

private fun String.cacheKey(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
    return bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}
