package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.unit.sp
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

    // 子代理类型（2026-08-12 用户要求：展示具体类型 general/explore 等）
    val agentType = (currentMessage.message as? Message.User)?.agent
        ?.takeIf { it.isNotBlank() }

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

    // 标签行（2026-08-12 用户要求组合）：时间 + "Background" + "Agent/Shell Completed" + 成功/失败图标
    val labelText = stringResource(R.string.chat_label_background)
    val statusLabel = when {
        info == null -> null
        isError -> stringResource(
            if (info.source == "shell") R.string.chat_background_shell_failed
            else R.string.chat_background_agent_failed
        )
        else -> stringResource(
            if (info.source == "shell") R.string.chat_background_shell_completed
            else R.string.chat_background_agent_completed
        )
    }

    // 标题行：任务描述（summary 去 "Background task completed/failed: " 前缀）
    val taskTitle = info?.summary?.let(::extractTaskDescription) ?: text

    var expanded by remember { mutableStateOf(false) }
    val hasNavArrow = sessionId != null
    val hasOutput = output != null

    // 标签行时间（同 user/assistant 标签行格式，左对齐最左边）
    val timeText = remember(currentMessage.message.time.created) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentMessage.message.time.created))
    }

    // 统一容器（MessageBubble）：标签栏 = 时间 + "Background" + "Agent/Shell Completed" + 状态图标
    // 操作按钮（展开/定位/跳转）统一放第 2 行（2026-08-12 用户要求）
    MessageBubble(
        alignEnd = false,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isError) AgentError.copy(alpha = AlphaTokens.MEDIUM)
            else MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
        ),
        shape = ShapeTokens.medium,
        label = labelText,
        timeMs = currentMessage.message.time.created,
        labelSuffix = {
            // 状态文案 + 成功/失败图标（2026-08-12 用户要求组合）
            if (statusLabel != null) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isError) AgentError else AgentSuccess,
                    maxLines = 1
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = iconTint
                )
            }
        },
    ) {
        // 第 2 行（2026-08-12 用户要求）：子代理类型 + 标题 + [展开][定位][跳转]
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // 2026-08-12 用户要求：区分 agent/shell 通知——图标 + 类型文字
                        // agent（subagent/task）= AccountTree；shell = Terminal
                        Icon(
                            imageVector = if (info.source == "shell") {
                                Icons.Default.Terminal
                            } else {
                                Icons.Default.AccountTree
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (info.source == "shell") {
                                stringResource(R.string.tool_terminal)
                            } else {
                                agentType?.replaceFirstChar { it.uppercase() }
                                    ?: stringResource(R.string.tool_sub_agent)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (taskTitle != text) {
                            Text(
                                text = "· $taskTitle",
                                style = CodeTypography,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
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
                        // 「定位发起卡片」：滚动到发起该任务的 TaskToolCard 位置
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
                        // 2026-08-12：箭头可点击 → 进入 subagent 子会话（与展开同栏）
                        // （shell 类通知无子会话 id → hasNavArrow=false → 无箭头）
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                            modifier = Modifier
                                .size(22.dp)
                                .clickable { onViewSubSession?.invoke(sessionId) }
                                .padding(3.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 展开输出（2026-08-12：tween 动画——spring 默认有回弹 overshoot，
                // 收起收尾会"跳一下"；tween 无回弹平滑收尾）
                AnimatedVisibility(
                    visible = hasOutput && expanded,
                    enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150))
                ) {
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
                            // 2026-08-12 用户要求：去掉 "Output summary" 文案——直接输出内容；
                            // agent 通知展示总结（截断足够）；shell 通知展示全部内容（不截断）
                            SelectionContainer {
                                MarkdownContent(
                                    markdown = if (info?.source == "shell") {
                                        output ?: ""
                                    } else {
                                        output?.take(2000) ?: ""
                                    },
                                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    isUser = false
                                )
                            }
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
    /** 通知来源类型（2026-08-12）："agent"（subagent/task 注入）/ "shell"（未来）/ null 未知 */
    val source: String? = null,
)

internal fun parseSyntheticTask(text: String): SyntheticTaskInfo? {
    // 兼容两种服务器 synthetic 格式（2026-08-12 修复）：
    // - 新版源码：<task id="..." state="..."><summary>...</summary><task_result|task_error>...</task_result></task>
    // - 运行中的旧版服务器：<subagent id="..." state="..." description="...">结果</subagent>
    //   旧格式没有 <summary>/<task_result> 标签——description 属性作摘要、标签正文作结果。
    //   修复前只认 <task>，<subagent> 格式解析失败 → 降级显示原始 XML 文本
    //   （用户反馈"主对话看不到通知提醒"的根因）。
    val taskMatch = Regex("""<(?:task|subagent|shell)\b[^>]*>""").find(text) ?: return null
    val taskTag = taskMatch.value
    val isSubagentTag = taskTag.startsWith("<subagent")
    // 来源类型（2026-08-12）：agent = subagent/task 注入；shell = shell 通知（未来）
    val source = when {
        isSubagentTag || taskTag.startsWith("<task") -> "agent"
        taskTag.startsWith("<shell") -> "shell"
        else -> null
    }
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
    return SyntheticTaskInfo(sessionId, state, summary, output, source)
}
