package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 压缩分割线认领策略（C4，2026-08-26 架构走查候选 4）——纯 Kotlin，无 Compose 依赖。
 *
 * 「一条压缩 = 一条分割线」（#217 裁决）：压缩的视觉呈现由三类分割线承担——
 * 消息流内 V1 摘要线（assistant agent=compaction）、消息流内触发线
 * （role=compaction 对位 / 带 Compaction part 的 user 消息）、尾部兜底线
 * （进行中压缩对应消息未入列时）。本文件是**认领/判定/去重决策的唯一真相源**：
 * 输入纯数据（displayItems / compactionState / 展开表快照），输出 [CompactionDividerSpec]；
 * Compose 状态（remember/mutableStateMap）留在 ChatMessageList，只把状态值作为参数传入。
 *
 * 历史修复语义存档（测试 CompactionDividerPolicyTest 表驱动覆盖）：
 * - #219（ba53ffef）：骨架期分割线消失——按 role+对位认领，不按 parts
 * - #226（ff2b78be）：V1 三元素重叠 + 空安全陷阱吞气泡
 * - #227（0ec516fe）：展开态滚出视口即丢——屏幕级展开表 + 尾部→消息流交接
 */
sealed class CompactionDividerSpec {

    /** #226：V1 摘要消息（assistant agent=compaction）认领的分割线。
     *  未完结 → 伪活跃态（active=true，activeState 由流式 text 装配）；
     *  已完结 → 完成态（summary 可达）。同 item 原位切换保留 Q13 连续性。 */
    data class V1Summary(
        val messageId: String,
        /** #227 屏幕级展开表键（= messageId）。 */
        val expansionKey: String,
        /** v1Active：未完结（归一化器完结守卫放行、parts 为流式 text）。 */
        val active: Boolean,
        /** active 时由 parts 流式 text 装配的伪活跃状态；完成态为 null。 */
        val activeState: CompactionStateInfo?,
        val summary: String?,
        val failed: Boolean,
    ) : CompactionDividerSpec()

    /** #217/#219：消息流内触发分割线（role=compaction 对位认领 / 带 Compaction part）。
     *  activeState 非空 = 进行中（含 #221 锁存空窗）；null = 完成态。 */
    data class Trigger(
        val expansionKey: String,
        val activeState: CompactionStateInfo?,
        val summary: String?,
        val failed: Boolean,
    ) : CompactionDividerSpec()

    /** #226：V1 触发消息（role=user + Compaction part + 空 summary）——零内容标记，
     *  不渲染（item 退化为一段 messageSpacing 间隙）。 */
    object V1TriggerHidden : CompactionDividerSpec()

    /** 尾部兜底分割线：仅当进行中压缩对应的消息还不在渲染列表（V2 进行期消息
     *  未刷新入列 / V1 无 messageId）——消息已对位时由消息流内同一 item 承担
     *  （避免双分割线，#217）。 */
    data class Tail(
        val state: CompactionStateInfo,
        /** #227：V2 = 真实 messageId；V1 本地置态 messageId 为空串 → 固定键
         *  （尾部→消息流交接桥的源键）。 */
        val expansionKey: String,
    ) : CompactionDividerSpec()

    /** 非压缩认领——正常消息渲染路径。 */
    object NotCompaction : CompactionDividerSpec()
}

/** #227 交接桥执行计划（纯决策，写表由调用方 effect 落地）。 */
data class V1TailHandoverPlan(
    /** V1 摘要消息 id（展开态搬入目标键）。 */
    val targetKey: String,
    /** 尾部固定键上记录的展开态。 */
    val expanded: Boolean,
)

/**
 * bannerCount / revealBannerCount 的压缩项派生（C4-C：不再独立手算——与尾部
 * 兜底 item 渲染同源，消除「必须与 item 块保持一致」的双写约束）。
 *
 * @property streamClaimed 进行中压缩已被消息流内 item 认领（计 bannerCount：
 *   视觉上分割线在消息流内，但物理上仍是 LazyColumn 尾部区 item 之一）
 * @property tailFallback 进行中压缩无人认领 → 尾部兜底分割线（计 revealBannerCount：
 *   #222 贴底 reveal 四类之一，插入点在锚之下不可见，需显式锚底）
 */
data class CompactionBannerTerms(
    val streamClaimed: Boolean,
    val tailFallback: Boolean,
)

/**
 * 压缩分割线认领策略纯函数集。全部无副作用；展开表以只读 [Map] 快照传入。
 */
object CompactionDividerPolicy {

    /** #227：压缩尾部兜底分割线的展开表键——V1 本地置态 messageId 为空串，无真实 id 可用。 */
    const val TAIL_EXPANSION_KEY = "compaction_banner_tail"

    // ===== ① 尾部兜底去重判据 =====

