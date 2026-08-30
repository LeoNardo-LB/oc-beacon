package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PartIdContract
import dev.leonardo.ocbeacon.domain.model.ToolState

/**
 * 消息/part 合并代数——从 MessageEventHandler 抽出的纯函数集（#234 战役一）。
 *
 * 本文件只住**纯函数**：无协程、无 StateFlow、无 Room、无时钟——所有时间值
 * 来自输入数据本身（骨架播种/完成标记等 `System.currentTimeMillis` 决策
 * 留在 handler 壳）。行为等价性由两条线锁住：
 * - handler 侧既有测试（8 文件 81 例，经公共 API——SSE 事件/三策略 upsert）
 * - 本文件的直测（MessageMergeEngineTest：delta 应用/kind 推断/滤空性质）
 *
 * 「零信息 part 不得进入系统」不变量（#223/#228/#229/#230 bug 族）在
 * [mergePartsList] 双侧滤空与注册决策（后续 Commit 收编 resolvePartRegistration）
 * 处执行——所有经本引擎进出的 part 路径自动继承该不变量。
 */
internal object MessageMergeEngine {

    // ============ part 合并 ============

    /**
     * 合并 Part 更新：对于 Text/Reasoning，SSE delta 驱动的文本优先。
     *
     * 流式传输期间，SSE delta 增量累积文本。REST 同步可能返回比 delta 累积
     * 更新的快照（例如 REST 返回"你好世界"而 SSE 只累积了"你好"）。
     * 若我们取 REST 快照的更长文本，后续的 SSE delta（服务器在 REST 调用前
     * 已发送）会追加快照中已有的内容，导致重复。
     *
     * 修复：若现有（SSE）已有任何文本，保留它——SSE 是流式传输的真相源。
     * 仅当现有为空（part 刚创建）时才取传入的文本。
     * 始终取传入的元数据（time 等），因为 REST 可能有更新的元数据。
     */
    fun mergePart(existing: Part, incoming: Part): Part {
        return when {
            existing is Part.Text && incoming is Part.Text -> {
                // #109：时间取兜底链——ended 事件 start=0（未知）时用 started
                // 记录的本地时刻，REST 真实时间戳（>0）优先。
                val time = Part.Text.Time(
                    start = incoming.time?.start?.takeIf { it > 0 }
                        ?: existing.time?.start?.takeIf { it > 0 }
                        ?: (incoming.time?.end ?: existing.time?.end) ?: 0L,
                    end = incoming.time?.end ?: existing.time?.end
                )
                // 2026-08-16 修复（内容中段重复，对齐官方 research/04 P0）：
                // text.ended 是官方的**全量值边界**（"Ended is the replayable
                // full-value boundary"——session-event.ts:209），携带完整最终
                // 文本，必须**直接覆盖**。原"更长文本胜出"启发式在 REST 快照
                // 与 SSE 累积前缀不一致时选错基线 → 中段内容重复（用户实测
                // "最后一句话被随机重复"）。
                // 判定 ended：incoming 是 text.ended 映射（时间兜底链后
                // end != null 且非空文本全量）——ended 映射处显式标记：
                // incoming.text 含完整文本且 end!=0。保守策略：
                // incoming 带 end 时间戳（ended/REST 语义）→ 覆盖；
                // 纯 started/delta 路径（无 end）→ 保留更长者（流式保护）。
                val isTerminal = (incoming.time?.end ?: 0L) != 0L
                if (isTerminal || incoming.text.length >= existing.text.length) incoming.copy(time = time)
                else existing.copy(time = time, metadata = incoming.metadata)
            }
            existing is Part.Reasoning && incoming is Part.Reasoning -> {
                val time = Part.Reasoning.Time(
                    start = incoming.time?.start?.takeIf { it > 0 }
                        ?: existing.time?.start?.takeIf { it > 0 }
                        ?: (incoming.time?.end ?: existing.time?.end) ?: 0L,
                    end = incoming.time?.end ?: existing.time?.end
                )
                if (incoming.text.length >= existing.text.length) incoming.copy(time = time)
                else existing.copy(time = time, metadata = incoming.metadata)
            }
            existing is Part.Tool && incoming is Part.Tool -> {
                var merged = incoming
                // 工具名保留：v2 中间事件（tool.input.ended/called 等）不带 name 字段，
                // 仅 tool.input.started 携带——若 incoming 缺名则保留 existing 的
                val incomingTool = merged.tool
                val existingTool = existing.tool
                if (incomingTool.isBlank() && existingTool.isNotBlank()) {
                    merged = merged.copy(tool = existingTool)
                }
                // input 保留：incoming 中间状态可能缺 input（input.ended 只有 output）
                val incomingInput = merged.stateInput()
                val existingInput = existing.stateInput()
                if (incomingInput.isEmpty() && existingInput.isNotEmpty()) {
                    merged = merged.withStateInput(existingInput)
                }
                // Tool part：SSE 中间状态（Running）可能缺少 metadata（如 subagent 子智能体会话 ID），
                // 但 REST 快照/早期 SSE 已完成状态包含完整 metadata。
                // 若 incoming 缺少 metadata 而 existing 有，保留 existing 的 metadata，
                // 避免 subagent 卡片失去子智能体会话跳转能力（backlog: subagent 卡片不可点击）。
                val incomingMetadata = merged.stateMetadata()
                val existingMetadata = existing.stateMetadata()
                if (incomingMetadata.isNullOrEmpty() && !existingMetadata.isNullOrEmpty()) {
                    merged = merged.withStateMetadata(existingMetadata)
                }
                merged
            }
            else -> incoming
        }
    }

