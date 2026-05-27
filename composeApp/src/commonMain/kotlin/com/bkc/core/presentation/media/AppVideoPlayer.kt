package com.bkc.core.presentation.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun AppVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    showPrevious: Boolean = false,
    showNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {}
)
