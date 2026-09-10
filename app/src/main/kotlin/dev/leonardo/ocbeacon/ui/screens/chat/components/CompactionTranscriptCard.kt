package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionEntry
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * #378/#375 压缩转录卡（流内 box）——压缩实体在主对话流的时序位呈现。
 *
 * #389（2026-09-10 用户裁决）：弃 EventCard 族 tertiaryContainer box 容器，
 * 与 V1/V2 分割线路径（[CompactionCard]）一并改以思考卡（[ReasoningBlock]）为
 * 容器改文案复用——文案统一：进行中「正在压缩上下文…」/完成「上下文已压缩」/
 * 失败「压缩会话失败」+error 破色（原 chat_compaction_* 文案族退役）。
 * 全服务器类型压缩 UIUX 单一（ui-conventions 服务器类型交互统一铁律）。
 *
 * 沿袭不动：
 * - #375：压缩是主对话的一部分；进行中与完成均可展开收起，摘要到达即展开可读；
 * - #384：吸收的源命令结算文本（「Compacted 8 history items (~5373 tokens).」）
 *   以标题行下常驻行呈现（belowHeader 槽位），error 结算沿用失败色；
 * - 失败原因行常驻（排障语义优先）；
 * - 视口外丢弃后重新组合保持展开态（LazyColumn item state；rememberSaveable）；
 * - 表面载体抑制：压缩摘要的 user/message 载体气泡在 ChatScreen 上游按
 *   entry.messageId 过滤——内容由本卡独占承载，无双份。
 */
@Composable
internal fun CompactionTranscriptCard(
    entry: CompactionEntry,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(entry.compactionId) { mutableStateOf(false) }

    val failed = entry.isFailed
    val summary = entry.summaryText
    val canExpand = !summary.isNullOrBlank()
    val label = when {
        !entry.isFinished -> stringResource(R.string.chat_compressing_context_plain)
        failed -> stringResource(R.string.chat_session_compact_failed)
        else -> stringResource(R.string.chat_summarized)
    }

    Column(modifier = modifier) {
        ReasoningBlock(
            text = summary ?: "",
            isExpanded = expanded,
            onToggleExpand = { if (canExpand) expanded = !expanded },
            isStreaming = !entry.isFinished,
            headerOverride = label,
            accentOverride = if (failed) MaterialTheme.colorScheme.error else null,
            belowHeader = {
                if (failed && !entry.error.isNullOrBlank()) {
                    Text(
                        text = entry.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                val cmdDone = entry.commandDone
                if (!cmdDone?.text.isNullOrBlank()) {
                    Text(
                        text = cmdDone!!.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cmdDone.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        },
                        maxLines = 2,
                    )
                }
            },
        )
    }
}
