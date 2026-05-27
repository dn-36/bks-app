package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun ProjectFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
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
                val file: File = chooser.selectedFile
                file.name to file.readBytes()
            } else {
                null
            }
        }

        result?.let { (name, bytes) -> onPicked(name, bytes) }
    }
}
