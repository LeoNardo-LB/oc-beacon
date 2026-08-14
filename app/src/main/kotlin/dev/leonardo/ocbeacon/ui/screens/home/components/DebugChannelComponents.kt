package dev.leonardo.ocbeacon.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.domain.model.DebugProfile
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #132 调试通道 —— Home 页入口卡片（仅 debug 构建渲染，调用方已按
 * [dev.leonardo.ocbeacon.debug.DebugChannel.profiles] 非空守卫）。
 * 文案为调试专用硬编码（不进 i18n，不面向真实用户）。
 */
@Composable
internal fun DebugChannelEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeTokens.medium)
            .clickable(onClick = onClick),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = AlphaTokens.FAINT),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.MD.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Debug Channel (dev)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "One-tap connect + jump to sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * #132 调试通道 —— 套餐选择对话框。
 * 点套餐 → [onSelect]（调用方负责 activateDebugProfile + 导航）。
 */
@Composable
internal fun DebugChannelDialog(
    profiles: List<DebugProfile>,
    onDismiss: () -> Unit,
    onSelect: (DebugProfile) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Channel") },
        text = {
            Column {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(profile) }
                            .padding(vertical = SpacingTokens.SM.dp, horizontal = SpacingTokens.XS.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.label,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = profile.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
