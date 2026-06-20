package com.bkc.core.presentation.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun ProjectFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val file = readFileFromUri(context, uri)
                if (file != null) {
                    onPicked(file.first, file.second)
                } else {
                    onDismiss()
                }
            }
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(arrayOf("*/*"))
    }
}

private suspend fun readFileFromUri(
    context: Context,
    uri: Uri
): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    val name = queryFileName(context, uri) ?: "project-file"
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
