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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
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
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 轮次完成合成通知卡片（#67 synthetic 消息——后台 task/subagent 完成注入）。
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
 * - **区别点**：顶部标签行（状态图标 绿✓/红✗/蓝ℹ + primary 标签色）
 * - 内容：标题行（Subagent · 任务描述）+ 状态行（Task completed/failed · 结果摘要）
 * - 右侧操作：展开输出 / 定位发起卡片 / 跳转子智能体会话
 * - 页脚：时间（HH:mm，同 assistant 页脚格式）
 * - 解析失败降级：Info 图标 + 全文（无状态行/跳转/展开）
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

    // 子智能体类型（2026-08-12 用户要求：展示具体类型 general/explore 等）
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
    // 「定位发起卡片」按钮（2026-08-11 用户要求）：有子智能体会话 id 即显示，
    // 点击后由 ChatMessageList 在消息流中查找发起卡片（TaskToolCard 的
    // metadata.sessionId）并滚动+高亮；找不到时提示。
    val canLocate = sessionId != null && onLocateTask != null

    // 标签行（2026-08-12 用户要求组合）：时间 + "Tasks" + "Agent/Shell Completed" + 成功/失败图标
    val labelText = stringResource(R.string.chat_label_tasks)
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
        DateFormatters.timeOnly().format(Date(currentMessage.message.time.created))
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
        // 2026-08-16（标题栏规范·类型图标）：合成通知=Notifications
        //（labelSuffix 的状态图标 ✓/✗ 保持不变——类型与状态分离）
        labelLeading = {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Notifications,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
            )
        },
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
        // 第 2 行（2026-08-12 用户要求）：子智能体类型 + 标题 + [定位][跳转]
                // #215 批3：标题行本体点击=展开/收起（有输出时；推翻 08-16 职责分离
                // 规范，与 scaffold 家族统一契约），右侧展开钮移除，跳转/定位钮保留
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hasOutput) { expanded = !expanded }
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
                    // 2026-08-12 用户要求：展开按钮与跳转按钮位置对调——
                    // 顺序 [跳转][定位][展开]（跳转最左、展开最右）
                    if (hasNavArrow) {
                        // 跳转：进入 subagent 子智能体会话（shell 类通知无子智能体会话 id → 无箭头）
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
                    // #215 批3：展开钮移除——本体点击已承担展开/收起
                }

                // 展开输出（2026-08-12 注释存档：曾用 tween——spring 回弹问题，
                // 现高度动画恢复默认；#215 验收反馈·一：视口稳定见 toggleAnchorCorrection）
                AnimatedVisibility(
                    visible = hasOutput && expanded,
                    enter = fadeIn(animationSpec = tween(150)) + expandVertically(),
                    exit = fadeOut(animationSpec = tween(150)) + shrinkVertically()
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

// #106-4：synthetic 解析正则——顶层预编译（原每条通知渲染现场编译）
private val BACKGROUND_TASK_PREFIX_REGEX = Regex(
    "^Background task (?:completed|failed):\\s*",
    RegexOption.IGNORE_CASE
)
private val TASK_TAG_REGEX = Regex("""<(?:task|subagent|shell)\b[^>]*>""")
private val TASK_ID_ATTR_REGEX = Regex("""id="([^"]*)"""")
private val TASK_STATE_ATTR_REGEX = Regex("""state="([^"]*)"""")
private val TASK_DESCRIPTION_ATTR_REGEX = Regex("""description="([^"]*)"""")
private val TASK_SUMMARY_REGEX = Regex("""<summary>(.*?)</summary>""", RegexOption.DOT_MATCHES_ALL)
private val TASK_RESULT_TAG_REGEX = Regex("""<task_result>(.*?)</task_result>""", RegexOption.DOT_MATCHES_ALL)
private val TASK_ERROR_TAG_REGEX = Regex("""<task_error>(.*?)</task_error>""", RegexOption.DOT_MATCHES_ALL)

/** 从 summary 提取任务描述：去 "Background task completed/failed: " 前缀。 */
internal fun extractTaskDescription(summary: String?): String {
    val s = summary?.trim() ?: return ""
    val stripped = BACKGROUND_TASK_PREFIX_REGEX.replaceFirst(s, "").trim()
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
    val taskMatch = TASK_TAG_REGEX.find(text) ?: return null
    val taskTag = taskMatch.value
    val isSubagentTag = taskTag.startsWith("<subagent")
    // 2026-08-12 修复：<shell> 标签同 <subagent>——正文在标签之间（非 task_result 包裹）
    val isBodyTag = isSubagentTag || taskTag.startsWith("<shell")
    // 来源类型（2026-08-12）：agent = subagent/task 注入；shell = shell 通知（未来）
    val source = when {
        isSubagentTag || taskTag.startsWith("<task") -> "agent"
        taskTag.startsWith("<shell") -> "shell"
        else -> null
    }
    val sessionId = TASK_ID_ATTR_REGEX.find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    // state 决定完成/失败语义与色彩——缺失则视为无效格式（fallback 纯文本）
    val state = TASK_STATE_ATTR_REGEX.find(taskTag)
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
    val summary = if (isBodyTag) {
        TASK_DESCRIPTION_ATTR_REGEX.find(taskTag)?.groupValues?.get(1)?.trim()
    } else {
        TASK_SUMMARY_REGEX.find(text)?.groupValues?.get(1)?.trim()
    }
    val output = if (isBodyTag) {
        // 正文 = 开标签与对应闭合标签之间的文本（subagent/shell 都是标签间正文）
        val closeTag = if (isSubagentTag) "</subagent>" else "</shell>"
        val closeIdx = text.indexOf(closeTag)
        val bodyStart = taskMatch.range.last + 1
        if (closeIdx > bodyStart) {
            text.substring(bodyStart, closeIdx).trim().takeIf { it.isNotBlank() }
        } else null
    } else {
        val outputRegex = if (state == "error") TASK_ERROR_TAG_REGEX else TASK_RESULT_TAG_REGEX
        outputRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    return SyntheticTaskInfo(sessionId, state, summary, output, source)
}
