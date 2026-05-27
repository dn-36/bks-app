package com.bkc.core.presentation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun AppVideoPlayer(
    url: String,
    modifier: Modifier,
    showPrevious: Boolean,
    showNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("Просмотр видео внутри приложения пока недоступен")
    }
}
