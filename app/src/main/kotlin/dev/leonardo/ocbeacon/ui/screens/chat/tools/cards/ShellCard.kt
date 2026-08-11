package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder

/**
 * 后台 shell 命令卡片（V2 Shell part）——2 行布局，与 [TaskToolCard] 对称：
 *
 * ```
 * ⚙ $ npm test                ← 第 1 行：Terminal 图标 + 命令（等宽）
 *    Running · > test:unit     ← 第 2 行：状态 + 输出摘要（单行省略）
 * ```
 * 点击切换展开，展开显示完整输出（与工具卡片一致）。
 */
@Composable
internal fun ShellCard(
    shell: Part.Shell,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val isRunning = shell.status == "running" || (shell.exit == null && shell.status.isBlank())
    val command = shell.command.trimStart('$', ' ').ifBlank { stringResource(R.string.tool_shell) }
    val output = shell.output.orEmpty()

    // 第 2 行状态文本：Running（primary 强调）→ Exit N / 完成 / 原始状态
    val statusText = when {
        isRunning -> stringResource(R.string.shell_status_running)
        shell.exit != null -> stringResource(R.string.shell_status_exit, shell.exit)
        shell.status.isNotBlank() -> shell.status
        else -> stringResource(R.string.shell_status_done)
    }
    val statusColor = if (isRunning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
    }

    // 第 2 行摘要：输出末行（running 时）或输出首行（结束后）
    val summary = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .let { lines ->
            if (isRunning) lines.lastOrNull() else lines.firstOrNull()
        }
        ?: ""

    ToolCardScaffold(
        icon = Icons.Default.Terminal,
        iconTint = MaterialTheme.colorScheme.primary,
        title = command,
        copyText = output.ifBlank { command },
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = output.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        titleContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = stringResource(R.string.tool_shell),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command,
                        style = CodeTypography,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                        if (summary.isNotBlank()) {
                            Text(
                                text = "· $summary",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    ) {
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.chat_shell_output_summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
                SelectionContainer {
                    Text(
                        text = output.ifBlank { statusText },
                        style = CodeTypography,
                        color = if (isAmoled) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }
        }
    }
}
