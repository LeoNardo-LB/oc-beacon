package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.logging.AppLogger
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
 * 识别当前可见的用户问题，返回其 **msgId**（用于与 Room 全量导航列表的
 * [JumpTarget.msgId] 匹配高亮）。
 *
 * 2026-08-12 修复（两次迭代）：
 * - 原实现：key 解析 id → rawMessages 索引向上找 user——displayItems 过滤
 *   消息（synthetic/turn 合并）导致索引与显示序列不一致 → currentMsgId 恒 null。
 * - 第一次改版：minByOrNull(offset) 取"视觉顶部"——reverseLayout 下 offset
 *   语义不可靠（实测选出的是最新消息/视觉底部），且向上找 user 方向错。
 * - 最终方案：直接取 **可见项中 key 以 "u_" 开头（user）且 index 最大**
 *   （降序列表 index 大 = 更旧 = 视觉更靠上）——不依赖 offset 语义，
 *   不受 displayItems 过滤影响，语义 = "用户当前正在查看区域最上方的问题"。
 *
 * 返回当前用户问题的 msgId：
 * - 视口内有可见 user 消息 → 取 index 最大（视觉最靠上）的那个
 * - 视口内无 user（如最新回复为长 assistant 占满视口）→ 以可见项中
 *   index 最大（最旧可见）为锚点，向更旧方向找最近 user；再向更新方向找
 * - 跳过空壳 user（无文本 parts——与 extractJumpTargets 过滤一致，
 *   否则返回的 id 不在导航列表 → index=-1 无法高亮/滚动）
 * - 均无 → null
 */
fun findCurrentQuestionMsgId(
    listState: LazyListState,
    displayItems: List<Pair<Int, ChatMessage>>,
    bannerCount: Int,
): String? {
    val visible = listState.layoutInfo.visibleItemsInfo
        .filter { (it.key as? String)?.let { k -> k.startsWith("u_") || k.startsWith("t_") } == true }
    val anchor = visible
        .filter { (it.key as? String)?.startsWith("u_") == true && hasText(displayItems, it.index - bannerCount) }
        .maxByOrNull { it.index }
        ?: visible.maxByOrNull { it.index }
        ?: run {
            if (BuildConfig.DEBUG) AppLogger.d("QuickNavigate", "findCurrent: 可见区无消息项 (total=${listState.layoutInfo.visibleItemsInfo.size})")
            return null
        }
    val displayIdx = anchor.index - bannerCount
    if (BuildConfig.DEBUG) {
        AppLogger.d("QuickNavigate", "findCurrent: anchorIdx=${anchor.index} banner=$bannerCount displayIdx=$displayIdx items=${displayItems.size}")
    }
    if (displayIdx < 0 || displayIdx >= displayItems.size) return null
    val found = (displayIdx until displayItems.size).firstOrNull { isNavigableUser(displayItems[it]) }
        ?: (displayIdx downTo 0).firstOrNull { isNavigableUser(displayItems[it]) }
    return found?.let { displayItems[it].second.message.id }
}

/** user 且非 synthetic、至少一个非空 Part.Text（可导航判定，与快速导航列表过滤一致）。 */
private fun isNavigableUser(item: Pair<Int, ChatMessage>): Boolean =
    item.second.isUser &&
        item.second.message.role != "synthetic" &&
        item.second.parts.any { it is Part.Text && it.text.isNotBlank() }

/** 根据 LazyColumn 索引（含 banner 偏移）取 displayItems 项并判定可导航。 */
private fun hasText(displayItems: List<Pair<Int, ChatMessage>>, displayIdx: Int): Boolean =
    displayIdx in displayItems.indices && isNavigableUser(displayItems[displayIdx])

/**
 * 当前可见区域的时间锚点（可见消息项中 index 最大的 created）——
 * 用于快速导航打开时定位"当前位置附近"的问题（currentMsgId 无法匹配时降级）。
 * 返回 null 表示无法确定。
 */
fun findCurrentAnchorTimestamp(
    listState: LazyListState,
    displayItems: List<Pair<Int, ChatMessage>>,
    bannerCount: Int,
): Long? {
    val anchor = listState.layoutInfo.visibleItemsInfo
        .filter { (it.key as? String)?.let { k -> k.startsWith("u_") || k.startsWith("t_") } == true }
        .maxByOrNull { it.index }
        ?: return null
    val displayIdx = anchor.index - bannerCount
    if (displayIdx !in displayItems.indices) return null
    return displayItems[displayIdx].second.message.time.created
}
