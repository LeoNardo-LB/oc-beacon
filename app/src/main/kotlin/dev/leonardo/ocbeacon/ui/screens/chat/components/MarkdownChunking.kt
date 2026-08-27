package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import dev.leonardo.ocbeacon.domain.model.Message
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
    /** #246：每片首块的文本签名（内容前缀）——渲染期在当前 AST 中按签名
     *  重定位区间起点，消除「计划索引 vs 实际 AST 错位」类的头片丢失。
     *  与 [ranges] 一一对应；空串 = 无法取签名时退回纯索引模式。 */
    val rangeAnchors: List<String> = emptyList(),
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
    // #246：为每片记录首块签名——用该块在 content 中的起始文本切片（≤24 字符，
    // 空白归一）作为渲染期锚点：chunkSuccessSlot 在实际 AST 中按签名扫描重定位，
    // 杜绝「计划索引 vs 实际 AST」错位造成的头片丢失（时序排序由锚点顺序保证）。
    val content = state.content
    fun blockAnchor(idx: Int): String {
        val b = children.getOrNull(idx) ?: return ""
        val s = (b.startOffset).coerceIn(0, content.length)
        val e = minOf(s + 48, content.length)
        // 跳过前导空白后取非空前缀（32 字符）——纯空白切片无法做锚点
        // 跳过前导空白；若整个窗口都是空白（块起始在两块间隙），向后扫描至非空白
        var scan = s
        while (scan < content.length && content[scan].isWhitespace()) scan++
        val te = minOf(scan + 32, content.length)
        return if (scan < content.length) content.substring(scan, te) else ""
    }
    val anchors = ranges.map { r -> blockAnchor(r.first) }
    return if (ranges.size <= 1) null else MdChunkPlan(partId, ranges, state, anchors)
}

// ============ 用户长消息纯文本分片（2026-08-22 滚动巨帧根治） ============
//
// 根因（真机 gfxinfo 取证，houji devRelease）：用户消息 = 纯 Text 单
// LazyItem，StaticLayout 断行无任何跨组合缓存——20K 字符粘贴文档滚离视口
// 后 item 丢弃，滚回每轮重新断行 = 38-61ms 主线程巨帧稳定复现（滚离滚回
// ×3 每轮重现；对照组短消息区同协议仅 16-18ms）。与 assistant 分片同机制：
// LazyItem 粒度切分，把断行成本摊到 N 帧。

/** 用户长消息纯文本分片计划（段序列，行边界优先 + 超长单行硬切）。 */
data class UserTextChunkPlan(
    /** 目标 text part id（与消息一一对应，保留用于取证/调试）。 */
    val partId: String,
    /** 连续文本段（每段以 '\n' 结尾；joinToString("") == 原文 + 尾部换行）。 */
    val segments: List<String>,
)

/**
 * 纯文本切段：按行累计字符预算 [targetChars]（行边界对齐——零内容变异，
 * joinToString("") == 原文 + 尾部换行）。无换行的超长单行（URL/日志粘贴）
 * 切不出多段 → 返回 null 保持原渲染（保守：不插入原文没有的换行）。
 * 返回 null = 短于 [minChars] 或切不出多段（无需分片）。
 */
fun splitUserTextChunks(partId: String, text: String, minChars: Int, targetChars: Int): UserTextChunkPlan? {
    if (text.length < minChars) return null
    val segments = mutableListOf<String>()
    val buf = StringBuilder()
    var acc = 0
    fun flush() {
        if (buf.isNotEmpty()) {
            segments += buf.toString()
            buf.setLength(0)
            acc = 0
        }
    }
    for (line in text.split('\n')) {
        buf.append(line).append('\n')
        acc += line.length + 1
        if (acc >= targetChars) flush()
    }
    flush()
    if (segments.size <= 1) return null
    return UserTextChunkPlan(partId, segments)
}