    /** 提取 Part.Tool 的 state.metadata（各 ToolState 子类）。 */
    private fun Part.Tool.stateMetadata(): Map<String, kotlinx.serialization.json.JsonElement>? = when (val s = state) {
        is ToolState.Pending -> null // Pending 无 metadata 字段
        is ToolState.Running -> s.metadata
        is ToolState.Completed -> s.metadata
        is ToolState.Error -> s.metadata
    }

    /** 提取 Part.Tool 的 state.input（各 ToolState 子类）。 */
    private fun Part.Tool.stateInput(): Map<String, kotlinx.serialization.json.JsonElement> = when (val s = state) {
        is ToolState.Pending -> s.input
        is ToolState.Running -> s.input
        is ToolState.Completed -> s.input
        is ToolState.Error -> s.input
    }

    /** 用保留的 input 重建 Part.Tool（state 替换为携带 input 的副本）。 */
    private fun Part.Tool.withStateInput(
        input: Map<String, kotlinx.serialization.json.JsonElement>
    ): Part.Tool = copy(
        state = when (val s = state) {
            is ToolState.Pending -> s.copy(input = input)
            is ToolState.Running -> s.copy(input = input)
            is ToolState.Completed -> s.copy(input = input)
            is ToolState.Error -> s.copy(input = input)
        }
    )

    /** 用保留的 metadata 重建 Part.Tool（state 替换为携带 metadata 的副本）。 */
    private fun Part.Tool.withStateMetadata(
        metadata: Map<String, kotlinx.serialization.json.JsonElement>
    ): Part.Tool = copy(
        state = when (val s = state) {
            is ToolState.Pending -> s // Pending 无 metadata 字段，保留原样
            is ToolState.Running -> s.copy(metadata = metadata)
            is ToolState.Completed -> s.copy(metadata = metadata)
            is ToolState.Error -> s.copy(metadata = metadata)
        }
    )

