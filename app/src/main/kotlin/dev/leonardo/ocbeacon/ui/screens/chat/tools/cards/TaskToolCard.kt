package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TaskOutputFetch
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalTaskOutputFetcher
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Task（子 agent）工具卡片 —— 显示描述 + 子级信息。
 * 与 WebUI 类似：trigger = "Agent (task)" + 描述，content = 子工具列表。
 */
@Composable
internal fun TaskToolCard(
    tool: Part.Tool,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val inputAgentType = (input["subagent_type"] ?: input["agent"])
        ?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() }
    val metadataAgentName = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        is ToolState.Running -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        else -> null
    }
    val agentType = inputAgentType
        ?: metadataAgentName?.replaceFirstChar { it.uppercase() }
        ?: turnAgentName?.replaceFirstChar { it.uppercase() }
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()
    val longPressCopyText = description
        ?: agentType?.let { "$it Agent" }
        ?: serverTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tool_sub_agent)
    val subSessionId = when (val state = tool.state) {
            is ToolState.Completed -> state.metadata?.get("sessionId")
                ?: state.metadata?.get("sessionID")
                ?: state.metadata?.get("jobId")  // V2 服务器用 jobId 存子会话 ID（2026-08-11 实测）
                ?: state.metadata?.get("childID") // #180：synthetic 同源命名（V2Mappers 已归一，直读兜底）
            is ToolState.Running -> state.metadata?.get("sessionId")
                ?: state.metadata?.get("sessionID")
                ?: state.metadata?.get("jobId")
                ?: state.metadata?.get("childID") // #180：Running 期尽早可跳
            else -> null
        }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    // 确定点击行为：有子会话则导航到它，否则切换展开
    // #180：Running 期只要拿到子会话 id 即可跳转（原实现仅 completed 可点）
    val clickAction: (() -> Unit)? = if (subSessionId != null && onViewSubSession != null) {
        { onViewSubSession(subSessionId) }
    } else null

    // #180：导航箭头不再排除 isRunning（Running 有 id 即显示，尽早进子会话看进度）
    val showNavArrow = subSessionId != null && onViewSubSession != null

    ToolCardScaffold(
        icon = Icons.Default.AccountTree,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "", // 未使用，因为提供了 titleContent
        // #181：导航态不再牺牲复制按钮（chevron 并存后右侧空间足够）
        copyText = longPressCopyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = if (showNavArrow) true else hasOutput,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        // #181 根因修复：原 showExpandIcon = !showNavArrow 导致导航态下
        // chevron 消失 + 标题行点击被导航覆盖 → 展开入口完全消失。
        // 改为独立并存：标题行=导航，右侧 chevron IconButton=展开切换。
        showExpandIcon = true,
        onClick = clickAction,
        // 2026-08-11 用户反馈：subagent 卡片保持原背景（蓝底改动撤销——
        // "蓝色基调不用改"，原设计即为默认背景 + primary 蓝色图标）
        rightSideExtras = if (showNavArrow) {
            { Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        titleContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = stringResource(R.string.tool_sub_agent),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentType?.let { "$it Agent" }
                            ?: serverTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.tool_sub_agent),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = CodeTypography,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        ) {
            val halfScreenHeight = halfScreenHeight()
            val scrollState = rememberScrollState()

            // #182（2026-08-21）：展开时实时拉取全量输出（grilling Q13 定案：
            // part 优先 → 子会话 transcript 回退；DB 留 500 预览不变）。
            // 本地 output 是 SSE 累积或 DB 500 预览；拉取结果取长者渲染。
            val fetcher = LocalTaskOutputFetcher.current
            var fetchedOutput by remember(tool.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(isExpanded, tool.id) {
                if (isExpanded && fetcher != null && fetchedOutput == null) {
                    fetchedOutput = runCatching { fetcher(tool.id, subSessionId) }.getOrNull()
                }
            }
            val renderSource = remember(output, fetchedOutput) {
                TaskOutputFetch.pickLonger(output, fetchedOutput) ?: output
            }
            val truncated = renderSource.length > TaskOutputFetch.MAX_RENDER_CHARS
            val slices = remember(renderSource) {
                renderSource.take(TaskOutputFetch.MAX_RENDER_CHARS)
                    .chunked(TaskOutputFetch.SLICE_CHARS)
            }

        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.chat_task_output_summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    // #182：take(2000) 硬截断移除——分片渲染（单片 4K 字符
                    // 组合预算内），上限 20K 防巨型输出整棵组合
                    Column {
                        slices.forEach { slice ->
                            MarkdownContent(
                                markdown = slice,
                                textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer,
                                isUser = false
                            )
                        }
                        if (truncated) {
                            Text(
                                text = stringResource(R.string.chat_task_output_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
