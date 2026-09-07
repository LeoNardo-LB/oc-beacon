package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TurnLedgerSummary
import dev.leonardo.ocbeacon.ui.screens.chat.tools.turnLedgerSummary
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCountLong
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

private const val TAG = "TurnLedger"

/**
 * #310④ 轨迹台账——轮次台账行（自有形态；数据 = RenderableTurn 纯投影
 * [TurnLedgerSummary]，无专用 RPC，#313 转录投影原则）。
 *
 * - 默认折叠 = 一行紧凑摘要（轮次 N · 时长 · 步骤 n · 工具 n），FAINT 级
 *   弱化样式不喧宾夺主；点击展开 = 工具名 chips + 工具调用数/token 明细
 *   （token 仅数据在场时显示——V2/DSH 消息级 tokens 缺席即整段隐藏）。
 * - 展开态记忆走屏幕级表（#227 模式：滚出视口不丢、离会话即清），
 *   默认收起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TurnLedgerRow(
    turnKey: String,
    summary: TurnLedgerSummary,
    expandedStates: MutableMap<String, Boolean>,
    /** #312⑤ 轮尾锚点入口：「从此轮分支」（null = 不显示——后端无 fork 域时）。 */
    onForkFromTurn: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val expanded = expandedStates[turnKey] == true
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.small)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.chat_turn_ledger_collapse
                        else R.string.chat_turn_ledger_expand
                    ),
                ) {
                    expandedStates[turnKey] = !expanded
                    AppLogger.d(TAG, "toggle " + turnKey.takeLast(12) + " -> " + !expanded)
                }
                .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.chat_turn_ledger_summary,
                    summary.turnNumber,
                    summary.durationMs?.let { formatDuration(it) } ?: "-",
                    summary.stepCount,
                    summary.toolCallCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SpacingTokens.LG.dp, end = SpacingTokens.LG.dp)
                    .padding(bottom = SpacingTokens.XS.dp),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
            ) {
                if (summary.toolNames.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                    ) {
                        summary.toolNames.forEach { name -> ToolNameChip(name) }
                    }
                }
                Text(
                    text = stringResource(R.string.chat_turn_ledger_tool_calls, summary.toolCallCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                )
                summary.tokensTotal?.let { tokens ->
                    Text(
                        text = stringResource(R.string.chat_turn_ledger_tokens, formatTokenCountLong(tokens)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
                // #312⑤ 轮尾锚点：从此轮分支（展开区尾动作行——primary 色可点文本，
                // 与台账弱化摘要形成主次；点击走 forkSession(锚点消息 id)）
                if (onForkFromTurn != null) {
                    Text(
                        text = stringResource(R.string.chat_turn_fork_from_here),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(ShapeTokens.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.chat_turn_fork_from_here),
                            ) { onForkFromTurn() }
                            .padding(horizontal = SpacingTokens.SM.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 工具名 chip——中性弱化形态（surfaceContainerHigh 底 + labelSmall），非交互。 */
@Composable
private fun ToolNameChip(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ShapeTokens.smallMedium,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.SM.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

/**
 * 台账装配门控：仅已完结轮次渲染。
 *
 * SSE 铁律：流式进行中轮次不显示——完结判定用 allStepsCompleted
 * （turn 内全部 assistant 消息带 completed，#343 与时长解耦）：
 * 零跨度完结轮（DSH 整装事件 created==completed 同信封）照常渲染，
 * 时长未知由 summary 回落 "-"（#338 宁缺毋谎语义）；序号未知
 * （窗口外/分页瞬态）同样静默不渲染。
 */
@Composable
internal fun MaybeTurnLedgerRow(
    turn: RenderableTurn?,
    anchorMsgId: String,
    turnNumber: Int?,
    expandedStates: MutableMap<String, Boolean>,
    /** #312⑤ 「从此轮分支」（锚点 = anchorMsgId；null = 动作不显示）。 */
    onForkFromTurn: ((String) -> Unit)? = null,
) {
    if (turn == null || turnNumber == null) return
    if (!turn.allStepsCompleted) return
    TurnLedgerRow(
        turnKey = "ledger_" + anchorMsgId,
        summary = turnLedgerSummary(turn, turnNumber),
        expandedStates = expandedStates,
        onForkFromTurn = onForkFromTurn?.let { cb -> { cb(anchorMsgId) } },
        modifier = Modifier.padding(top = SpacingTokens.XS.dp),
    )
}