    fun mergePartsList(existingParts: List<Part>, incomingParts: List<Part>): List<Part> {
        // 2026-08-12 根因修复（流式内容消失）：
        // 1. incoming 为空（REST 流式 turn content 未提交 / SSE 部分更新）时
        //    保留 existing——原实现返回 [] 清空 SSE 累积文本。
        // 2. 保留 incoming 中不存在的已有 parts：REST text part id="" 与 SSE
        //    派生 id="msg_ord_N" 契约不一致（V2Mappers.kt:294 vs V2SseMapper
        //    derivePartId）→ 原实现丢弃 existing 独有（SSE 累积）文本。
        //    顺序：incoming（REST 权威）在前，SSE 独有追加在后；完成后 REST
        //    全量返回 → preserved 为空 → 顺序完全按 REST。
        if (incomingParts.isEmpty()) return existingParts
        // #228（2026-08-26 用户报「点会话加载很久+页面乱」真机 MIUIScout 5s HANG 栈定音）：
        // #223 的空 part 过滤只作用 existing 侧（preserved）——incoming 侧携带空 part 时
        //（Room 炸弹行二次种子：实测一条消息 4488 个空 reasoning part 从 Room 回灌热视图）
        // 它们长驱直入 dedupOverlappingTextParts 的 O(N²) 双层循环（每对两次 isNewPartId
        // 短串扫描）→ 2000 万+迭代在主线程跑数秒。对称补全：incoming 侧同样滤空——
        // 空 Text/Reasoning 零信息（started 后从未收到 delta；delta 有 idx<0 重建兜底），
        // 且热视图里的炸弹在每次 merge 后被逐步清除。sanitized 全空（整批都是空 part）
        // 时对 existing 也做一次滤空再返回——该路径原本直通 existing，炸弹永生。
        val sanitizedIncoming = incomingParts.filter { !isEmptyStreamPart(it) }
        if (sanitizedIncoming.isEmpty()) {
            return existingParts.filter { !isEmptyStreamPart(it) }
        }
        val existingById = existingParts.associateBy { it.id }
        val merged = sanitizedIncoming.map { incoming ->
            val existing = existingById[incoming.id]
            if (existing != null) mergePart(existing, incoming) else incoming
        }
        val incomingIds = sanitizedIncoming.mapTo(HashSet()) { it.id }
        // #223（空 part 炸弹，2026-08-25 真机 jdb + Room 双证）：SSE 的
        // reasoning/text.started 每事件建一个空 part（ordinal 递增），REST
        // 权威刷新不携带它们 → 此处被无限保留——实测一条消息累积 110 个空
        // reasoning part，进会话即 merge/dedup 风暴打挂主线程。服务器侧
        // 无此数据（同会话 REST 无 >10 part 消息）= 纯客户端残留。修复：
        // preserved 过滤掉**空文本**的 Text/Reasoning 残留——空 part 零信息；
        // 正在累积的 part 文本非空不受影响；被误删后到达的 delta 有 idx<0
        // 重建兜底（applyDelta）。工具 part（payload）不动。
        val preserved = existingParts.filter { it.id !in incomingIds && !isEmptyStreamPart(it) }
        return dedupOverlappingTextParts(merged + preserved)
    }

