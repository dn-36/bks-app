package com.bkc.core.presentation.share

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual object MessageShare {
    actual fun shareText(text: String): Boolean =
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            true
        }.getOrDefault(false)
}
