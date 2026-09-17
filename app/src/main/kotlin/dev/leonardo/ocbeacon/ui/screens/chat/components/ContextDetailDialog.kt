package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.RowCapabilities
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.TurnDetailRow
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.buildTurnDetailRows
import dev.leonardo.ocbeacon.ui.screens.chat.util.BreakdownRole
import dev.leonardo.ocbeacon.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCount
import dev.leonardo.ocbeacon.ui.screens.chat.util.webFormatTokens
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCountLong
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SheetTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * （2026-09-12 消息层扁平化 批3）上下文详情 = Material 3 底部面板（可滚动）。
 *
 * 由不可滚动的 BasicAlertDialog 迁移而来（spec 统计弹窗节）：会话级区块原样
 * 保留（LazyColumn item），新增 sessionStats 区（DSH 投影，OpenCode 恒 null
 * → 整区不渲染）与逐轮明细列表（倒序、懒加载、行点击展开）。
 *
 * 能力位门控在装配期完成（[caps] → buildTurnDetailRows：cost/timing 缺席即
 * 整项隐藏，形态不变——docs/ui-conventions.md 能力位铁律）。
 *
 * 形态遵循主对话抽屉三件套（SheetTokens.ChatSheetHeightFraction 固定 75% +
 * 内部列表 weight(1f) + skipPartiallyExpanded）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextDetailDialog(
    state: ContextDetailState?,
    caps: RowCapabilities,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    val params = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surfaceContainerLow,
        normalElevation = 0.dp,
    )
    // 逐轮行折叠/展开状态（key = 列表下标；rows 由 remember 缓存，下标稳定）
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // 能力位门控 + 可选桶归一在装配期完成（组合外一次计算）
    val rows = remember(state.turnDetailInputs, caps) {
        buildTurnDetailRows(state.turnDetailInputs, caps)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = params.containerColor,
        tonalElevation = params.tonalElevation,
        dragHandle = { SmallSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // #379：内容手势隔离——列表滚动/fling 不致收起（仅手柄/点外/返回）
                .sheetContentGestureIsolation()
                .height(
                    LocalConfiguration.current.screenHeightDp.dp *
                        SheetTokens.ChatSheetHeightFraction
                ),
        ) {
            // 标题行（标题 + 关闭；SheetScaffold 同款）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_context_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                // contentPadding 吸收导航栏 inset（#379 sheet 底部安全区）
                contentPadding = PaddingValues(
                    start = SpacingTokens.LG.dp,
                    end = SpacingTokens.LG.dp,
                    bottom = SpacingTokens.XL.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                // ① provider/model + 时间戳
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
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
                            // DSH created=0（epoch 0 不当真实创建时间展示，V7 dash 先例）
                            Text(
                                text = stringResource(
                                    R.string.chat_context_timestamps,
                                    DateFormatters.formatEpochOrDash(fmt, ts.created),
                                    DateFormatters.formatEpochOrDash(fmt, ts.updated),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                            )
                        }
                    }
                }

                // ② 进度条
                if (state.contextWindow > 0 && state.contextTokens > 0) {
                    item {
                        val progress = (state.contextTokens.toFloat() / state.contextWindow).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
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
                    }
                }

                // ③ 消息计数 + 缓存命中率
                if (state.messageCount != null || state.cacheHitRate != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
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
                        }
                    }
                }

                // ④ breakdown 纵向列表
                state.breakdown?.let { bd ->
                    if (bd.segments.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
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
                    }
                }

                // ⑤ Token 明细（复用现有 TokenUsageCard）
                item {
                    TokenUsageCard(
                        inputTokens = state.inputTokens,
                        outputTokens = state.outputTokens,
                        reasoningTokens = state.reasoningTokens,
                        cacheReadTokens = state.cacheReadTokens,
                        cacheWriteTokens = state.cacheWriteTokens,
                        totalCost = state.totalCost,
                    )
                }

                // ⑥ 子代理区（DSH 专属：tokenUsage 累计 + subagentTiming 活跃时长；
                //    OpenCode 会话两字段恒 null → 整区不渲染，V2 零改动）
                if (state.subagentTokens != null || state.subagentActiveDurationMs != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                            Text(
                                text = stringResource(R.string.chat_context_subagent_title),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            state.subagentTokens?.let { tokens ->
                                Text(
                                    text = stringResource(
                                        R.string.chat_context_subagent_tokens,
                                        formatTokenCountLong(tokens.total),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            state.subagentActiveDurationMs?.let { ms ->
                                Text(
                                    text = stringResource(
                                        R.string.chat_context_subagent_duration,
                                        formatDuration(ms),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                            }
                        }
                    }
                }

                // ⑦ DSH 上下文占用投影区（超集追加：~used/window 行 + system/tools/messages
                //    分段条——Web ContextMeter panel 语义；投影缺席整区不渲染）
                if (state.projectionUsedTokens != null && state.projectionContextWindow != null) {
                    item {
                        ProjectionSection(state = state)
                    }
                }

                // ⑧ sessionStats 区（批3 新增：DSH 投影，OpenCode 恒 null → 整区不渲染）
                state.projectionSessionStats?.let { sessionStats ->
                    item(key = "session_stats") {
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                            Text(
                                text = stringResource(R.string.chat_stats_session_title),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.chat_stats_summary,
                                    sessionStats.turns,
                                    sessionStats.steps,
                                    formatDuration(sessionStats.llmMs),
                                    formatDuration(sessionStats.toolMs),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (sessionStats.ttftSteps > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.chat_stats_ttft_avg,
                                        formatDuration(sessionStats.ttftMs / sessionStats.ttftSteps),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                            }
                            if (sessionStats.decodeMs > 0) {
                                val tokensPerSec = sessionStats.decodeTokens * 1000.0 / sessionStats.decodeMs
                                Text(
                                    text = stringResource(
                                        R.string.chat_stats_decode,
                                        "%.1f".format(tokensPerSec),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                            }
                        }
                    }
                }

                // ⑨ 逐轮明细（批3 新增：倒序、懒加载、行点击展开）
                if (rows.isNotEmpty()) {
                    item(key = "per_turn_title") {
                        Text(
                            text = stringResource(R.string.chat_stats_per_turn_title),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    itemsIndexed(rows, key = { index, _ -> "turn-row-$index" }) { index, row ->
                        TurnDetailEntry(row = row, index = index, expanded = expanded)
                    }
                }
            }
        }
    }
}

/** ⑦ DSH 上下文占用投影区（原区块迁入，语义零改动）。 */
@Composable
private fun ProjectionSection(state: ContextDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        Text(
            text = stringResource(R.string.context_pressure_title),
            style = MaterialTheme.typography.labelMedium,
        )
        // 调用方已按双非空条件门控本区
        val usedP = state.projectionUsedTokens!!
        val windowP = state.projectionContextWindow!!
        Text(
            text = stringResource(
                R.string.context_pressure_used,
                webFormatTokens(usedP),
                webFormatTokens(windowP),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        val breakdownTotal = state.projectionBreakdown?.let {
            it.systemTokens + it.toolsTokens + it.messageTokens
        } ?: 0L
        if (breakdownTotal > 0) {
            val percent = (usedP.toDouble() / windowP).coerceIn(0.0, 1.0)
            val bd = state.projectionBreakdown!!
            Row(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                bd.systemTokens.takeIf { it > 0 }?.let {
                    Box(
                        modifier = Modifier
                            .weight((percent * it / breakdownTotal).toFloat().coerceAtLeast(0.002f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                bd.toolsTokens.takeIf { it > 0 }?.let {
                    Box(
                        modifier = Modifier
                            .weight((percent * it / breakdownTotal).toFloat().coerceAtLeast(0.002f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
                bd.messageTokens.takeIf { it > 0 }?.let {
                    Box(
                        modifier = Modifier
                            .weight((percent * it / breakdownTotal).toFloat().coerceAtLeast(0.002f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BreakdownLegendRow(
                    color = MaterialTheme.colorScheme.primary,
                    label = stringResource(R.string.context_pressure_system),
                    tokens = bd.systemTokens,
                )
                BreakdownLegendRow(
                    color = MaterialTheme.colorScheme.tertiary,
                    label = stringResource(R.string.context_pressure_tools),
                    tokens = bd.toolsTokens,
                )
                BreakdownLegendRow(
                    color = MaterialTheme.colorScheme.secondary,
                    label = stringResource(R.string.context_pressure_messages),
                    tokens = bd.messageTokens,
                )
            }
        }
    }
}

/**
 * 逐轮明细行（US#25-27）：折叠 = 「第 N 轮 · 耗时 · tokens 总量」；
 * 点击展开 = tokens 桶 / TTFT / 速度 / 步骤数 / 工具数 / 成本 / 模型
 * （null 项整项不渲染——装配期已按能力位门控）。
 */
@Composable
private fun TurnDetailEntry(
    row: TurnDetailRow,
    index: Int,
    expanded: MutableMap<Int, Boolean>,
) {
    val hasBody = row.expandable || row.tokensInput != null || row.tokensOutput != null
    val isExpanded = hasBody && expanded[index] == true
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasBody) {
                        Modifier.clickable { expanded[index] = !isExpanded }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val turnNo = row.turnNumber?.value ?: (index + 1)
            val durationText = row.durationMs?.let { formatDuration(it) } ?: "—"
            val tokensText = row.tokensTotal?.let { formatTokenCountLong(it) } ?: "—"
            Text(
                text = stringResource(R.string.chat_stats_turn_row, turnNo, durationText, tokensText),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (hasBody) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (isExpanded) R.string.chat_stats_collapse else R.string.chat_stats_expand
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isExpanded) {
            Column(
                modifier = Modifier.padding(
                    start = SpacingTokens.MD.dp,
                    bottom = SpacingTokens.SM.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StatRow(stringResource(R.string.chat_stats_input), row.tokensInput?.let { webFormatTokens(it) })
                StatRow(stringResource(R.string.chat_stats_output), row.tokensOutput?.let { webFormatTokens(it) })
                StatRow(stringResource(R.string.chat_stats_reasoning), row.tokensReasoning?.let { webFormatTokens(it) })
                StatRow(stringResource(R.string.chat_stats_cache_read), row.tokensCacheRead?.let { webFormatTokens(it) })
                StatRow(stringResource(R.string.chat_stats_cache_write), row.tokensCacheWrite?.let { webFormatTokens(it) })
                StatRow(stringResource(R.string.chat_stats_ttft), row.ttftMs?.let { formatDuration(it) })
                StatRow(
                    stringResource(R.string.chat_stats_tokens_per_second),
                    row.tokensPerSecond?.let { "%.1f".format(it) },
                )
                StatRow(stringResource(R.string.chat_stats_steps), row.stepCount.toString())
                StatRow(stringResource(R.string.chat_stats_tools), row.toolCallCount.toString())
                StatRow(stringResource(R.string.chat_stats_cost), row.cost?.let { String.format(java.util.Locale.US, "$%.4f", it) })
                StatRow(
                    stringResource(R.string.chat_stats_model),
                    listOfNotNull(row.providerId, row.modelId)
                        .joinToString(" · ")
                        .ifBlank { null },
                )
            }
        }
    }
}

/** 展开体单行（label 左 / value 右）；[value] 为 null 整项不渲染。 */
@Composable
private fun StatRow(label: String, value: String?) {
    if (value == null) return
    Row(modifier = Modifier.fillMaxWidth()) {
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

/** DSH contextBreakdown 图例行（swatch + 标签 + ~值）。 */
@Composable
private fun BreakdownLegendRow(color: Color, label: String, tokens: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "~" + webFormatTokens(tokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
