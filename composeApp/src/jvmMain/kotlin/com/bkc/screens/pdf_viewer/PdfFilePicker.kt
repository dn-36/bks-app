package com.bkc.screens.pdf_viewer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun PdfFilePicker(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
) {
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите PDF"
                fileFilter = FileNameExtensionFilter("PDF files", "pdf")
                isAcceptAllFileFilterUsed = false
            }

            val approve = chooser.showOpenDialog(null)
            if (approve == JFileChooser.APPROVE_OPTION) {
                val file: File = chooser.selectedFile
                file.name to file.readBytes()
            } else {
                null
            }
        }

        result?.let { (name, bytes) ->
            onPicked(name, bytes)
        }
    }
}