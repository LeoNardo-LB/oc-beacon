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
 * 兼容性守恒与演进：解析失败降级（Info 图标 + generic 标签 + 原文作描述行）、
 * 展开正文全量渲染（V6 反馈「展示不完全」后取消 agent 2000 截断）、
 * 跳转箭头仅子智能体卡显示（V6 用户裁决 2026-08-27；shell/其他不给箭头）、
 * 属性别名兼容 id|sessionID 与 description|command（#240）、「定位发起卡片」进展开区动作位。
 */
@Composable
internal fun SyntheticNotificationCard(
    currentMessage: ChatMessage,
    eventExpandedStates: MutableMap<String, Boolean>,
    onViewSubSession: ((String) -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
    /** #243 连续同内容去重：本卡代表的被抑制重复数（0=无重复）。标签行显示 ×(N+1)。 */
    dupCount: Int = 0,
) {
    val text = currentMessage.parts
        .filterIsInstance<Part.Text>()
        .firstOrNull { it.text.isNotBlank() }
        ?.text
        ?: return

    val info = remember(text) { parseSyntheticTask(text) }
    // 失败态派生（2026-08-27 展示会话取证）：opencode 服务器对后台 shell **一律**
    // 发 state="completed"（连 exit 7 也是——原始 XML 实证），失败信号只落在
    // 正文尾部的「Command exited with code N.»。为让 Q5 严重度编码真正可达，
    // shell 卡在此从正文派生失败（非零退出码）；上游状态语义缺陷另行登记。
    val output = info?.output?.takeIf { it.isNotBlank() }
    val shellExitFailure = info?.source == "shell" &&
        output?.contains(Regex("Command exited with code [1-9]\\d*")) == true
    val isFailed = info?.state == "error" || shellExitFailure
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        dev.leonardo.ocbeacon.logging.AppLogger.w(
            "SynthDiag",
            "source=" + (info?.source ?: "null") + " state=" + (info?.state ?: "null") +
                " outLen=" + (output?.length ?: -1) + " exitFail=" + shellExitFailure
        )
    }
    // 跳转子会话（#216 + V6 用户裁决 2026-08-27）：只有子智能体/任务卡给常驻箭头——
    // shell 与其他事件不需要跳转；且 id 为工具调用前缀（call_）时不可当会话 id 跳转
    val navTargetId = info?.sessionId
        ?.takeIf { info.source == "agent" }
        ?.takeIf { !it.startsWith("call_") }
        ?.takeIf { it.isNotBlank() && onViewSubSession != null }

    // Q8 来源图标（shell=Terminal / 其余=CheckCircle）；失败态图标由 EventCard 覆盖
    val sourceIcon = if (info?.source == "shell") Icons.Filled.Terminal else Icons.Filled.CheckCircle
    val unknownIcon = Icons.Outlined.Info

    // Q7 i18n 标签（chat_event_* 新家族）
    // 2026-09-01（Task 3b 降级卡）：state="running"（DSH workflow run-start 信封）
    // 走 generic 标签——不误标"完成"；workflow 阶段卡落地后由专属标签接管。
    val labelBase = when {
        info == null -> stringResource(R.string.chat_event_generic)
        isFailed -> stringResource(
            if (info.source == "shell") R.string.chat_event_shell_failed
            else R.string.chat_event_task_failed
        )
        info.state == "running" -> stringResource(R.string.chat_event_generic)
        else -> stringResource(
            if (info.source == "shell") R.string.chat_event_shell_completed
            else R.string.chat_event_task_completed
        )
    }

    // #243 连续同内容去重：×N 后缀（N=含本卡的总出现次数）；重复卡不渲染
    val label = if (dupCount > 0) "$labelBase ×${dupCount + 1}" else labelBase

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
        bodyFontScale = 0.85f,
        bodyContent = output?.let { out ->
            // 展开正文全量渲染（V6 反馈「展示不完全」——原 agent 截断 2000 取消，
            // shell 本就全量；300dp 滚动区承载长度）。字号小一档（bodyFontScale）。
            @Composable {
                MarkdownContent(
                    markdown = out,
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
// #240 属性别名兼容（2026-08-27 真机实证）：旧格式 subagent 用 sessionID=、
// shell 用 command= 作描述。(?:\s|^) 前缀防 attrName 尾部子串误配（如 xxxId=）。
private val TASK_ID_ATTR_REGEX = Regex("""(?:\s|^)(?:id|sessionID)="([^"]*)"""")
private val TASK_STATE_ATTR_REGEX = Regex("""(?:\s|^)state="([^"]*)"""")
private val TASK_DESCRIPTION_ATTR_REGEX = Regex("""(?:\s|^)(?:description|command)="([^"]*)"""")
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

// ---------------------------------------------------------------------------
// #243 连续同内容去重（2026-08-27 用户裁决：完全相同内容不重复渲染，首张 + ×N）
// ---------------------------------------------------------------------------

/**
 * 去重键：仅 shell 合成卡参与（task/subagent 卡携带子会话跳转载荷，永不折叠）。
 * 键 = source|state|描述|输出——call_ 工具调用 id 等易变字段不参与，
 * 因此「同一命令跑 N 次」产生的 N 张卡同键。
 */
internal fun syntheticDedupKey(text: String): String? {
    val info = parseSyntheticTask(text) ?: return null
    if (info.source != "shell") return null
    return listOf(info.source, info.state ?: "", info.summary ?: "", info.output ?: "")
        .joinToString("\u0001")
}

/**
 * 连续同键 shell 卡去重（纯函数，JVM 可测）：首张保留并计数，后续抑制。
 * 返回 (过滤后列表, 保留消息 id → 被抑制数)。只折叠连续同键——被其他消息
 * 隔开的同内容卡不算重复。
 */
internal fun <F> dedupeConsecutiveSynthetics(
    items: List<Pair<F, ChatMessage>>,
): Pair<List<Pair<F, ChatMessage>>, Map<String, Int>> {
    val suppressed = HashSet<String>()
    val counts = LinkedHashMap<String, Int>()
    var lastKey: String? = null
    var lastKeptId: String? = null
    for ((first, msg) in items) {
        val key = if (msg.isSynthetic) {
            val text = msg.parts.filterIsInstance<Part.Text>().firstOrNull { it.text.isNotBlank() }?.text
            text?.let(::syntheticDedupKey)
        } else {
            null
        }
        if (key != null && key == lastKey && lastKeptId != null) {
            suppressed.add(msg.message.id)
            counts[lastKeptId] = (counts[lastKeptId] ?: 0) + 1
        } else {
            lastKey = key
            lastKeptId = msg.message.id
        }
    }
    return items.filter { it.second.message.id !in suppressed } to counts
}
