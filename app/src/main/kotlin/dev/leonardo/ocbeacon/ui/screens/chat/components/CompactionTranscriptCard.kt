package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionEntry
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #378/#375 压缩转录卡（流内 box）——压缩实体在主对话流的时序位呈现。
 *
 * 用户裁决（#375）：压缩是主对话的一部分（非固定底部分割线）；进行中与完成
 * 均可展开收起；进行中 SSE 流式摘要先按 indeterminate（wire 摘要单帧到达，
 * 无流式 token 契约——journal 378-380-wire §三.4），摘要到达即展开可读。
 *
 * - 折叠态：状态行（图标 + 「会话压缩」+ 状态标签 + 展开箭头）——EventCard
 *   族模子（CommandFeedbackCard 同款容器语言：成功/信息中性，仅失败破色）；
 * - 展开态：摘要全文 Markdown（[MarkdownContent] asyncParse——非流式内容走
 *   后台解析路径，SSE 铁律不受触碰）；失败压缩附错误原因行。
 * - 表面载体抑制：压缩摘要的 user/message 载体气泡在 ChatScreen 上游按
 *   entry.messageId 过滤——内容由本卡独占承载，无双份。
 */
@Composable
internal fun CompactionTranscriptCard(
    entry: CompactionEntry,
    modifier: Modifier = Modifier,
) {
    // 视口外丢弃后重新组合保持展开态（LazyColumn item state；rememberSaveable）
    var expanded by rememberSaveable(entry.compactionId) { mutableStateOf(false) }

    val failed = entry.isFailed
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
        !entry.isFinished -> stringResource(R.string.chat_compaction_in_progress)
        failed -> stringResource(R.string.chat_compaction_failed)
        else -> stringResource(R.string.chat_compaction_done)
    }
    val expandA11y = stringResource(R.string.chat_compaction_expand)
    val runningA11y = stringResource(R.string.chat_compaction_in_progress)

    Surface(
        color = containerColor,
        shape = ShapeTokens.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.MD.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !entry.isFinished -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(14.dp)
                            .semantics { contentDescription = runningA11y },
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
                Text(
                    text = stringResource(R.string.chat_compaction_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(SpacingTokens.SM.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                    contentDescription = expandA11y,
                    modifier = Modifier.size(16.dp),
                    tint = secondaryColor,
                )
            }
            // 失败原因（终态行内常驻——折叠态也可见，排障语义优先）
            if (failed && !entry.error.isNullOrBlank()) {
                Text(
                    text = entry.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // #384：吸收的源命令结算文本（「Compacted 8 history items (~5373 tokens).」）
            // ——单卡承载 title+状态+结算+摘要全文；error 结算沿用失败色。
            val cmdDone = entry.commandDone
            if (!cmdDone?.text.isNullOrBlank()) {
                Text(
                    text = cmdDone!!.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cmdDone.isError) MaterialTheme.colorScheme.error else secondaryColor,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 摘要全文（进行中到达即可读；双态均可展开收起——用户裁决）
            val summary = entry.summaryText
            if (summary != null) {
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = SpacingTokens.SM.dp)) {
                        MarkdownContent(
                            markdown = summary,
                            textColor = contentColor,
                            isUser = false,
                            asyncParse = true,
                        )
                    }
                }
            }
        }
    }
}
