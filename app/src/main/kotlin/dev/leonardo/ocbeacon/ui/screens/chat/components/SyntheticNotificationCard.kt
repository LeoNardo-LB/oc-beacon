package dev.leonardo.ocbeacon.ui.screens.chat.components

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AgentInfo
import dev.leonardo.ocbeacon.ui.theme.AgentSuccess
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 后台任务完成通知卡片（#67 synthetic 消息，2026-08-11 重新设计）。
 *
 * opencode 服务器在后台 task/subagent 完成时向主会话注入 synthetic 消息
 * （REST GET /message 的 type="synthetic" + 顶层 text；无 SSE 事件），
 * text 为结构化格式：
 *   <task id="ses_xxx" state="completed|error">
 *   <summary>Background task completed: <描述></summary>
 *   <task_result|task_error>…输出…</task_result|task_error>
 *   </task>
 *
 * 设计目标（与发起卡片 TaskToolCard 对称，用户 2026-08-11 要求）：
 * - 同一 ToolCardScaffold 视觉语言，左对齐卡片，不再用独立居中条
 * - 状态色彩/图标区分：完成 = 绿色 CheckCircle，失败 = 红色 ErrorOutline
 * - 标题 = "Task completed/failed"，描述 = summary（含发起时 description，
 *   与发起卡片呼应）
 * - 右侧：解析出 subagent sessionID → 导航箭头跳转子会话
 *   （与 TaskToolCard 行为一致——"开始卡片"与"结束卡片"互相引用）
 * - 有输出时可展开查看完整内容（同 TaskToolCard 交互）
 * - 解析失败 fallback：Info 图标 + 全文（无跳转/展开）
 */
@Composable
internal fun SyntheticNotificationCard(
    currentMessage: ChatMessage,
    isAmoled: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
) {
    val text = currentMessage.parts
        .filterIsInstance<Part.Text>()
        .firstOrNull { it.text.isNotBlank() }
        ?.text
        ?: (currentMessage.message as? Message.User)?.summary?.body
        ?: return

    val info = remember(text) { parseSyntheticTask(text) }
    val isError = info?.state == "error"
    val output = info?.output?.takeIf { it.isNotBlank() }
    val sessionId = info?.sessionId
        ?.takeIf { it.isNotBlank() && onViewSubSession != null }

    val icon = when {
        info == null -> Icons.Outlined.Info
        isError -> Icons.Outlined.ErrorOutline
        else -> Icons.Filled.CheckCircle
    }
    val iconTint = when {
        info == null -> MaterialTheme.colorScheme.primary
        isError -> AgentError
        else -> AgentSuccess
    }
    // 状态底色语义（2026-08-11 用户要求）：完成=绿底 / 失败=红底 /
    // 未知=默认；与发起卡片（TaskToolCard 蓝底）形成三色体系。
    val containerColor = when {
        info == null -> MaterialTheme.colorScheme.surface
        isError -> AgentError.copy(alpha = AlphaTokens.SELECTED)
        else -> AgentSuccess.copy(alpha = AlphaTokens.SELECTED)
    }

    val title = when {
        info == null -> text
        isError -> stringResource(R.string.chat_task_failed)
        else -> stringResource(R.string.chat_task_completed)
    }
    val summary = info?.summary?.takeIf { it.isNotBlank() }

    var expanded by remember { mutableStateOf(false) }
    val hasNavArrow = sessionId != null
    val hasOutput = output != null

    ToolCardScaffold(
        icon = icon,
        iconTint = iconTint,
        title = title,
        copyText = text,
        isExpanded = expanded,
        isRunning = false,
        hasContent = hasOutput,
        isAmoled = isAmoled,
        onToggleExpand = { expanded = !expanded },
        containerColor = containerColor,
        onClick = if (hasNavArrow) {
            { onViewSubSession?.invoke(sessionId) }
        } else null,
        showExpandIcon = !hasNavArrow,
        rightSideExtras = if (hasNavArrow) {
            {
                Icon(
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
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = iconTint
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (summary != null && summary != title) {
                        Text(
                            text = summary,
                            style = CodeTypography,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    ) {
        if (hasOutput) {
            val halfScreenHeight = halfScreenHeight()
            val scrollState = rememberScrollState()
            Surface(
                shape = ShapeTokens.extraSmall,
                color = toolOutputContainerColor(),
                modifier = Modifier
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
                        MarkdownContent(
                            markdown = output.take(2000),
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            isUser = false
                        )
                    }
                }
            }
        }
    }
}

/** 解析服务器 synthetic 文本的 <task> 结构化格式。解析失败返回 null。 */
internal data class SyntheticTaskInfo(
    val sessionId: String?,
    val state: String?,
    val summary: String?,
    val output: String?,
)

internal fun parseSyntheticTask(text: String): SyntheticTaskInfo? {
    val taskMatch = Regex("""<task\b[^>]*>""").find(text) ?: return null
    val taskTag = taskMatch.value
    val sessionId = Regex("""id="([^"]*)"""").find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    // state 决定完成/失败语义与色彩——缺失则视为无效格式（fallback 纯文本）
    val state = Regex("""state="([^"]*)"""").find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
    val summary = Regex(
        """<summary>(.*?)</summary>""",
        RegexOption.DOT_MATCHES_ALL
    ).find(text)?.groupValues?.get(1)?.trim()
    val outputTag = if (state == "error") "task_error" else "task_result"
    val output = Regex(
        """<$outputTag>(.*?)</$outputTag>""",
        RegexOption.DOT_MATCHES_ALL
    ).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    return SyntheticTaskInfo(sessionId, state, summary, output)
}
