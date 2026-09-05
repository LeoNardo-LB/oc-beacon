package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TurnDeliverables
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.PathUtils

/**
 * #311 Task4 deliverables——turn 尾产出文件行（气泡下方，台账行之后）。
 *
 * 数据 = [RenderableTurn.deliverableFiles] 纯投影（TurnDeliverables fold，
 * 契约 ②）；呈现 = 「Produced」弱化标签 + 文件名 chips（basename 展示、
 * 点击整路径打开、chip 上限 6 + 「+N」余量计数——web mod28 ProducedFiles
 * 同构；web 的容器宽度自适应折叠在 Compose 无对应物，取固定上限，偏差
 * 见 #311 任务报告）。产出为空不挂载（空不挂载，契约 ②）。
 */
@Composable
internal fun MaybeProducedFilesRow(
    turn: RenderableTurn?,
    onOpenFile: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (turn == null || turn.durationMs == null) return
    val files = turn.deliverableFiles
    if (files.isEmpty()) return
    ProducedFilesRow(files = files, onOpenFile = onOpenFile, modifier = modifier)
}

@Composable
private fun ProducedFilesRow(
    files: List<String>,
    onOpenFile: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_produced_files_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
        )
        val shown = files.take(TurnDeliverables.SHOWN_LIMIT)
        shown.forEach { path ->
            val a11y = stringResource(R.string.chat_produced_files_open, path)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = ShapeTokens.smallMedium,
                modifier = Modifier
                    .clip(ShapeTokens.smallMedium)
                    .clickable(enabled = onOpenFile != null) { onOpenFile?.invoke(path) }
                    .semantics { contentDescription = a11y },
            ) {
                Text(
                    text = PathUtils.fileName(path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SpacingTokens.SM.dp, vertical = 2.dp),
                    maxLines = 1,
                )
            }
        }
        val remainder = files.size - shown.size
        if (remainder > 0) {
            Text(
                text = stringResource(R.string.chat_produced_files_more, remainder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                maxLines = 1,
            )
        }
    }
}