    /**
     * #109（D2-01 兜底）：part id 契约演进期间（Room 旧数据 id=""、旧版派生
     * id `msg_ord_N` 与新版 `msg_type_ord_N`），同一逻辑 part 的两个版本可能
     * 同时存活 → 已完结消息文本双份渲染。对 Text/Reasoning 按**内容重叠**
     * （相等/前缀）去重：至少一侧 id 非新版契约时才合并（两条新版 id 不同的
     * part 视为真不同），保留文本更长、等长优先非空 id 的一条。
     * 与注册路径的 #87b 空内容匹配防御同一权衡。
     *
     * #229（#228 根因补完，2026-08-26 用户质询「修复的是根因吗」）：#223 的
     * 前置跳过只省了 overlaps 文本比较，**pair 枚举本身仍是 O(N²)**——两条
     * 新版契约 id 各做一次 contains 短串扫描，N=4488 时 2000 万次迭代照样
     * 烧秒级（#228 MIUIScout 栈定音，contains 即热点帧）。结构性根治：
     * ① isNewPartId 结果按 id 记忆化（每 id 至多 2 次扫描）；② 维护同类
     * 「legacy-id 子集」桶——新版契约 p 只可能与 legacy-id 同类 part 重叠，
     * 只扫 legacy 桶（炸弹全为新版契约 → 桶空 → 每元素 O(1)）；legacy p
     * 仍扫全桶（语义不变）。复杂度 O(N + M×N)，M=legacy 条数（迁移期遗留，
     * 现实 ≤2/消息）。胜者替换时同步桶成员（替换罕见，含 O(桶) 查删可接受）。
     */
    fun dedupOverlappingTextParts(parts: List<Part>): List<Part> {
        if (parts.size < 2) return parts
        val result = mutableListOf<Part>()
        val isNewCache = HashMap<String, Boolean>(parts.size * 2)
        fun isNew(id: String): Boolean =
            isNewCache.getOrPut(id) { PartIdContract.isDerived(id) }
        // 同类扫描桶：全量索引 + legacy-id 子集索引（按 kind 分桶，非文本 part 不入桶）
        val textAll = mutableListOf<Int>()
        val textLegacy = mutableListOf<Int>()
        val reasonAll = mutableListOf<Int>()
        val reasonLegacy = mutableListOf<Int>()
        outer@ for (p in parts) {
            val (all, legacy) = when (p) {
                is Part.Text -> textAll to textLegacy
                is Part.Reasoning -> reasonAll to reasonLegacy
                else -> {
                    result.add(p)
                    continue@outer
                }
            }
            val pIsNew = isNew(p.id)
            // #229：新版契约 id 只需与 legacy-id 同类 part 比对（与 #223 前置
            // 跳过语义等价：双侧新版 → 无候选 → 直接入列）
            val scan = if (pIsNew) legacy else all
            for (i in scan) {
                val r = result[i]
                val rt = (r as? Part.Text)?.text ?: (r as? Part.Reasoning)?.text ?: continue
                val pt = (p as? Part.Text)?.text ?: (p as? Part.Reasoning)?.text ?: continue
                val overlaps = rt == pt ||
                    (rt.length <= pt.length && pt.startsWith(rt)) ||
                    (pt.length <= rt.length && rt.startsWith(pt))
                if (overlaps) {
                    val winner = if (pt.length > rt.length || (pt.length == rt.length && p.id.isNotBlank())) p else r
                    result[i] = winner
                    // 桶成员同步：索引 i 保持在 all 桶；legacy 桶按胜者契约属性增删
                    val winnerIsNew = isNew(winner.id)
                    val inLegacy = legacy.contains(i)
                    if (winnerIsNew && inLegacy) legacy.remove(i)
                    else if (!winnerIsNew && !inLegacy) legacy.add(i)
                    continue@outer
                }
            }
            result.add(p)
            val idx = result.size - 1
            all.add(idx)
            if (!pIsNew) legacy.add(idx)
        }
        return result
    }

    // ============ 谓词（#223/#230 语义）============

    /** #223：SSE 残留的空 Text/Reasoning part（started 后从未收到 delta）。 */
    fun isEmptyStreamPart(part: Part): Boolean = when (part) {
        is Part.Text -> part.text.isBlank()
        is Part.Reasoning -> part.text.isBlank()
        else -> false
    }

    /** #223：同为流式文本类（Text/Reasoning 同 kind）。 */
    fun sameStreamKind(a: Part, b: Part): Boolean = when {
        a is Part.Text && b is Part.Text -> true
        a is Part.Reasoning && b is Part.Reasoning -> true
        else -> false
    }

    /** #109 派生 id 契约判定——#234 战役二起委托 [PartIdContract]（生产/消费单一权威）。 */
    fun isDerivedOrdinalId(id: String): Boolean = PartIdContract.isDerived(id)

    /** 零信息 part 过滤器（#228 语义）——appendOnly 等直通路径的封洞入口。 */
    fun sanitized(parts: List<Part>): List<Part> = parts.filter { !isEmptyStreamPart(it) }

    // ============ 注册决策（#234 战役二自 handler 收编）============

    /** 新 part 到达已注册列表时的处置决策（策略本体在引擎，消费方只按分支执行）。 */
    sealed interface PartRegistration {
        /** id 命中已注册 part：与该位合并（mergePart）。 */
        data class MergeAt(val index: Int) : PartRegistration

        /** #87b 空 id Text 按内容匹配（相等/前缀）合并。 */
        data class MergeByContent(val index: Int) : PartRegistration

        /** #223：派生契约 id 的同 kind 空 started 重复——丢弃（空对空零信息损失）。 */
        object DropZeroInfoDuplicate : PartRegistration

        /** #230：派生契约 id 的首个空 started——不注册（delta idx<0 兜底重建）。 */
        object DropZeroInfo : PartRegistration

