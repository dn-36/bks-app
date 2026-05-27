package com.bkc.core.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFFFF3209),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3B0900),
    secondary = Color(0xFF0C1024),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E3F8),
    onSecondaryContainer = Color(0xFF0C1024),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0C1024),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C1024),
    surfaceVariant = Color(0xFFF4F4F6),
    onSurfaceVariant = Color(0xFF3E4252),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFB8BBC7),
    outlineVariant = Color(0xFFDDE0EA)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Light,
        shapes = AppShapes,
        content = content
    )
}
