package com.bkc.core.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bkc.core.presentation.media.loadImageBitmap

@Composable
fun CachedAvatar(
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = size * 0.72f
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = AvatarBitmapCache.getCached(avatarUrl), key1 = avatarUrl) {
        value = AvatarBitmapCache.get(avatarUrl)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private object AvatarBitmapCache {
    private val bitmaps = mutableMapOf<String, ImageBitmap>()

    fun getCached(url: String?): ImageBitmap? =
        url?.let { bitmaps[it] }

    suspend fun get(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        bitmaps[url]?.let { return it }
        val bitmap = loadImageBitmap(url) ?: return null
        bitmaps[url] = bitmap
        return bitmap
    }
}