        /** 新 part 入列（文本保持不变——delta 丢失时文本不剥除）。 */
        data class Add(val part: Part) : PartRegistration
    }

    /**
     * part 注册策略（原 handleMessagePartUpdated 决策树，#87b/#223/#230 语义原样收编）。
     *
     * 决策顺序：
     * 1. id 命中 → [PartRegistration.MergeAt]
     * 2. 防御（#87b）：part ID 契约差异——REST 快照的 text part id 为空串 vs SSE 的
     *    id 为 prt_xxx。按 id 找不到时直接新增会出现同消息两条文本 part → 文本
     *    重复渲染（压测实测重复文本）。对空 id 的 Text part 按内容匹配（相等/前缀）
     *    合并而非新增。
     * 3. #223（空 part 增殖源头，2026-08-25 真机定音）：部分服务器链路对每个
     *    reasoning 块发 started（ordinal 递增）而 delta 恒进 ordinal 0——空
     *    started part 无限增殖（实测单消息 4488 part/4487 空，DB 持续 INSERT）。
     *    仅对派生契约 id 且同 kind 已有空 part 时丢弃新空 started：零信息损失
     *    （空对空），后续若有 delta 到达新 ordinal，delta 路径 idx<0 兜底重建。
     *    自定义 id 的两个空 part（如 p1/p2）可能 legitimately 不同——不折叠。
     *    带 ended 的非空覆盖不经过此分支（text 非空）。
     * 4. #230（#228/#229 残余通道封堵，2026-08-26）：#223 只在同 kind 已有空
     *    part 时丢弃——首个空 started 仍会注册进内存 → persistSseUpdate 快照
     *    落 Room（text=NULL 行）→ 开会话再写、启动清扫再删的死循环。零信息
     *    part 一律不注册：后续 delta 到达时 idx<0 兜底重建，首个非空 delta/
     *    updated 再入列。
     * 5. 其余 → [PartRegistration.Add]（文本保持不变：delta 被错过时文本将
     *    永久丢失；endsWith 去重 + mergePart 更长文本胜出一起处理潜在重叠）。
     */
    fun resolvePartRegistration(existingParts: List<Part>, incoming: Part): PartRegistration {
        val idx = existingParts.indexOfFirst { it.id == incoming.id }
        if (idx >= 0) return PartRegistration.MergeAt(idx)
        if (incoming.id.isBlank() && incoming is Part.Text) {
            val contentMatchIdx = existingParts.indexOfFirst { existing ->
                existing is Part.Text &&
                    (existing.text == incoming.text ||
                        existing.text.startsWith(incoming.text) ||
                        incoming.text.startsWith(existing.text))
            }
            if (contentMatchIdx >= 0) return PartRegistration.MergeByContent(contentMatchIdx)
        }
        // #265 E2E 竞态收口：完结权威替换（text.ended 全量值 + 服务端 partId）
        // 与流式派生 id 存在换代漂移——同 kind 重叠文本 part 是同一逻辑 part 的
        // 两个版本，不得作为新 part 追加（真机 E2E 实证：滞留尾 delta 走 idx<0
        // 兜底新建 → 结尾句渲染两遍）。判定：非空 Text incoming 与任一既有同
        // kind part 文本前缀相容（一方以另一方开头）→ MergeAt 到既有位置，
        // 由 mergePart 的 isTerminal 覆盖语义接手。dedupOverlappingTextParts
        // 的「重叠保长」语义在此前移到注册时刻。
        if (incoming is Part.Text && incoming.text.isNotEmpty()) {
            val overlapIdx = existingParts.indexOfFirst { existing ->
                existing is Part.Text && existing.text.isNotEmpty() && (
                    incoming.text.startsWith(existing.text) ||
                        existing.text.startsWith(incoming.text)
                    )
            }
            if (overlapIdx >= 0) return PartRegistration.MergeAt(overlapIdx)
        }
        return when {
            isDerivedOrdinalId(incoming.id) && isEmptyStreamPart(incoming) &&
                existingParts.any {
                    sameStreamKind(it, incoming) && isEmptyStreamPart(it) && isDerivedOrdinalId(it.id)
                } -> PartRegistration.DropZeroInfoDuplicate
            isDerivedOrdinalId(incoming.id) && isEmptyStreamPart(incoming) -> PartRegistration.DropZeroInfo
            else -> PartRegistration.Add(incoming)
        }
    }

