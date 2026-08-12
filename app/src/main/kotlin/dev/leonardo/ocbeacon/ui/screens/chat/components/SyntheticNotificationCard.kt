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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AgentInfo
import dev.leonardo.ocbeacon.ui.theme.AgentSuccess
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台任务完成通知卡片（#67 synthetic 消息）。
 *
 * opencode 服务器在后台 task/subagent 完成时向主会话注入 synthetic 消息
 * （type="synthetic" + 顶层 text；客户端实时经 session.input.promoted 接收，
 * 2026-08-12 与 TUI 机制对齐），text 为结构化格式：
 *   <task id="ses_xxx" state="completed|error"><summary>…</summary><task_result>…</task_result></task>
 *   <subagent id="ses_xxx" state="completed" description="…">结果</subagent>
 *
 * 渲染（2026-08-12 用户决策：独立气泡方案 A）：
 * - synthetic 是**独立消息**，独立气泡渲染（不再嵌入 assistant turn）
 * - 气泡结构与 assistant 同构：左对齐 + surfaceVariant 底 + ShapeTokens.medium 圆角
 * - **区别点**：顶部"系统通知"标签行（状态图标 绿✓/红✗/蓝ℹ + primary 标签色）
 * - 内容：标题行（Sub-agent · 任务描述）+ 状态行（Task completed/failed · 结果摘要）
 * - 右侧操作：展开输出 / 定位发起卡片 / 跳转子会话
 * - 页脚：时间（HH:mm，同 assistant 页脚格式）
 * - 解析失败 fallback：Info 图标 + 全文（无状态行/跳转/展开）
 */
@Composable
internal fun SyntheticNotificationCard(
    currentMessage: ChatMessage,
    isAmoled: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
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
    // 「定位发起卡片」按钮（2026-08-11 用户要求）：有子会话 id 即显示，
    // 点击后由 ChatMessageList 在消息流中查找发起卡片（TaskToolCard 的
    // metadata.sessionId）并滚动+高亮；找不到时提示。
    val canLocate = sessionId != null && onLocateTask != null

    // 第 1 行主标题：任务描述（summary 去 "Background task completed/failed: " 前缀）
    val taskTitle = info?.summary?.let(::extractTaskDescription) ?: text
    // 第 2 行状态（与 ShellCard 的 "Running · 输出摘要" 同构）
    val statusText = when {
        info == null -> null
        isError -> stringResource(R.string.chat_task_failed)
        else -> stringResource(R.string.chat_task_completed)
    }
    val statusColor = when {
        info == null -> null
        isError -> AgentError
        else -> AgentSuccess
    }
    val outputSummary = output?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.firstOrNull()

    var expanded by remember { mutableStateOf(false) }
    val hasNavArrow = sessionId != null
    val hasOutput = output != null

    // 页脚时间（同 assistant 页脚格式，MessageCardAssistant HH:mm）
    val timeText = remember(currentMessage.message.time.created) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentMessage.message.time.created))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (isAmoled) AmoledDefaultBorder else null,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = SpacingTokens.LG.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 顶部标签行：状态图标 + "系统通知" + 右侧操作（展开/定位/跳转）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconTint
                    )
                    Text(
                        text = stringResource(R.string.chat_system_notification),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (hasOutput) {
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_expand),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                            )
                        }
                    }
                    if (canLocate) {
                        IconButton(
                            onClick = { onLocateTask?.invoke(sessionId) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = stringResource(R.string.a11y_locate_task),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                            )
                        }
                    }
                    if (hasNavArrow) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 标题行：Sub-agent · 任务描述（TaskToolCard 同款标题行）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (info == null) {
                        // fallback：直接显示原文（无任务格式）
                        Text(
                            text = text,
                            style = CodeTypography,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.tool_sub_agent),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (taskTitle != text) {
                            Text(
                                text = "· $taskTitle",
                                style = CodeTypography,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 状态行：Task completed/failed · 结果摘要（ShellCard 同款）
                if (statusText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (outputSummary != null) {
                            Text(
                                text = "· $outputSummary",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 展开输出（点击标签行右侧展开按钮）
                if (hasOutput && expanded) {
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

                // 页脚：时间（同 assistant 页脚，右对齐）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }
        }
    }
}

/** 从 summary 提取任务描述：去 "Background task completed/failed: " 前缀。 */
internal fun extractTaskDescription(summary: String?): String {
    val s = summary?.trim() ?: return ""
    val stripped = Regex(
        "^Background task (?:completed|failed):\\s*",
        RegexOption.IGNORE_CASE
    ).replaceFirst(s, "").trim()
    return stripped.ifBlank { s }
}

/** 解析服务器 synthetic 文本的 <task> 结构化格式。解析失败返回 null。 */
internal data class SyntheticTaskInfo(
    val sessionId: String?,
    val state: String?,
    val summary: String?,
    val output: String?,
)

internal fun parseSyntheticTask(text: String): SyntheticTaskInfo? {
    // 兼容两种服务器 synthetic 格式（2026-08-12 修复）：
    // - 新版源码：<task id="..." state="..."><summary>...</summary><task_result|task_error>...</task_result></task>
    // - 运行中的旧版服务器：<subagent id="..." state="..." description="...">结果</subagent>
    //   旧格式没有 <summary>/<task_result> 标签——description 属性作摘要、标签正文作结果。
    //   修复前只认 <task>，<subagent> 格式解析失败 → 降级显示原始 XML 文本
    //   （用户反馈"主对话看不到通知提醒"的根因）。
    val taskMatch = Regex("""<(?:task|subagent)\b[^>]*>""").find(text) ?: return null
    val taskTag = taskMatch.value
    val isSubagentTag = taskTag.startsWith("<subagent")
    val sessionId = Regex("""id="([^"]*)"""").find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    // state 决定完成/失败语义与色彩——缺失则视为无效格式（fallback 纯文本）
    val state = Regex("""state="([^"]*)"""").find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
    val summary = if (isSubagentTag) {
        Regex("""description="([^"]*)"""").find(taskTag)?.groupValues?.get(1)?.trim()
    } else {
        Regex(
            """<summary>(.*?)</summary>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(text)?.groupValues?.get(1)?.trim()
    }
    val output = if (isSubagentTag) {
        // 正文 = 开标签与 </subagent> 之间的文本
        val closeIdx = text.indexOf("</subagent>")
        val bodyStart = taskMatch.range.last + 1
        if (closeIdx > bodyStart) {
            text.substring(bodyStart, closeIdx).trim().takeIf { it.isNotBlank() }
        } else null
    } else {
        val outputTag = if (state == "error") "task_error" else "task_result"
        Regex(
            """<$outputTag>(.*?)</$outputTag>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    return SyntheticTaskInfo(sessionId, state, summary, output)
}
