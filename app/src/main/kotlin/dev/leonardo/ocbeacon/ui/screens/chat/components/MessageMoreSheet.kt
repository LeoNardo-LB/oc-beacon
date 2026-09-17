package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * （2026-09-12 消息层扁平化）消息「更多」底部面板（US#12/#13/#31/#35/#36）。
 *
 * 低频 / 敏感动作收进这里，主界面尾部保持干净：
 * - 👍/👎（仅 DSH——[onRate] 非 null）；
 * - 复制 Markdown 源码（[onCopyMarkdownSource] 非 null）；
 * - 删除消息（仅服务器支持时出现——[onDelete] 非 null，能力位门控）。
 *
 * 形态 = Material 3 底部面板 + 可滚动内容（小屏也能操作）。
 * 每一项独立判空：无任何可用项时调用方不应打开本面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageMoreSheet(
    feedback: MessageFeedbackItem?,
    onRate: ((MessageFeedbackRating) -> Unit)?,
    onCopyMarkdownSource: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SpacingTokens.LG.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_more),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    start = SpacingTokens.LG.dp,
                    end = SpacingTokens.LG.dp,
                    top = SpacingTokens.SM.dp,
                    bottom = SpacingTokens.SM.dp,
                ),
            )
            if (onRate != null) {
                MoreSheetRow(
                    icon = Icons.Filled.ThumbUp,
                    label = stringResource(R.string.chat_feedback_positive),
                    selected = feedback?.rating == MessageFeedbackRating.Positive,
                    onClick = {
                        onRate(MessageFeedbackRating.Positive)
                        onDismiss()
                    },
                )
                MoreSheetRow(
                    icon = Icons.Filled.ThumbDown,
                    label = stringResource(R.string.chat_feedback_negative),
                    selected = feedback?.rating == MessageFeedbackRating.Negative,
                    onClick = {
                        onRate(MessageFeedbackRating.Negative)
                        onDismiss()
                    },
                )
            }
            if (onCopyMarkdownSource != null) {
                MoreSheetRow(
                    icon = Icons.Filled.Code,
                    label = stringResource(R.string.chat_copy_markdown_source),
                    onClick = {
                        onCopyMarkdownSource()
                        onDismiss()
                    },
                )
            }
            if (onDelete != null) {
                MoreSheetRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.chat_delete_message),
                    destructive = true,
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    destructive: Boolean = false,
) {
    val tint = when {
        destructive -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) tint else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = SpacingTokens.MD.dp),
        )
    }
}
