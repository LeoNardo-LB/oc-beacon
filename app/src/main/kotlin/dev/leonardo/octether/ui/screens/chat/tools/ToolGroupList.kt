package dev.leonardo.octether.ui.screens.chat.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.ui.theme.AlphaTokens

/** 聚合卡片通用行数据。 */
data class ToolGroupListItem(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val trailing: @Composable (() -> Unit)? = null,
)

/**
 * 聚合卡片通用行列表。布局迁移自 ContextToolGroupCard（重构前 L80-119）：
 * Column { forEach { if(idx>0) HorizontalDivider; Row(icon, label, subtitle weight:1f, trailing) } }
 *
 * 行样式：图标 16dp(onSurfaceVariant) + 标签(labelMedium) + 副标题(labelMedium,
 * onSurfaceVariant, weight:1f 省略号) + 可选 trailing。
 */
@Composable
fun ToolGroupList(
    items: List<ToolGroupListItem>,
    onItemClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        items.forEachIndexed { idx, item ->
            if (idx > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { m -> if (onItemClick != null) m.clickable { onItemClick(idx) } else m }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val labelModifier =
                    if (item.subtitle.isNullOrEmpty()) Modifier.weight(1f) else Modifier
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = labelModifier,
                )
                if (!item.subtitle.isNullOrEmpty()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                item.trailing?.invoke()
            }
        }
    }
}
