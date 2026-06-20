package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

@Composable
actual fun ProjectFilePicker(
    onDismiss: () -> Unit,
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        val selectedFile = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите файл проекта"
                fileFilter = FileNameExtensionFilter(
                    "Project files",
                    "pdf", "fb2", "epub", "doc", "docx", "xls", "xlsx", "txt", "rtf", "zip", "jpg", "jpeg", "png"
                )
                isAcceptAllFileFilterUsed = true
            }

            val approve = chooser.showOpenDialog(null)
            if (approve == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else {
                null
            }
        }

        if (selectedFile != null) {
            val result = withContext(Dispatchers.IO) {
                selectedFile.name to selectedFile.readBytes()
            }
            onPicked(result.first, result.second)
        } else {
            onDismiss()
        }
    }
}