    /** #217：当前渲染列表的消息 id 集——尾部进行中压缩分割线的去重判据
     *  （压缩消息已在列表中时，由消息流内同 item 分割线承担，尾部不再出线）。 */
    fun displayItemMessageIds(displayItems: List<Pair<Int, ChatMessage>>): Set<String> =
        displayItems.map { it.second.message.id }.toSet()

    /** #226：列表中 V1 摘要消息（assistant agent=compaction）id——无则 null。
     *  V2 消息不带 agent=compaction，恒 null 零影响。 */
    fun v1SummaryMessageId(displayItems: List<Pair<Int, ChatMessage>>): String? =
        displayItems.firstOrNull { entry ->
            val m = entry.second.message
            m is Message.Assistant && m.agent == "compaction"
        }?.second?.message?.id

    // ===== ④ 尾部兜底分割线 =====

    /** 尾部兜底分割线认领：active 且对应消息未入列（messageId 不在 id 集、
     *  且无 V1 摘要消息入列）时返回 [CompactionDividerSpec.Tail]，否则 null。 */
    fun tailSpec(
        compaction: CompactionStateInfo?,
        displayItemMessageIds: Set<String>,
        v1SummaryInList: Boolean,
    ): CompactionDividerSpec.Tail? {
        val active = compaction?.takeIf { it.isActive } ?: return null
        // #226：V1 摘要消息入列后由消息流内活跃线承担（V1 本地置态 messageId
        // 为空串永不命中对位判据，此前尾部线全程在场 → 与摘要线/气泡三元素同屏）
        if (active.messageId in displayItemMessageIds || v1SummaryInList) return null
        return CompactionDividerSpec.Tail(state = active, expansionKey = tailExpansionKey(active.messageId))
    }

    /** #227：尾部兜底线的展开表键——V2 用真实 messageId（尾部→消息流对位交接
     *  同键无缝）；V1 空串退固定键。 */
    fun tailExpansionKey(messageId: String): String =
        messageId.ifBlank { TAIL_EXPANSION_KEY }

    // ===== ② V1 尾部→消息流展开态交接桥 =====

    /** #227 交接桥决策：V1 摘要消息入列（尾部线让位）且尾部固定键上有展开记录时，
     *  返回搬移计划（目标键 = 摘要消息 id）；否则 null（零操作）。
     *  「完成不收起」（#221 裁决）在 V1 交接路径同样成立。 */
    fun v1TailHandoverPlan(
        displayItems: List<Pair<Int, ChatMessage>>,
        expansions: Map<String, Boolean>,
    ): V1TailHandoverPlan? {
        val tailExpansion = expansions[TAIL_EXPANSION_KEY] ?: return null
        val target = v1SummaryMessageId(displayItems) ?: return null
        return V1TailHandoverPlan(targetKey = target, expanded = tailExpansion)
    }

    // ===== ⑤ V1 摘要消息认领（assistant agent=compaction 分支） =====

    /** #226：V1 摘要消息认领判定——assistant(agent=compaction) 一律渲染分割线，
     *  与 V2 同构（「一条压缩 = 一条分割线」）：
     *  - 未完结（归一化器完结守卫放行、parts 为流式 text）→ 伪活跃态分割线：
     *    骑线进度 + 可展开流式摘要——取代普通气泡流式（用户裁决 #217：压缩输出
     *    不得在气泡中；气泡→完成态分割线的形态突变即「闪现」主源）。
     *  - 已完结（CompactionNormalizer 折叠为单个 Part.Compaction）→ 完成态分割线
     *    + 摘要可达——修复此前 PartContent 跳过 Compaction 导致的空 turn。
     *  非 agent=compaction 消息返回 null（正常气泡路径）。 */
    fun v1SummarySpec(msg: ChatMessage): CompactionDividerSpec.V1Summary? {
        val asstInfo = msg.message as? Message.Assistant
        if (asstInfo == null || asstInfo.agent != "compaction") return null
        val compPart = msg.parts.filterIsInstance<Part.Compaction>().firstOrNull()
        val v1Active = compPart == null && asstInfo.time.completed == null && asstInfo.error == null
        val activeState = if (v1Active) {
            val liveSummary = msg.parts
                .filterIsInstance<Part.Text>()
                .joinToString("\n\n") { it.text }
                .trim()
            CompactionStateInfo(
                isActive = true,
                reason = "",
                deltaText = liveSummary,
                messageId = msg.message.id,
            )
        } else null
        return CompactionDividerSpec.V1Summary(
            messageId = msg.message.id,
            expansionKey = msg.message.id,
            active = v1Active,
            activeState = activeState,
            summary = compPart?.summary,
            failed = compPart?.failed ?: (asstInfo.error != null),
        )
    }

    /** #226：V1 摘要线撤销边界取压缩触发消息（V1 语义：撤到压缩点之前恢复被压
     *  前文；摘要消息 id 会留下触发残骸）——即列表中紧邻其前、带 Compaction part
     *  的 user 消息；找不到退自身。 */
    fun v1RevertBoundary(
        displayItems: List<Pair<Int, ChatMessage>>,
        displayItemIndex: Int,
        fallbackId: String,
    ): String {
        return displayItems.getOrNull(displayItemIndex - 1)
            ?.second
            ?.takeIf { prev ->
                prev.message is Message.User && prev.parts.any { it is Part.Compaction }
            }?.message?.id ?: fallbackId
    }