    // ============ 消息合并 ============

    /**
     * 合并两个按 [Message.time.created] 升序的消息列表，按 id 去重。
     *
     * 等价于 `(existing + incomingSorted).distinctBy { it.id }.map { merge(it) }.sortedBy { it.time.created }`，
     * 但复杂度 O(existing.size + incomingSorted.size)（线性两路归并），
     * 替代 O((n+m) log(n+m)) 全量排序——1000-2000 条会话每次 upsert 节省约 10000-40000 次比较。
     *
     * **前提**（由调用方写入路径维持，实际语义成立）：
     * - [existing] 已按 created 升序（所有写入路径输出有序）
     * - [incomingSorted] 由调用方保证按 created 升序（Kotlin `sortedBy` 稳定）
     * - 同 id 消息在两列表中 created 一致：消息创建时间为固有属性（服务器不变更），
     *   [mergeMessageMeta] 仅修改 completed（不改 created）；
     *   [MergeStrategy.REST_AUTHORITY] 虽用 incoming 完全覆盖，但 REST 同一消息的 created 与 SSE 一致
     *
     * **去重 / 稳定语义**（与原 distinctBy + 稳定 sortBy 完全一致）：
     * - 同 id：取 [merge] 结果，位置取 existing 的位置
     * - existing 独有 id：保留 existing
     * - incoming 独有 id：插入到 created 升序对应位置
     * - 相同 created 不同 id：existing 在前（稳定排序——与 `(ex+inc).distinctBy.sortedBy` 中
     *   distinctBy 保留 existing 在前 + sortedBy 稳定保持原序一致）
     *
     * **Bug 1 修复（同 created 顺序反转，2026-08-10）**：当 existing 项的 id 被 incoming 覆盖且
     * 与 incoming 中某独有项同 created 时，旧实现跳过 existing 项后让 incoming 独有项先入 result，
     * 导致合并版本排到独有项之后。修复：existing 项被覆盖时立即 merge 并入 result（保持原位），
     * 用 [added] 标记防止 incoming 中该 id 再次加入。
     *
     * **Bug 2 修复（同 id 不去重，2026-08-10）**：existing/incoming 内含同 id 重复项时，
     * 旧实现全部追加。修复：[added] 集合统一跟踪已加入的 id（等价于 distinctBy 的 seen 集合），
     * 后续遇到同 id 跳过（保留首个，与 distinctBy 语义一致）。
     */
    fun mergeSortedMessages(
        existing: List<Message>,
        incomingSorted: List<Message>,
        merge: (existingMsg: Message, incomingMsg: Message) -> Message,
    ): List<Message> {
        // O(m)：incoming id → 首个版本（与 distinctBy 保留首个语义一致，不用 associateBy 因其保留末个）
        val incomingById = LinkedHashMap<String, Message>(incomingSorted.size)
        for (msg in incomingSorted) {
            if (msg.id !in incomingById) incomingById[msg.id] = msg
        }
        // 已加入 result 的 id 集合：等价于 distinctBy 的 seen 集合，
        // 统一处理所有来源（existing/incoming 内部重复、跨列表同 id）的去重。
        val added = HashSet<String>()
        val result = ArrayList<Message>(existing.size + incomingSorted.size)
        var i = 0  // existing 游标
        var j = 0  // incomingSorted 游标
        while (i < existing.size && j < incomingSorted.size) {
            val e = existing[i]
            val inc = incomingSorted[j]
            when {
                e.id == inc.id -> {
                    // 同 id：合并，前进两个游标（等价于 distinctBy 保留 existing 位置 + map 替换内容）
                    // 检查 added：existing/incoming 内部可能同 id 重复，首次已处理，后续只前进游标
                    if (e.id !in added) {
                        result.add(merge(e, inc))
                        added.add(e.id)
                    }
                    i++; j++
                }
                e.time.created <= inc.time.created -> {
                    // existing 较早或并列（稳定排序：existing 优先）
                    if (e.id !in added) {
                        if (e.id in incomingById) {
                            // Bug 1 修复：e 被 incoming 覆盖——立即 merge 保持原位，
                            // 否则 incoming 中与 e 同 created 的独有项会错误地排到 e 的合并版本前
                            result.add(merge(e, incomingById[e.id]!!))
                        } else {
                            result.add(e)
                        }
                        added.add(e.id)
                    }
                    i++
                }
                else -> {
                    // incoming 较早：跳过已加入的同 id 条目（Bug 2 修复——incoming 内同 id 重复）
                    if (inc.id !in added) {
                        result.add(inc)
                        added.add(inc.id)
                    }
                    j++
                }
            }
        }
        // existing 剩余
        while (i < existing.size) {
            val e = existing[i]
            if (e.id !in added) {
                val incVersion = incomingById[e.id]
                if (incVersion != null) {
                    // e 被 incoming 覆盖且尚未加入：merge 保持原位
                    result.add(merge(e, incVersion))
                } else {
                    result.add(e)
                }
                added.add(e.id)
            }
            i++
        }
        // incoming 剩余：跳过已加入的（Bug 2 修复）
        while (j < incomingSorted.size) {
            val inc = incomingSorted[j]
            if (inc.id !in added) {
                result.add(inc)
                added.add(inc.id)
            }
            j++
        }
        return result
    }

