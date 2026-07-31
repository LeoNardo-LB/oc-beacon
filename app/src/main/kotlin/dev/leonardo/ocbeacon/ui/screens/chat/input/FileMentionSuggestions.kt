package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * File mention suggestion popup shown when user types "@<query>".
 */
@Composable
internal fun FileMentionSuggestions(
    results: List<String>,
    onFileSelected: (String) -> Unit
) {
    AnimatedVisibility(
        visible = results.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val configuration = LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.4f).dp

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 4.dp)
        ) {
            items(
                results.take(10),
                key = { it }
            ) { path ->
                val isDir = path.endsWith("/")
                // Split into directory part + filename for display
                val displayPath = if (isDir) path.trimEnd('/') else path
                val lastSlash = displayPath.lastIndexOf('/')
                val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileSelected(path) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = stringResource(R.string.a11y_icon_file),
                        modifier = Modifier.size(16.dp),
                        tint = if (isDir)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                    )
                    Text(
                        text = buildAnnotatedString {
                            if (dirPart.isNotEmpty()) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))) {
                                    append(dirPart)
                                }
                            }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append(namePart)
                            }
                            if (isDir) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))) {
                                    append("/")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
