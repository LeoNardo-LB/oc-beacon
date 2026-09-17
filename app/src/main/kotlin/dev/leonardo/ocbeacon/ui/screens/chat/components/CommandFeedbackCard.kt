package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CommandFeedback
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * #323 斜杠命令执行反馈卡 / 2026-09-12 扁平化归并表 #6：**统一通知卡家族的
 * 薄适配器**（EventCard scaffold；原自绘 Surface 色块退役）。
 *
 * 单卡三态（commandId 配对原位更新——run→done 同卡刷新，非两行）：
 * - 进行中（run，done==null）：14dp spinner（经 EventCard leadingContent 槽）；
 * - 受理占位（#365 本地，done==null）：静态时钟图标（受理即知，无动画）；
 * - 完成（done success）：CheckCircle 中性样式；
 * - 失败（done error）：ErrorOutline + 失败破色（EventCard failed）。
 *
 * 折叠态 = 单行（图标 + /name + 状态 + args 摘要）；展开体 = 结算文本
 * （数据在才给展开）。log-only 事件——历史重放同渲染。
 */
@Composable
internal fun CommandFeedbackCard(
    state: CommandFeedback,
    expandedStates: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier,
) {
    val done = state.done
    val failed = done?.isError == true
    val running = done == null
    val localAccepted = state.localAccepted && done == null

    val statusLabel = when {
        localAccepted -> stringResource(R.string.command_feedback_accepted)
        running -> stringResource(R.string.command_feedback_running)
        done.isSuccess -> stringResource(R.string.command_feedback_completed)
        done.isError -> stringResource(R.string.command_feedback_failed)
        // kind 词汇开放（success|error|…）——未知种类原样透传，不臆译
        else -> done.kind
    }
    val runningA11y = stringResource(R.string.command_feedback_a11y_running)
    val leadingIcon = when {
        localAccepted -> Icons.Outlined.Schedule
        running -> Icons.Outlined.Schedule
        failed -> Icons.Outlined.ErrorOutline
        else -> Icons.Filled.CheckCircle
    }
    val description = buildString {
        if (state.name.isNotBlank()) append("/").append(state.name)
        if (!state.args.isNullOrBlank()) {
            if (isNotEmpty()) append(" ")
            append(state.args)
        }
    }.takeIf { it.isNotBlank() }
    val settleText = done?.text?.takeIf { it.isNotBlank() }

    EventCard(
        eventKey = "cmd_" + state.commandId,
        timeMs = done?.time?.takeIf { it > 0 } ?: state.startedAt,
        label = statusLabel,
        leadingIcon = leadingIcon,
        leadingContent = if (running && !localAccepted) {
            {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(13.dp)
                        .semantics { contentDescription = runningA11y },
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else null,
        failed = failed,
        description = description,
        expandedStates = expandedStates,
        bodyContent = settleText?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                )
            }
        },
        modifier = modifier,
    )
}