    /**
     * 合并消息的 SSE 和 REST 版本。
     * SSE 对内容更新（流式传输），但 REST 可能有 SSE 尚未投递的完成信息。
     *
     * 注意：REST completed 仅在 SSE 尚未完成时作为兜底合并（SSE 完成事件
     * 丢失时防止消息永不完成）；SSE 已完成则完全信任 SSE。
     */
    fun mergeMessageMeta(sse: Message, rest: Message): Message {
        // 对于用户消息：REST 是权威的（无流式传输）
        if (sse is Message.User) return rest
        if (sse !is Message.Assistant) return rest

        // 2026-08-15：REST 元数据兜底——SSE 侧 modelId/providerId/agent 为空时
        // 采纳 REST 值。V2 SSE step.ended 契约本就不含模型信息（曾整替换抹掉
        // step.started 写入的值），REST listMessages 的 model 映射完整——
        // 让 REST 兜底路径真正能修复统计栏的模型名。
        val restA = rest as? Message.Assistant
        fun withMeta(m: Message.Assistant): Message.Assistant = if (restA == null) m else m.copy(
            modelId = m.modelId ?: restA.modelId,
            providerId = m.providerId ?: restA.providerId,
            agent = m.agent ?: restA.agent
        )

        // 对于 Assistant 消息：
        // - 若 SSE 显示已完成（流式结束），完全信任 SSE
        // - 若 SSE 显示未完成但 REST 显示已完成，信任 REST 的完成时间
        //   但保留 SSE 的其他字段（finish、tokens、cost 可能更新）
        return if (sse.time.completed != null) {
            withMeta(sse)  // SSE 拥有最终状态，优先使用它（模型元数据 REST 兜底）
        } else if (rest.time.completed != null) {
            // REST 显示已完成但 SSE 尚未看到——合并完成时间
            withMeta(sse.copy(time = sse.time.copy(completed = rest.time.completed)))
        } else {
            // 两者都未完成——优先 SSE（更新的流式状态）
            withMeta(sse)
        }
    }

