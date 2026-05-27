package com.bkc.screens.pdf_viewer

import androidx.compose.runtime.Composable


// shared/androidMain

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.bkc.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()



    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = readPdfFromUri(context, uri)
                if (result != null) {
                    onPicked(result.first, result.second)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(arrayOf("application/pdf"))
    }
}


private suspend fun readPdfFromUri(
    context: Context,
    uri: Uri
): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    val name = queryFileName(context, uri) ?: "document.pdf"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
    name to bytes
}

private fun queryFileName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            return cursor.getString(nameIndex)
        }
    }
    return null
}