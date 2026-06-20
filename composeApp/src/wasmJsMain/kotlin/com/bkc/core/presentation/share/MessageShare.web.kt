package com.bkc.core.presentation.share

import kotlinx.browser.window

actual object MessageShare {
    actual fun shareText(text: String): Boolean {
        window.navigator.clipboard.writeText(text)
        return true
    }
}
