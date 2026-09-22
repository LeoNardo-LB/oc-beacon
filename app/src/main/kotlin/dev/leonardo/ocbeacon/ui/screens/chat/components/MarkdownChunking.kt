package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup

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

/**
 * V2 服务器合成信封消息的 role 集合（#252 勘误二）：这类消息只承载状态语义
 * （后台 shell 登记 / agent 切换 / 模型切换），零 parts、无用户可见内容。
 * 客户端的可见反馈由专门通道承担（shell → ShellJobsStore 通知卡）；按原样
 * 渲染只会产生 48dp 空气泡（Message.User 回退样式），累积即「消息间鸿沟」。
 */
internal val SYNTHETIC_ENVELOPE_ROLES = setOf("shell", "agent-switched", "model-switched")

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
    // #246 六轮根治：剔除纯空白块（归一化后 AST 中存在空白节点——以它开头的
    // chunk 渲染高度≈0，配合 LazyColumn 放置逻辑表现为整片消失。JVM repro
    // 实证：chunk1 首块=' '，c0 高度塌陷被挤出视口）。charLen 只计非空白块。
    val significant = children.mapIndexed { index, n ->
        val s0 = n.startOffset.coerceIn(0, state.content.length)
        val s1 = n.endOffset.coerceIn(0, state.content.length)
        Triple(index, n, s1 > s0 && state.content.substring(s0, s1).any { it.isLetterOrDigit() })
    }.filter { it.third }
    if (significant.isEmpty()) return null
    val significantIdx = HashSet(significant.map { it.first })
    // 字符量不足以分片（minChars 门槛由调用方判定，这里防御性复查）
    val total = significant.last().second.endOffset - significant.first().second.startOffset
    if (total < minChars) return null
    val ranges = mutableListOf<IntRange>()
    var start = significant.first().first
    var acc = 0
    for ((i, n, _) in significant) {
        acc += n.endOffset - n.startOffset
        if (acc >= targetChars && i < children.size - 1) {
            ranges += start..i
            // #246 补全（2026-08-27）：切割点不得落在纯空白块上——原修复只
            // 把空白块剔出字符核算，start=i+1 仍会以空白块开头（JVM repro
            // 实证 ranges 0..21,22..41 且块 22=' '）→ 该片渲染高度≈0。
            start = i + 1
            while (start < children.size && start !in significantIdx) start++
            acc = 0
        }
    }
    if (start <= children.size - 1) ranges += start until children.size
    // #246：为每片记录首块签名——用该块在 content 中的起始文本切片（≤32 字符，
    // 跳过前导空白）作为渲染期锚点：chunkSuccessSlot 在实际 AST 中按签名扫描
    // 重定位，杜绝「计划索引 vs 实际 AST」错位造成的头片丢失。
    val content = state.content
    fun blockAnchor(idx: Int): String {
        val b = children.getOrNull(idx) ?: return ""
        var s = (b.startOffset).coerceIn(0, content.length)
        while (s < content.length && content[s].isWhitespace()) s++
        val e = minOf(s + 32, content.length)
        return if (s < content.length) content.substring(s, e) else ""
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
        /** #422 历史懒加载:大组拆条目发射时,尾片跳过 StepGroup 渲染
         * (折叠行由 [StepGroupHead] 条目承担,内容由 [StepGroupBody] 承担)。 */
        val skipStepGroupItem: Boolean = false,
    ) : ChatEntry

    /**
     * #422 历史懒加载:大组展开态的折叠行头(key "t_<turnId>#sgh",气泡顶部)。
     * 仅与 [Turn] 尾片 + 若干 [StepGroupBody] 组成同 turn 的条目族。
     */
    data class StepGroupHead(
        override val displayIndex: Int,
        override val key: String,
        val step: dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem.StepGroup,
    ) : ChatEntry

    /**
     * #422 历史懒加载:大组展开体的 groups 分片(key "t_<turnId>#sgb<i>")。
     * 独立 LazyItem → 视口外零组合,巨型组首开只组可见条目。
     */
    data class StepGroupBody(
        override val displayIndex: Int,
        override val key: String,
        val groups: List<PartGroup>,
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

    /**
     * 历史长 turn 的分段 chunk（#258 Stage B；key "t_<turnId>#s<i>"，前缀保持
     * t_ 起始与旧 Chunk 的 #c 后缀互斥不碰撞）。chunkIndex 为跨段扁平序号
     * （Giant 段按 AST 区间展开）。
     */
    data class TurnChunk(
        override val displayIndex: Int,
        override val key: String,
        val plan: TurnSegmentPlan,
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
 * #422:多消息轮次(turn 内 ≥2 条 assistant 消息)判定——其非末消息内容在
 * StepGroup 折叠体内渲染,不得走 MdChunkPlan part 级分片(Chunk 条目绕过
 * turn renderable,折叠组行与末消息内容双丢失)。产侧(协调器)与装配侧
 * (buildChatEntries)共用本谓词,防单边漂移。
 */
internal fun List<ChatMessage>.isMultiMessageTurn(): Boolean = size > 1

/**
 * #422 历史懒加载阈值(已退役,批次十三全量裂变退役后仅注释留存):StepGroup
 * 权重达此值曾走条目化发射。#427 起切片器定义迁 [StepGroupSlicing.kt]
 * (宿主移入卡片内部);本文件下方退役裂变发射分支仍引用该函数——分支恒
 * 不触发(expandedStepGroups 恒空),随 #426 死代码批次整体移除。
 */

/**
 * 构建分片发射表。分片条件（全部满足）：
 * - assistant turn；- 非流式（streamingMsgId 不在 turn 内）；
 * - 不在 recentStreamedTurnKeys（流式刚结束的 turn 延迟分片——避免视口内
 *   key 从 1 个裂成 N 个的闪变/锚点跳动，滚出预解析窗口后自然进入分片）；
 * - turn 内存在 part.id 命中 chunkPlans 的巨型 text part。
 *
 * #258 Stage B：历史长 turn 追加 [segmentPlans] 路径（TurnSegmentPlan，
 * renderItem 边界分段）——与 chunkPlans 互斥（旧 MdChunkPlan 优先：turn 内
 * 存在旧计划 part 即走旧路径）；计划指纹失配（turn 内容已变）即弃。
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
    segmentPlans: Map<String, TurnSegmentPlan> = emptyMap(),
    /** #422→#423 批次六:turnKey → 展开态 StepGroup(权重门槛已拆,全量裂变)。
     *  命中的 turn 拆条目发射(尾 Turn + StepGroupBody×N + StepGroupHead)。 */
    expandedStepGroups: Map<String, dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem.StepGroup> = emptyMap(),
): ChatEntries {
    val entries = mutableListOf<ChatEntry>()
    val displayEntryStart = IntArray(displayItems.size)
    // #252 时间线化：REST 真消息的 shellId 索引——local 乐观合成行（msg_local_shell_*
    // 前缀，ShellJobsHandler 插入）在真消息入库后让位，避免双卡。
    val realShellIds = HashSet<String>()
    for ((_, m) in displayItems) {
        if (m.message.id.startsWith("msg_local_")) continue
        if (m.message.role == "shell") {
            m.parts.firstOrNull { it is Part.Shell }?.let { realShellIds.add((it as Part.Shell).shellId) }
        }
    }
    for (displayIdx in displayItems.indices) {
        displayEntryStart[displayIdx] = entries.size
        val (rawIndex, msg) = displayItems[displayIdx]
        // #252 真机勘误二（2026-08-28，UI dump + Room 实证定音）：V2 服务器为每次
        // !cmd 创建 role='shell' 零 parts 信封消息——MessageSerializer 按 role 分发时
        // 'shell' 落入 else 回退分支反序列化为 Message.User（不是 Assistant！），原
        // (message as? Message.Assistant)?.role == "shell" 判定永不命中，空气泡
        //（48dp/条）照常渲染，15 条占位累积 = 消息与通知卡之间的半屏鸿沟。
        // #252 时间线化（2026-08-28 用户定音「shell 命令是主对话内容的一部分」）：
        // 带 Part.Shell 载荷的 shell 消息（V2Mappers 映射 REST 完整载荷）**按消息
        // 时间线发射**——渲染层在其时间位置出通知卡，新消息自然顶上去；零载荷
        // 空壳（SSE 实时窗口未刷新 / 历史数据）仍跳过（无内容可渲染）。
        // agent-switched/model-switched 同为服务器合成零内容信封，无条件跳过。
        if (msg.message.role in SYNTHETIC_ENVELOPE_ROLES) {
            val hasShellPayload = msg.message.role == "shell" &&
                msg.parts.any { it is dev.leonardo.ocbeacon.domain.model.Part.Shell }
            if (!hasShellPayload) {
                continue
            }
            // local 乐观合成行：同 shellId 的 REST 真消息已在列表 → 让位
            if (msg.message.id.startsWith("msg_local_shell_")) {
                val localShellId = msg.parts.firstOrNull { it is dev.leonardo.ocbeacon.domain.model.Part.Shell }
                    ?.let { (it as dev.leonardo.ocbeacon.domain.model.Part.Shell).shellId }
                if (localShellId != null && localShellId in realShellIds) {
                    continue
                }
            }
        }
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
        // #422 历史懒加载:大组展开态拆条目(先于一切旧分片路径——MdChunkPlan 对
        // 多消息轮次已抑制,segPlan 让位)。发射序 = 视觉自底向上(reverseLayout
        // 索引 0 在屏幕底部,同 #246 逆文档序先例):尾 Turn(末消息+统计栏)先入列,
        // 内容分片逆序,折叠行头最后(视觉顶部)。displayEntryStart 钉回头部——
        // 跳转落点 = 折叠行,语义与其他路径的"首片含标签栏"一致。
        val splitStepGroup =
            if (!msg.isUser && !isStreamingTurn) expandedStepGroups[turnKey] else null
        if (splitStepGroup != null) {
            entries += ChatEntry.Turn(displayIdx, turnKey, skipStepGroupItem = true)
            // 键序号=文档序,发射逆序(底部=文档最旧片)——同 #246 chunk 键语义
            val bodies = sliceStepGroupBodies(splitStepGroup.groups)
            for (bi in bodies.indices.reversed()) {
                entries += ChatEntry.StepGroupBody(displayIdx, turnKey + "#sgb" + bi, bodies[bi])
            }
            // #423 批次三:组尾收起行——大组内容可达数屏高,头部折叠行在视觉顶部
            // (reverseLayout 发射最后),用户读到底部无处收起。尾部再发一条同款
            // 折叠行(点击收起),复用 StepGroupHead 渲染分支,零新组件。
            entries += ChatEntry.StepGroupHead(displayIdx, turnKey + "#sgt", splitStepGroup)
            entries += ChatEntry.StepGroupHead(displayIdx, turnKey + "#sgh", splitStepGroup)
            displayEntryStart[displayIdx] = entries.size - 1
            continue
        }
        val plan = if (!msg.isUser && !isStreamingTurn && turnKey !in recentStreamedTurnKeys) {
            val turnMsgs = turnGroups[rawIndex] ?: listOf(msg)
            // #422:多消息轮次(含 StepGroup 折叠组)不走 MdChunkPlan 分片——
            // Chunk 条目按 part 直渲染,绕过 turn renderable(折叠组行与末消息
            // 内容双丢失,巨型中间消息平铺)。防御性抑制(协调器侧已不产);
            // 巨型末消息由 Stage B 分段接管(SG 保持独立 item)。
            if (turnMsgs.isMultiMessageTurn()) {
                null
            } else turnMsgs.firstNotNullOfOrNull { cm ->
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
        // #258 Stage B：历史长 turn 分段计划（旧 MdChunkPlan 未命中时兜底；
        // 指纹失配 = turn 内容已变（REST 刷新/分页替换）→ 弃置等待重算）。
        val segPlan = if (plan == null && !msg.isUser && !isStreamingTurn && turnKey !in recentStreamedTurnKeys) {
            val turnMsgs = turnGroups[rawIndex] ?: listOf(msg)
            segmentPlans[turnKey]?.takeIf { sp ->
                sp.representativeMsgId == msg.message.id &&
                    sp.fingerprint == turnPlanFingerprint(msg, turnMsgs)
            }
        } else null
        if (plan != null) {
            val count = plan.ranges.size
            // #246 定音（2026-08-27 真机截图+ScrollDiag 算术链）：displayItems
            // 最新在前（reverseLayout 索引 0 在屏幕底部），chunk 必须逆文档序
            // 发射（尾片先入列）。原正序发射把 head 当「更新」排到 tail 下方
            // ——屏幕上第 5-8 节跑到第 1-4 节上面，用户从底部上滚先见尾片。
            for (c in count - 1 downTo 0) {
                entries += ChatEntry.Chunk(
                    displayIndex = displayIdx,
                    key = turnKey + "#c" + c,
                    plan = plan,
                    chunkIndex = c,
                    chunkCount = count,
                )
            }
            // 跳转落点语义不变（「turn 首 chunk」= 含标签栏的头片 c0）；
            // 反转后头片最末发射，displayEntryStart 显式钉回。
            displayEntryStart[displayIdx] = entries.size - 1
        } else if (userPlan != null) {
            val count = userPlan.segments.size
            // 同上：逆文档序发射（#246）
            for (c in count - 1 downTo 0) {
                entries += ChatEntry.UserChunk(
                    displayIndex = displayIdx,
                    key = turnKey + "#c" + c,
                    plan = userPlan,
                    chunkIndex = c,
                    chunkCount = count,
                )
            }
            displayEntryStart[displayIdx] = entries.size - 1
        } else if (segPlan != null) {
            // #258 Stage B：历史长 turn 分段（TurnSegmentPlan）——同 #246 逆文档序
            // 发射（尾片先入列），displayEntryStart 钉回首片（含标签栏，跳转落点
            // 语义与旧路径一致）。
            val count = segPlan.chunkCount
            for (c in count - 1 downTo 0) {
                entries += ChatEntry.TurnChunk(
                    displayIndex = displayIdx,
                    key = turnKey + "#s" + c,
                    plan = segPlan,
                    chunkIndex = c,
                    chunkCount = count,
                )
            }
            displayEntryStart[displayIdx] = entries.size - 1
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