/**
 * 用户 turn 分片判定（保守）：常规用户消息（非 synthetic/非压缩触发）且
 * 气泡可渲染 parts 恰为一条长文本——多 part（图片/补丁/多文本）与短消息
 * 保持原渲染路径。
 *
 * memo：buildChatEntries 每次重建（chunkPlans 提交/流式状态变化）都会对
 * 全列表重跑判定——20K 字符重复切割是主线程无谓开销（真机 93ms 离群帧
 * 嫌疑）。按 partId + 文本长度缓存（长度不等即失效——part 内容在实践里
 * 不可变，长度比对是廉价防御）。
 */
private val userPlanCache = HashMap<String, Pair<Int, UserTextChunkPlan?>>()

internal fun userChunkPlanFor(msg: ChatMessage): UserTextChunkPlan? {
    val userMsg = msg.message as? Message.User ?: return null
    if (userMsg.role == "synthetic") return null
    if (msg.parts.any { it is Part.Compaction }) return null
    val renderables = msg.parts.filter { dev.leonardo.ocbeacon.ui.screens.chat.isBubbleRenderablePart(it) }
    if (renderables.size != 1) return null
    val text = renderables[0] as? Part.Text ?: return null
    val cached = userPlanCache[text.id]
    if (cached != null && cached.first == text.text.length) return cached.second
    val plan = splitUserTextChunks(text.id, text.text, USER_CHUNK_MIN_CHARS, USER_CHUNK_TARGET_CHARS)
    userPlanCache[text.id] = text.text.length to plan
    return plan
}

/** 用户长消息分片门槛/预算（对齐 assistant CHUNK_MIN_CHARS/CHUNK_TARGET_CHARS）。 */
const val USER_CHUNK_MIN_CHARS = 3000
const val USER_CHUNK_TARGET_CHARS = 2500

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

    /** 用户长消息纯文本分片（2026-08-22；key "u_<msgId>#c<i>"，前缀保持 u_ 起始）。 */
    data class UserChunk(
        override val displayIndex: Int,
        override val key: String,
        val plan: UserTextChunkPlan,
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
        // C-R3 修复（2026-08-20 spec/impl 背离）：原条件 streamingMsgId == null 是
        // 全局粒度（任一消息流式 → 全表 chunked turn 合并为单 item；流式结束
        // → 全表再裂变）——视口内 key 双向翻转无门控 = 叠放竞态源 + 流式期长
        // turn 巨帧回归。改为 turn 粒度：仅流式 turn 不分片，其余照常。
        val isStreamingTurn = streamingMsgId != null &&
            (turnGroups[displayIdx] ?: listOf(msg)).any { it.message.id == streamingMsgId }
        val plan = if (!msg.isUser && !isStreamingTurn && turnKey !in recentStreamedTurnKeys) {
            val turnMsgs = turnGroups[rawIndex] ?: listOf(msg)
            turnMsgs.firstNotNullOfOrNull { cm ->
                cm.parts.firstOrNull { it is Part.Text && it.id in chunkPlans }?.let { chunkPlans[it.id] }
            }
        } else null
        // 用户长消息纯文本分片（无 AST/无预解析依赖——切段为纯函数，首建即
        // 稳定 key，无 assistant 那种「解析后裂变」窗口，无需延迟提交门控）。
        // #232 勘误二（SysMsgDiag 定音）：system 角色的 User 消息（zhipu 工具
        // 目录 11KB）必须排除——此前被用户长文分片切成 UserChunk 条目，绕过
        // Turn 分支的 #232 系统通知拦截，逐分片按用户 Markdown 渲染（含代码
        // 块样式）= 又一面包文本墙。排除后走 ChatEntry.Turn → 拦截生效。
        val userPlan = if (plan == null && msg.isUser &&
            (msg.message as? dev.leonardo.ocbeacon.domain.model.Message.User)?.role != "system"
        ) userChunkPlanFor(msg) else null
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
        } else if (userPlan != null) {
            val count = userPlan.segments.size
            for (c in 0 until count) {
                entries += ChatEntry.UserChunk(
                    displayIndex = displayIdx,
                    key = turnKey + "#c" + c,
                    plan = userPlan,
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
