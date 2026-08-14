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
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/** #135（D2-L43）：ANSI 转义序列剥离正则——顶层预编译（原每次重组现场编译）。 */
private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

/**
 * Bash 工具卡片 —— 显示 $ 命令 + 输出（2 行布局，2026-08-11 用户要求：
 * 与 ShellCard / TaskToolCard 视觉统一——subagent 与 shell 都可后台）：
 *
 * ```
 * ⚙ $ npm test                ← 第 1 行：Terminal 图标 + 命令（等宽）
 *    Running · > test:unit     ← 第 2 行：状态 + 输出摘要（单行省略）
 * ```
 * 点击切换展开，展开显示完整输出（与工具卡片一致）。
 */
@Composable
internal fun BashToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val cleanedOutput = output.replace(ANSI_ESCAPE_REGEX, "")
    val displayText = buildString {
        if (command.isNotBlank()) {
            append("$ $command")
        }
        if (cleanedOutput.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(cleanedOutput.take(5000))
        }
    }

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = command.isNotBlank() || output.isNotBlank()

    // 第 2 行状态文本（对齐 ShellCard：Running / Exit N / Done）
    val statusText = when {
        isRunning -> stringResource(R.string.shell_status_running)
        isError -> stringResource(R.string.shell_status_failed)
        else -> stringResource(R.string.shell_status_done)
    }
    val statusColor = if (isRunning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
    }
    // 第 2 行摘要：输出末行（running 时）或输出首行（结束后）
    val summary = cleanedOutput.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .let { lines ->
            if (isRunning) lines.lastOrNull() else lines.firstOrNull()
        }
        ?: ""

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.Terminal,
        iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = serverTitle ?: stringResource(R.string.tool_shell),
        copyText = displayText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasContent,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        titleContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.Terminal,
                    contentDescription = stringResource(R.string.tool_shell),
                    modifier = Modifier.size(16.dp),
                    tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    // 第 1 行：命令（对齐 ShellCard 命令行）
                    Text(
                        text = if (command.isNotBlank()) "$ $command" else (serverTitle ?: stringResource(R.string.tool_shell)),
                        style = CodeTypography,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 第 2 行：状态 + 输出摘要（对齐 ShellCard 状态行）
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
            border = if (isAmoled) AmoledDefaultBorder else null,            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = displayText,
                    style = CodeTypography.copy(color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier
                        .padding(4.dp)
                        .codeHorizontalScroll()
                )
            }
        }
    }
}
