package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 超长 assistant 消息的块级分片（2026-08-20 fling 巨帧根治）。
 *
 * 根因（真机 Perfetto 取证 + mikepenz 0.43.0 源码核对）：一条长 assistant
 * 消息 = 一个 LazyItem；LazyColumn 子项在滚动方向上是无限高约束 → item
 * 必须组合全部内容。130K 字符 ≈ 300+ 顶层 Markdown 块在单个组合作用域内
 * 同步建完 = 单帧 50-80ms（trace: 单个 Compose:recompose scope 49.7ms；
 * prefetch:measure max 150ms——item 是预取的原子单位，巨型 item 预取无效）。
 * 库无块级懒加载参数（0.43.0 全部重载核对），LazyMarkdownSuccess 不能嵌套
 * 进同向 LazyColumn。唯一治本维度 = LazyItem 粒度本身：把已完结长消息的
 * Markdown AST 顶层块序列切成连续区间，一个 turn 发射 N 个 item。
 *
 * 引用式链接在解析期已写入 referenceLinkHandler（State.Success 携带），
 * children 拆开渲染不破坏跨块引用链接（库源码 model/MarkdownState.kt:221）。
 */

/** 分片计划：目标 text part 的 AST 顶层块按字符预算切成的区间列表。 */
data class MdChunkPlan(
    /** 目标 text part id（与 RenderReadinessRegistry 键一致）。 */
    val partId: String,
    /** 每片渲染的 children 区间 [from, to)（to 不含）。 */
    val ranges: List<IntRange>,
    /** 计划对应的解析产物（渲染 chunk 直接用，无需再查注册表）。 */
    val state: State.Success,
)

/**
 * 计算分片计划：按 [targetChars] 字符预算切顶层块。
 * 用块的 start/end offset 估算字符量（无需拼接文本）。
 */
fun computeChunkPlan(partId: String, state: State.Success, minChars: Int, targetChars: Int): MdChunkPlan? {
    val children = state.node.children
    if (children.isEmpty()) return null
    // 字符量不足以分片（minChars 门槛由调用方判定，这里防御性复查）
    val total = children.last().endOffset - children.first().startOffset
    if (total < minChars) return null
    val ranges = mutableListOf<IntRange>()
    var start = 0
    var acc = 0
    for (i in children.indices) {
        acc += children[i].endOffset - children[i].startOffset
        if (acc >= targetChars && i < children.size - 1) {
            ranges += start until i + 1
            start = i + 1
            acc = 0
        }
    }
    if (start < children.size) ranges += start until children.size
    return if (ranges.size <= 1) null else MdChunkPlan(partId, ranges, state)
}

/** LazyColumn 发射条目（消息 turn 或其分片 chunk）。 */
internal sealed interface ChatEntry {
    /** displayItems 索引（chunk 与其所属 turn 共享）。 */
    val displayIndex: Int
    val key: String

    /** 完整 turn（未分片：user / 流式 / 短消息 / 预解析未就绪）。 */
    data class Turn(
        override val displayIndex: Int,
        override val key: String,
    ) : ChatEntry

    /** 已完结长消息的 Markdown 分片。 */
    data class Chunk(
        override val displayIndex: Int,
        /** "t_<turnId>#c<i>"——前缀保持 t_ 起始（可见项过滤依赖）。 */
        override val key: String,
        val plan: MdChunkPlan,
        val chunkIndex: Int,
        val chunkCount: Int,
    ) : ChatEntry {
        val isFirst: Boolean get() = chunkIndex == 0
        val isLast: Boolean get() = chunkIndex == chunkCount - 1
    }
}

/**
 * 分片发射表 + 双向索引（LazyColumn index ↔ displayItems index 的单一真相源）。
 *
 * @param entries 发射条目（messages 区，不含 banner）
 * @param entryDisplayIndex entry 序号 → displayItems 索引（bannerCount 之外）
 * @param displayEntryStart displayItems 索引 → 该 turn 首个 entry 序号
 */
internal data class ChatEntries(
    val entries: List<ChatEntry>,
    val entryDisplayIndex: IntArray,
    val displayEntryStart: IntArray,
)

/**
 * 构建分片发射表。分片条件（全部满足）：
 * - assistant turn；- 非流式（streamingMsgId 不在 turn 内）；
 * - 不在 recentStreamedTurnKeys（流式刚结束的 turn 延迟分片——避免视口内
 *   key 从 1 个裂成 N 个的闪变/锚点跳动，滚出预解析窗口后自然进入分片）；
 * - turn 内存在 part.id 命中 chunkPlans 的巨型 text part。
 *
 * key 生成逻辑与 ChatMessageList itemsIndexed 原 key 完全一致
 * （#103 M-8：turn 组首条消息 id 锚定），chunk 追加 "#c<i>" 后缀。
 */
internal fun buildChatEntries(
    displayItems: List<Pair<Int, ChatMessage>>,
    turnGroups: Map<Int, List<ChatMessage>>,
    streamingMsgId: String?,
    chunkPlans: Map<String, MdChunkPlan>,
    recentStreamedTurnKeys: Set<String>,
): ChatEntries {
    val entries = mutableListOf<ChatEntry>()
    val displayEntryStart = IntArray(displayItems.size)
    for (displayIdx in displayItems.indices) {
        displayEntryStart[displayIdx] = entries.size
        val (rawIndex, msg) = displayItems[displayIdx]
        val turnKey = if (msg.isUser) {
            "u_" + msg.message.id
        } else {
            "t_" + (turnGroups[rawIndex]?.firstOrNull()?.message?.id ?: msg.message.id)
        }
        val plan = if (!msg.isUser && streamingMsgId == null && turnKey !in recentStreamedTurnKeys) {
            val turnMsgs = turnGroups[rawIndex] ?: listOf(msg)
            turnMsgs.firstNotNullOfOrNull { cm ->
                cm.parts.firstOrNull { it is Part.Text && it.id in chunkPlans }?.let { chunkPlans[it.id] }
            }
        } else null
        if (plan != null) {
            val count = plan.ranges.size
            for (c in 0 until count) {
                entries += ChatEntry.Chunk(
                    displayIndex = displayIdx,
                    key = turnKey + "#c" + c,
                    plan = plan,
                    chunkIndex = c,
                    chunkCount = count,
                )
            }
        } else {
            entries += ChatEntry.Turn(displayIdx, turnKey)
        }
    }
    return ChatEntries(
        entries = entries,
        entryDisplayIndex = IntArray(entries.size) { entries[it].displayIndex },
        displayEntryStart = displayEntryStart,
    )
}
