package dev.leonardo.ocbeacon.ui.screens.chat.components

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
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

@Composable
internal fun FileCard(file: Part.File) {
    // Images are handled by ImageThumbnailRow, so FileCard only handles non-image files
    FileCardFallback(file)
}

@Composable
internal fun FileCardFallback(file: Part.File) {
    // #215 批3：容器统一——EmbeddedCardContainer（surfaceContainerLow + 1dp 边框
    // + medium 12dp）退役，迁 AmoledSurface 统一语言（surface + tonal 1dp +
    // smallMedium 6dp，AMOLED 纯黑+边框），与 scaffold 家族/提问卡同容器
    val contentColor = MaterialTheme.colorScheme.onSurface

    AmoledSurface(
        isAmoledDark = isAmoledTheme(),
        normalColor = MaterialTheme.colorScheme.surface,
        normalTonalElevation = 1.dp,
        shape = ShapeTokens.smallMedium,
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
                    ?: file.url?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it) }?.takeIf { it.isNotBlank() }
                    ?: file.mime,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
