package com.bkc.core.presentation.share

import android.content.Context
import android.content.Intent

actual object MessageShare {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    actual fun shareText(text: String): Boolean {
        val appContext = context ?: return false
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Поделиться сообщением").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
