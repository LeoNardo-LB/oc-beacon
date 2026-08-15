package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 压缩完成卡片（2026-08-15）：
 * - 收起态：居中分割线 + 「上下文已压缩」+ 展开箭头（轻量，不占视觉重量）
 * - 展开态：分割线下方展示无边框轻量卡片（透明背景 + 细边框，与
 *   SyntheticNotificationCard 一致的视觉语言）内含摘要全文（Markdown 源文本
 *   等宽呈现，保持服务器原始格式）+ 收起箭头
 *
 * 数据源：V2 REST compaction 消息的 text（Part.Compaction.summary）。
 * V1 SSE Part.Compaction 无 summary → 仅分割线（不可展开，行为同旧版）。
 */
@Composable
internal fun CompactionCard(summary: String?) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = !summary.isNullOrBlank()

    Column(modifier = Modifier.fillMaxWidth()) {
        // 分割线行（收起/展开共用）：—— 上下文已压缩 ▾ ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { m -> if (canExpand) m.clickable { expanded = !expanded } else m }
                .padding(vertical = SpacingTokens.XS.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )
            Text(
                text = stringResource(R.string.chat_summarized),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp)
            )
            if (canExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
                Spacer(modifier = Modifier.width(SpacingTokens.XS.dp))
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )
        }

        // 展开态：无边框轻量卡片（透明背景 + 细边框，同 synthetic 通知卡片）
        AnimatedVisibility(visible = expanded && canExpand) {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
                ),
                shape = ShapeTokens.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.XL.dp, vertical = SpacingTokens.XS.dp)
            ) {
                Text(
                    text = summary!!,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    modifier = Modifier.padding(SpacingTokens.MD.dp)
                )
            }
        }
    }
}
