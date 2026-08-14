package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.util.BreakdownRole
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCount
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextDetailDialog(state: ContextDetailState?, onDismiss: () -> Unit) {
    if (state == null) return
    val params = amoledDialogParams()
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.XL.dp),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            ) {
                // Title
                Text(
                    text = stringResource(R.string.chat_context_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                // ① provider/model + 时间戳
                state.providerModel?.let { pm ->
                    val label = listOfNotNull(pm.providerId, pm.modelId).joinToString(" · ")
                    if (label.isNotBlank()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.timestamps?.let { ts ->
                    val fmt = DateFormatters.monthDayHourMinute()
                    Text(
                        text = stringResource(R.string.chat_context_timestamps, fmt.format(Date(ts.created)), fmt.format(Date(ts.updated))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }

                // ② 进度条
                if (state.contextWindow > 0 && state.contextTokens > 0) {
                    val progress = (state.contextTokens.toFloat() / state.contextWindow).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Text(
                        text = "${formatTokenCount(state.contextTokens)} / ${formatTokenCount(state.contextWindow)}  (${(progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }

                // ③ 消息计数 + 缓存命中率
                state.messageCount?.let { mc ->
                    Text(
                        text = stringResource(
                            R.string.chat_context_msg_summary,
                            mc.user + mc.assistant,
                            mc.user,
                            mc.assistant,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.cacheHitRate?.let { rate ->
                    Text(
                        text = stringResource(R.string.chat_context_cache_hit, (rate * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }

                // ④ breakdown 纵向列表
                state.breakdown?.let { bd ->
                    if (bd.segments.isNotEmpty()) {
                        Spacer(Modifier.height(SpacingTokens.XS.dp))
                        Text(
                            text = stringResource(R.string.chat_context_composition),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        bd.segments.forEach { seg ->
                            val roleLabel = when (seg.role) {
                                BreakdownRole.USER -> stringResource(R.string.chat_role_user)
                                BreakdownRole.ASSISTANT -> stringResource(R.string.chat_role_assistant)
                                BreakdownRole.TOOL -> stringResource(R.string.chat_role_tool)
                                BreakdownRole.OTHER -> stringResource(R.string.chat_context_other_note)
                            }
                            val barColor = when (seg.role) {
                                BreakdownRole.USER -> MaterialTheme.colorScheme.primary
                                BreakdownRole.ASSISTANT -> MaterialTheme.colorScheme.secondary
                                BreakdownRole.TOOL -> MaterialTheme.colorScheme.tertiary
                                BreakdownRole.OTHER -> MaterialTheme.colorScheme.outline
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = roleLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatTokenCount(seg.estimatedTokens),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(SpacingTokens.SM.dp))
                                Text(
                                    text = "${(seg.percent * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                                Spacer(Modifier.width(SpacingTokens.SM.dp))
                                LinearProgressIndicator(
                                    progress = { seg.percent.coerceIn(0f, 1f) },
                                    modifier = Modifier.width(48.dp).height(4.dp),
                                    color = barColor,
                                    trackColor = barColor.copy(alpha = AlphaTokens.FAINT),
                                )
                            }
                        }
                    }
                }

                // ⑤ Token 明细（复用现有 TokenUsageCard）
                Spacer(Modifier.height(SpacingTokens.XS.dp))
                TokenUsageCard(
                    inputTokens = state.inputTokens,
                    outputTokens = state.outputTokens,
                    reasoningTokens = state.reasoningTokens,
                    cacheReadTokens = state.cacheReadTokens,
                    cacheWriteTokens = state.cacheWriteTokens,
                    totalCost = state.totalCost,
                )

                Spacer(Modifier.height(SpacingTokens.SM.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.close), DialogButtonRole.Primary) { onDismiss() },
                    ),
                )
            }
        }
    }
}