    // ===== ⑥ 触发消息判定（user 分支：V1 触发隐藏 + V2 对位认领） =====

    /** #217：进行中态按 messageId 对位——仅当前压缩对应的消息渲染进行中分割线
     *  （历史分割线不受新压缩影响）；同 item 原位切换保证 Q13 展开/流式文本连续
     *  （messageId 来自 started 事件 inputID，与 compaction 消息 id 同源）。 */
    fun activeCompactionFor(
        compaction: CompactionStateInfo?,
        messageId: String,
    ): CompactionStateInfo? = compaction?.takeIf { it.isActive && it.messageId == messageId }

    /** #219/#226：user 方向消息的压缩认领决策（按渲染时序逐级判定）：
     *  1. V1 触发消息（role=user + Compaction part + 空 summary）→ [CompactionDividerSpec.V1TriggerHidden]
     *     ——不再渲染：此前它以静止「已压缩」形态与摘要线/尾部活跃线三元素同屏
     *     （且进行中误导为完成态）。压缩的视觉呈现由摘要消息与尾部兜底线全权承担。
     *  2. 带 Compaction part（V2 完成态/摘要可达）或 role=compaction 对位认领
     *     （进行中 activeState 非空，或 #221 锁存的 lastCompactionMsgId）→ [CompactionDividerSpec.Trigger]
     *  3. 其余 → [CompactionDividerSpec.NotCompaction]
     *
     *  #219 修复二（进行中分割线消失）：inbox.enqueued 在压缩发起瞬间即插入
     *  role=compaction 骨架消息（无 Part.Compaction——那要等完成后的 REST 刷新）。
     *  此前仅按 parts 判定 → 骨架期消息流内不渲染分割线；#219 勘误 inputID 后
     *  尾部分割线的去重条件（messageId 已在列表）又被骨架满足 → 进行中态两边都
     *  不显示，直到完成才蹦出。按 role+对位认领：started 到达后骨架即渲染进行中
     *  分割线，完成后同 item 原位切完成态（Q13 本意）。注意 steer 排队期（skeleton
     *  已入列但 started 未到）不认领——compactionState 未置，认领会渲染成静止
     *  「已压缩」误导。
     *
     *  热修（2026-08-26 用户即报，#226）：初版条件
     *  `firstOrNull()?.summary.isNullOrBlank()` 在**无** Compaction part 的普通
     *  用户消息上求值 = null.isNullOrBlank() = true → 全部用户气泡被隐藏（Kotlin
     *  空安全惯用陷阱）。必须显式要求 part 存在且 summary 为空才算触发消息。 */
    fun userTriggerClaim(
        msg: ChatMessage,
        activeState: CompactionStateInfo?,
        latchedCompactionMsgId: String?,
    ): CompactionDividerSpec {
        val compactionPart = msg.parts.filterIsInstance<Part.Compaction>().firstOrNull()
        // V1 触发消息（role=user + Compaction part、无摘要——V1 契约里摘要住在
        // 后随 assistant(agent=compaction) 消息）
        val isV1CompactionTriggerMsg =
            (msg.message as? Message.User)?.role == "user" &&
                compactionPart != null &&
                compactionPart.summary.isNullOrBlank()
        if (isV1CompactionTriggerMsg) return CompactionDividerSpec.V1TriggerHidden
        val isCompactionTrigger = msg.parts.any { it is Part.Compaction } ||
            ((msg.message as? Message.User)?.role == "compaction" &&
                (activeState != null || msg.message.id == latchedCompactionMsgId))
        if (!isCompactionTrigger) return CompactionDividerSpec.NotCompaction
        return CompactionDividerSpec.Trigger(
            expansionKey = msg.message.id,
            activeState = activeState,
            summary = compactionPart?.summary,
            failed = compactionPart?.failed ?: false,
        )
    }

    // ===== ③ banner 计数派生（C4-C） =====

    /** bannerCount/revealBannerCount 压缩项：由尾部兜底认领决策派生（同源单一
     *  真相源）——streamClaimed = 活跃且消息流内已有 item 认领；tailFallback =
     *  活跃且无人认领（尾部兜底 item 将渲染）。非活跃两项皆 false。 */
    fun bannerTerms(
        compaction: CompactionStateInfo?,
        displayItemMessageIds: Set<String>,
        v1SummaryInList: Boolean,
    ): CompactionBannerTerms {
        val tail = tailSpec(compaction, displayItemMessageIds, v1SummaryInList) != null
        return CompactionBannerTerms(
            streamClaimed = compaction?.isActive == true && !tail,
            tailFallback = tail,
        )
    }
}
