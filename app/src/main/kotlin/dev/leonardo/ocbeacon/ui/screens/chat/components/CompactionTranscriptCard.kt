package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionEntry

/**
 * #378/#375 压缩转录卡（DSH 转录实体）——参数适配到 [CompactionNoticeCard]
 *（#389 二轮终型：透明底＋外框通知卡；全服务器类型单一视觉，
 * ui-conventions 服务器类型交互统一铁律）。
 *
 * 沿袭：#375 压缩是主对话一部分、双态可展开；#384 吸收的源命令结算文本
 *（「Compacted 8 history items (~5373 tokens).」）以支持行呈现（error 结算
 * 沿用失败色）；失败原因行常驻；rememberSaveable 视口外回来保持展开；
 * 载体气泡上游抑制无双份。
 */
@Composable
internal fun CompactionTranscriptCard(
    entry: CompactionEntry,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(entry.compactionId) { mutableStateOf(false) }

    val failed = entry.isFailed
    val title = when {
        !entry.isFinished -> stringResource(R.string.chat_compressing_context_plain)
        failed -> stringResource(R.string.chat_session_compact_failed)
        else -> stringResource(R.string.chat_summarized)
    }
    val cmdDone = entry.commandDone

    CompactionNoticeCard(
        title = title,
        active = !entry.isFinished,
        failed = failed,
        errorText = entry.error?.takeIf { it.isNotBlank() },
        resultText = cmdDone?.text?.takeIf { it.isNotBlank() },
        resultIsError = cmdDone?.isError == true,
        body = entry.summaryText,
        expanded = expanded,
        onToggle = {
            if (!entry.summaryText.isNullOrBlank()) expanded = !expanded
        },
        modifier = modifier,
    )
}
