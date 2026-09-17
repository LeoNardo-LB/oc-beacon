package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.MessageDetailField
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.MessageDetailInput
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.messageDetailFields
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCountLong
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Locale

/**
 * （2026-09-17 v2）消息详情弹窗——取代 MessageMoreSheet 底部抽屉（US#13/#45）。
 *
 * 形态 = Material 3 AlertDialog（居中）：上半只读信息、下半动作列表，内容可滚动。
 * 只读字段由 [messageDetailFields] 装配（空值整项不渲染）；动作按回调非 null 判在场：
 * 点赞点踩（仅 DSH）/ 复制 Markdown 源码 / 从此轮分支（仅末轮）/ 删除消息（能力位就绪）。
 *
 * 因为时间已移出消息头部，本入口（尾部 ⓘ）每条角色消息都渲染。
 */
@Composable
internal fun MessageDetailDialog(
    input: MessageDetailInput,
    feedback: MessageFeedbackItem?,
    onRate: ((MessageFeedbackRating) -> Unit)?,
    onCopyMarkdownSource: (() -> Unit)?,
    onForkFromTurn: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val fields = remember(input) { messageDetailFields(input) }
    val params = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        normalElevation = 0.dp,
    )
    val hasAction = onRate != null || onCopyMarkdownSource != null || onForkFromTurn != null || onDelete != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = params.containerColor,
        tonalElevation = params.tonalElevation,
        title = { Text(stringResource(R.string.chat_msg_detail_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                for (field in fields) {
                    DetailInfoRow(
                        label = stringResource(detailLabelRes(field)),
                        value = detailValue(field, input),
                    )
                }
                if (hasAction) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.SM.dp))
                }
                if (onRate != null) {
                    DetailActionRow(
                        icon = Icons.Filled.ThumbUp,
                        label = stringResource(R.string.chat_feedback_positive),
                        selected = feedback?.rating == MessageFeedbackRating.Positive,
                        onClick = {
                            onRate(MessageFeedbackRating.Positive)
                            onDismiss()
                        },
                    )
                    DetailActionRow(
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
                    DetailActionRow(
                        icon = Icons.Filled.Code,
                        label = stringResource(R.string.chat_copy_markdown_source),
                        onClick = {
                            onCopyMarkdownSource()
                            onDismiss()
                        },
                    )
                }
                if (onForkFromTurn != null) {
                    DetailActionRow(
                        icon = Icons.Filled.CopyAll,
                        label = stringResource(R.string.chat_turn_fork_from_here),
                        onClick = {
                            onForkFromTurn()
                            onDismiss()
                        },
                    )
                }
                if (onDelete != null) {
                    DetailActionRow(
                        icon = Icons.Filled.Delete,
                        label = stringResource(R.string.chat_delete_message),
                        destructive = true,
                        onClick = { showDeleteConfirm = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    if (showDeleteConfirm && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_message_title),
            message = stringResource(R.string.chat_delete_message_message),
            confirmLabel = stringResource(R.string.chat_delete_message),
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
                onDismiss()
            },
        )
    }
}

private fun detailLabelRes(field: MessageDetailField): Int = when (field) {
    MessageDetailField.TIME -> R.string.chat_detail_time
    MessageDetailField.AGENT -> R.string.chat_label_agent
    MessageDetailField.MODEL -> R.string.chat_stats_model
    MessageDetailField.DURATION -> R.string.chat_detail_duration
    MessageDetailField.STEPS -> R.string.chat_stats_steps
    MessageDetailField.TOOLS -> R.string.chat_stats_tools
    MessageDetailField.TOKENS -> R.string.chat_detail_tokens
    MessageDetailField.COST -> R.string.chat_stats_cost
}

private fun detailValue(field: MessageDetailField, input: MessageDetailInput): String = when (field) {
    MessageDetailField.TIME -> DateFormatters.messageTimestamp(input.timeMs)
    MessageDetailField.AGENT -> input.agentName.orEmpty()
    MessageDetailField.MODEL -> listOfNotNull(input.providerId, input.modelId).joinToString(" · ")
    MessageDetailField.DURATION -> formatDuration(input.durationMs ?: 0L)
    MessageDetailField.STEPS -> input.stepCount.toString()
    MessageDetailField.TOOLS -> input.toolCallCount.toString()
    MessageDetailField.TOKENS -> formatTokenCountLong(input.tokensTotal ?: 0L)
    MessageDetailField.COST -> String.format(Locale.US, "$%.4f", input.cost ?: 0.0)
}

/** 只读信息行（label 左 / value 右）。 */
@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 动作行（图标 + 文案；删除类破坏性着色）。 */
@Composable
private fun DetailActionRow(
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
            .padding(vertical = SpacingTokens.MD.dp),
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
