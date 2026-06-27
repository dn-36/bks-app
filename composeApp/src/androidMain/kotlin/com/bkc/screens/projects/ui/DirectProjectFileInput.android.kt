package com.bkc.screens.projects.ui

import androidx.compose.runtime.Composable

@Composable
actual fun DirectProjectFileInput(
    onPicked: (fileName: String, bytes: ByteArray) -> Unit
): (() -> Unit)? = null
