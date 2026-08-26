package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 轮次完成合成通知卡片（#67 synthetic 消息——后台 task/subagent/shell 完成注入）。
 *
 * opencode 服务器在后台 task/subagent 完成时向主会话注入 synthetic 消息
 * （type="synthetic" + 顶层 text；客户端实时经 session.input.promoted 接收，
 * 2026-08-12 与 TUI 机制对齐），text 为结构化格式：
 *   <task id="ses_xxx" state="completed|error"><summary>…</summary><task_result>…</task_result></task>
 *   <subagent id="ses_xxx" state="completed" description="…">结果</subagent>
 *   <shell id="…" state="…" description="…">输出</shell>
 *
 * **#234（2026-08-27）形态翻案声明**：本组件自 2026-08-12「独立气泡方案 A」
 * （#67 自有标签行/标题行/按钮行）迁移为统一事件卡 EventCard 的薄适配器——
 * 三种 SSE 事件元素共用严格同构模子（spec
 * docs/specs/2026-08-26-event-card-unification-design.md §1–§2），本组件只负责：
 * - synthetic 文本解析（[parseSyntheticTask]，解析层零改动——§6 守恒项）
 * - 参数表映射（标签/图标/描述行/展开正文/跳转/动作，spec §2 task/shell 列）
 *
 * 兼容性守恒：解析失败降级（Info 图标 + generic 标签 + 原文作描述行）、
 * agent 输出截断 2000 字符 / shell 全量、跳转箭头仅在子会话 id 存在时显示
 * （#216 入口守恒）、「定位发起卡片」进展开区动作位。
 */
@Composable
internal fun SyntheticNotificationCard(
    currentMessage: ChatMessage,
    eventExpandedStates: MutableMap<String, Boolean>,
    onViewSubSession: ((String) -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
) {
    val text = currentMessage.parts
        .filterIsInstance<Part.Text>()
        .firstOrNull { it.text.isNotBlank() }
        ?.text
        ?: return

    val info = remember(text) { parseSyntheticTask(text) }
    val isFailed = info?.state == "error"
    val output = info?.output?.takeIf { it.isNotBlank() }
    // 跳转子会话（#216）：仅当目标 id 与入口回调都存在时显示常驻箭头
    val navTargetId = info?.sessionId
        ?.takeIf { it.isNotBlank() && onViewSubSession != null }

    // Q8 来源图标（shell=Terminal / 其余=CheckCircle）；失败态图标由 EventCard 覆盖
    val sourceIcon = if (info?.source == "shell") Icons.Filled.Terminal else Icons.Filled.CheckCircle
    val unknownIcon = Icons.Outlined.Info

    // Q7 i18n 标签（chat_event_* 新家族）
    val label = when {
        info == null -> stringResource(R.string.chat_event_generic)
        isFailed -> stringResource(
            if (info.source == "shell") R.string.chat_event_shell_failed
            else R.string.chat_event_task_failed
        )
        else -> stringResource(
            if (info.source == "shell") R.string.chat_event_shell_completed
            else R.string.chat_event_task_completed
        )
    }

    // Q15 描述行：描述数据实际存在才激活——task=任务描述（identity 信息）、
    // shell=命令预览（description 属性）、解析失败降级=原始全文截断
    val description = remember(text) {
        if (info == null) {
            text
        } else {
            extractTaskDescription(info.summary).ifBlank { null }
        }
    }

    val timeMs = currentMessage.message.time.created

    EventCard(
        eventKey = currentMessage.message.id,
        timeMs = timeMs,
        label = label,
        leadingIcon = if (info == null) unknownIcon else sourceIcon,
        failed = isFailed,
        description = description,
        expandedStates = eventExpandedStates,
        navTargetId = navTargetId,
        onNavClick = { id -> onViewSubSession?.invoke(id) },
        bodyContent = output?.let { out ->
            // 展开正文：shell 全量；agent 截断 2000（历史行为守恒）
            @Composable {
                MarkdownContent(
                    markdown = if (info?.source == "shell") out else out.take(2000),
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    isUser = false,
                )
            }
        },
        actions = if (navTargetId != null && onLocateTask != null) {
            // Q4：「定位发起卡片」在展开区动作位（折叠态无此钮——spec §2）
            @Composable {
                TextButton(
                    onClick = { navTargetId?.let(onLocateTask) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = stringResource(R.string.a11y_locate_task),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_event_locate_task),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else null,
    )
}

// ---------------------------------------------------------------------------
// synthetic 文本解析（纯函数，#234 迁移零改动——"解析层零改动"守恒项；
// 单测 SyntheticTaskParserTest / ParseSyntheticTaskTest 同包直引下列符号）
// ---------------------------------------------------------------------------

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
    /** 通知来源类型（2026-08-12）："agent"（subagent/task 注入）/ "shell" / null 未知 */
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
    // 来源类型（2026-08-12）：agent = subagent/task 注入；shell = shell 通知
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
