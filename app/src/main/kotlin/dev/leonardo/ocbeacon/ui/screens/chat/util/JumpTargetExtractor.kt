package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/** 可从快速导航面板跳转的用户问题。 */
data class JumpTarget(
    val label: String,        // "Q1"、"Q2" ...
    val timestampMs: Long,    // message.time.created（epoch 毫秒）
    val preview: String,      // 第一个 Part.Text 内容，或回退 summary.body
    val msgId: String         // message.id，用于跳转查找与当前高亮匹配
)

/**
 * 从 Room 全量 user 消息（[MessageWithParts]）提取跳转目标。
 *
 * 数据源为 [dev.leonardo.ocbeacon.data.local.MessageStore.userMessages]
 * （热表 role='user'，最多 1000 条），覆盖内存窗口外的更早历史。
 * synthetic（role='synthetic'）已在 SQL 层排除，此处 `role != "synthetic"`
 * 为双保险。按 created 升序，Q1 = 最旧。
 *
 * 排除 synthetic 用户消息：它们会并入 assistant turn 组或被过滤，不在
 * displayItems 中独立存在，跳转时 indexOfFirst 找不到目标会静默失败
 * （2026-08-12 用户反馈"快速定位有些 item 点击没反应"的根因）。
 *
 * 排除空壳 user 消息（2026-08-12 用户反馈"很多 item 没有内容"根因）：
 * 服务器历史遗留/已删除消息在 Room 保留记录但无 parts 且无 summary.body
 *（实测主会话 62 条 user 中 23 条为空壳，服务器单条查询 404）——
 * 无任何可展示文本的 item 直接跳过，不进入快速导航列表。
 *
 * 纯函数 —— 无 Android/Compose 依赖。
 *
 * @param noTextPlaceholder 保留参数（兼容调用方）；空壳消息将被过滤而非显示占位符。
 */
fun extractJumpTargets(
    messages: List<MessageWithParts>,
    noTextPlaceholder: String = "(无文本)",
): List<JumpTarget> {
    return messages
        .filter { it.info is Message.User && it.info.role != "synthetic" }
        .sortedBy { it.info.time.created }
        .mapNotNull { mwp ->
            val preview = mwp.parts.firstOrNull { it is Part.Text }
                ?.let { (it as Part.Text).text }
                ?.takeIf { it.isNotBlank() }
                ?: (mwp.info as? Message.User)?.summary?.body
                    ?.takeIf { it.isNotBlank() }
            preview?.let { p ->
                mwp to p
            }
        }
        .mapIndexed { i, (mwp, preview) ->
            JumpTarget(
                label = "Q${i + 1}",
                timestampMs = mwp.info.time.created,
                preview = preview,
                msgId = mwp.info.id,
            )
        }
}

/**
 * 给定一个 rawIndex（任意消息），找到其上或其本身最近的用户消息。
 * 返回其 rawIndex，不存在则返回 null。纯函数。
 */
fun findNearestUserIndexBefore(rawMessages: List<ChatMessage>, rawIdx: Int): Int? {
    if (rawIdx < 0 || rawIdx >= rawMessages.size) return null
    return (rawIdx downTo 0).firstOrNull { rawMessages[it].isUser }
}

/**
 * 识别当前可见顶部消息对应的用户问题，返回其 **msgId**（用于与 Room 全量
 * 导航列表的 [JumpTarget.msgId] 匹配高亮）。
 *
 * 使用 listState.layoutInfo.visibleItemsInfo + 消息 key 格式
 *（"u_<id>" 为用户，"t_<id>" 为 assistant —— 见 ChatMessageList.kt:391-392）。
 * reverseLayout=true：视觉上最顶部的可见消息 = 最小偏移。
 *
 * 返回当前用户问题的 msgId，无法确定时返回 null。
 */
fun findCurrentQuestionMsgId(
    listState: LazyListState,
    rawMessages: List<ChatMessage>,
): String? {
    val visibleMsgs = listState.layoutInfo.visibleItemsInfo.filter { info ->
        (info.key as? String)?.let { it.startsWith("u_") || it.startsWith("t_") } == true
    }
    val topMsg = visibleMsgs.minByOrNull { it.offset } ?: return null
    val key = topMsg.key as String
    val msgId = key.removePrefix("u_").removePrefix("t_")
    val rawIdx = rawMessages.indexOfFirst { it.message.id == msgId }
    if (rawIdx < 0) return null
    return findNearestUserIndexBefore(rawMessages, rawIdx)?.let { rawMessages[it].message.id }
}
