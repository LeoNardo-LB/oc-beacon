package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CommandFeedback
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #323 斜杠命令执行反馈行卡（EventCard 族——自有形态，CompactionCard/
 * TurnMaxTokensCard 同款「事件状态行」模子；非流式内容，不接高度补偿）。
 *
 * 单卡三态（commandId 配对原位更新——run→done 同卡刷新，非两行；
 * DshJobTimelineCard runId 同宿主原位更新同款语义）：
 * - 进行中（run，done==null）：命令名 + args 摘要 + 14dp spinner（语义进行中）；
 * - 完成（done success）：CheckCircle 中性样式 + text 摘要（如有）；
 * - 失败（done error）：ErrorOutline + errorContainer 破色 + text（错误原因）；
 * - 其余结算 kind（词汇开放）：按完成样式呈现、状态标签原样透传。
 *
 * durable 事件——历史重放同渲染；行挂载于消息流尾部按 seq 插入序（log-only
 * 无轮包裹，轮外行）。
 */
@Composable
internal fun CommandFeedbackCard(
    state: CommandFeedback,
    modifier: Modifier = Modifier,
) {
    val done = state.done
    val failed = done?.isError == true

    // 严重度编码（EventCard Q5 同款：成功/信息中性，仅失败破色）
    val containerColor = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = AlphaTokens.FAINT)
    }
    val contentColor = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val secondaryColor = contentColor.copy(alpha = AlphaTokens.MUTED)

    val statusLabel = when {
        done == null -> stringResource(R.string.command_feedback_running)
        done.isSuccess -> stringResource(R.string.command_feedback_completed)
        done.isError -> stringResource(R.string.command_feedback_failed)
        // kind 词汇开放（success|error|…）——未知种类原样透传，不臆译
        else -> done.kind
    }
    // a11y：spinner 动画无文本语义，补内容描述（行文案另由状态标签承载）
    val runningA11y = stringResource(R.string.command_feedback_a11y_running)

    Surface(
        color = containerColor,
        shape = ShapeTokens.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                done == null -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .semantics {
                            contentDescription = runningA11y
                        },
                    strokeWidth = 1.5.dp,
                    color = contentColor,
                    trackColor = secondaryColor,
                )
                failed -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor,
                )
                else -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor,
                )
            }
            Spacer(modifier = Modifier.width(SpacingTokens.SM.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 命令标识：/name（orphan done 无 run 前缀时回退状态文案单行）
                    if (state.name.isNotBlank()) {
                        Text(
                            text = "/" + state.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.SM.dp))
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // args 摘要（recordInput=false 的命令缺席——数据在才显示）
                if (!state.args.isNullOrBlank()) {
                    Text(
                        text = state.args,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 结算文本（error 常带失败原因；success 可带摘要）
                if (!done?.text.isNullOrBlank()) {
                    Text(
                        text = done!!.text!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) contentColor else secondaryColor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
