package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

@Composable
internal fun FileCard(file: Part.File) {
    // Images are handled by ImageThumbnailRow, so FileCard only handles non-image files
    FileCardFallback(file)
}

@Composable
internal fun FileCardFallback(file: Part.File) {
    val isAmoled = isAmoledTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = if (isAmoled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.AMOLED)
    }
    val contentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = ShapeTokens.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = stringResource(R.string.a11y_icon_file),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = file.filename
                    ?: file.url?.let { dev.leonardo.octether.util.PathUtils.fileName(it) }?.takeIf { it.isNotBlank() }
                    ?: file.mime,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