    /**
     * 2026-08-15：Assistant 消息非空字段合并（统计栏丢模型/耗时修复）。
     *
     * V2 SSE 的 step.ended 事件不含 modelId/providerId/agent（服务器契约就没有），
     * 但携带 tokens/cost；step.started 相反（带模型信息、不带 tokens）。原实现
     * 整对象替换会让两个事件互相抹掉（tokens 在而模型无的不对称）。合并规则：
     * - incoming 非空的字段以 incoming 为准（REST 权威数据可覆盖 SSE 估计值）
     * - incoming 为空的字段保留 existing（step.ended 不抹 step.started 的模型）
     * - time.created 取较早值：step.ended 映射用本地当前时刻，晚于 step.started
     *   的原始时刻——顶替会让单步消息耗时 ≈ 0 → 统计栏耗时被 `>0` 门隐藏
     * - time.completed 以 incoming 非空为准（V2 SSE 从不携带，由 markSessionIdle
     *   或 REST 兜底补齐）
     */
    fun mergeAssistantMeta(existing: Message.Assistant, incoming: Message.Assistant): Message.Assistant =
        incoming.copy(
            modelId = incoming.modelId ?: existing.modelId,
            providerId = incoming.providerId ?: existing.providerId,
            agent = incoming.agent ?: existing.agent,
            mode = incoming.mode ?: existing.mode,
            parentId = incoming.parentId.ifBlank { existing.parentId },
            cost = incoming.cost ?: existing.cost,
            tokens = incoming.tokens ?: existing.tokens,
            finish = incoming.finish ?: existing.finish,
            time = incoming.time.copy(
                created = minOf(existing.time.created, incoming.time.created),
                completed = incoming.time.completed ?: existing.time.completed
            )
        )

    // ============ delta 应用（自 flushPendingDeltas 内联块抽出，#234）============

    /**
     * 将单个 delta 应用到消息的 part 列表（48ms 批处理 flush 的每条目变换）。
     *
     * - part 已注册：文本追加（Text 有 endsWith 去重——批内重叠 delta 不重复拼接；
     *   Reasoning 直拼接）
     * - part 未注册（空 started 被 #230 丢弃 / 事件丢失）：按 [kind] 重建——
     *   #223 已验证的 idx<0 兜底机制，首个非空 delta 即重建注册。
     */
    fun applyDelta(
        parts: List<Part>,
        partId: String,
        sessionId: String,
        messageId: String,
        kind: String,
        delta: String,
    ): List<Part> {
        val messageParts = parts.toMutableList()
        val idx = messageParts.indexOfFirst { it.id == partId }
        if (idx >= 0) {
            val part = messageParts[idx]
            val newPart = when (part) {
                is Part.Text -> {
                    if (part.text.endsWith(delta)) part  // 去重
                    else part.copy(text = part.text + delta)
                }
                is Part.Reasoning -> part.copy(text = part.text + delta)
                else -> part
            }
            messageParts[idx] = newPart
        } else {
            // #265 E2E 竞态守卫：完结全量替换（authoritative part 重建，partId
            // 换代为服务端序）之后，批缓冲中滞留的过期 delta 才 flush——其内容
            // 已包含在既有同 kind part 的全文里。若仍按 #223 兜底新建 part，
            // 会被渲染两遍（真机 E2E 实证：尾 delta 116 字在完结替换后 flush
            // → 结尾句 ×2）。判定：同 kind 既有 part 全文已包含该 delta →
            // 丢弃（视为已合并）；#223 真重建场景（内容从未到达，delta 不在
            // 任何既有 part 中）不受影响。
            val sameKindText = parts.joinToString("") {
                when {
                    it is Part.Text && kind != "reasoning" -> it.text
                    it is Part.Reasoning && kind == "reasoning" -> it.text
                    else -> ""
                }
            }
            if (delta.isNotEmpty() && sameKindText.contains(delta)) return parts
            if (kind == "reasoning") {
                messageParts.add(Part.Reasoning(
                    id = partId,
                    sessionId = sessionId,
                    messageId = messageId,
                    text = delta
                ))
            } else {
                messageParts.add(Part.Text(
                    id = partId,
                    sessionId = sessionId,
                    messageId = messageId,
                    text = delta
                ))
            }
        }
        return messageParts
    }

    /**
     * delta 到达时 part 尚未注册的 kind 推断（#230）：
     * 已注册 part 按其自身类型；未注册按派生 id 契约（`_reasoning_ord_`）——
     * 此前硬编码 "text" 会让 reasoning delta 以正文 kind 重建（kind 错乱）。
     */
    fun inferDeltaKind(existingParts: List<Part>?, partId: String): String {
        val existingPart = existingParts?.firstOrNull { it.id == partId }
        return when {
            existingPart is Part.Reasoning -> "reasoning"
            existingPart != null -> "text"
            else -> PartIdContract.kindOf(partId)
        }
    }
}
