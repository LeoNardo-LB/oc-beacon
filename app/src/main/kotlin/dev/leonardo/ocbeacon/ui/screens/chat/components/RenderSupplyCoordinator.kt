package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 渲染供给协调器（Render Supply）——聊天列表视口前方的渲染资源预备决策的
 * 唯一决策点（2026-08-21 架构评审候选 1：从 ChatMessageList ~190 行视口驱动
 * LaunchedEffect 外移；术语见根目录 CONTEXT.md）。
 *
 * 职责（全部收进本 implementation，调用方无需知晓）：
 * - 窗口计算：视口 ±[PREPARSE_AHEAD]，display 粒度（entry 先映射 displayIndex
 *   ——chunk 化后 ±8 entries ≈ ±2 turns，物理窗口缩水会供给不足）
 * - 预解析供给：窗口内 assistant 长文本 part 提前后台解析（RenderReadinessRegistry）
 * - 流式 turn 禁预解析：流式中途的部分文本快照若被预解析，registry 永不重析
 *   → 分片渲染部分 AST = 回复尾部永久截断（极具迷惑性）
 * - 分片时机安全决策：巨型 part 解析完成 → plan 入 pending 队列；仅当所属
 *   turn 离开窗口（视口外防线）且跳转安全（相位非进行中 + 终点稳定窗口外）
 *   时按 partId 反查当前 display index 提交（陈旧索引根治）
 * - 有界性：preparseSeenKeys LRU 上限，淘汰联动 registry.remove（防 AST 无界增长）
 * - 流式刚结束 turn 延迟分片（recentStreamedTurnKeys——防视口内 key 从 1 裂成 N）
 *
 * 接口契约：单方法推送视口快照 + 流式结束通知；输出经两条只读 StateFlow。
 * 线程：所有方法与解析回调假定主线程单线程（Compose 桥接保证）。
 */
internal class RenderSupplyCoordinator(
    private val registry: RenderReadinessRegistry,
    private val parseScope: CoroutineScope,
    private val jumpPhase: StateFlow<JumpPhase>,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    /** 已提交分片计划（partId → plan）——buildChatEntries 消费。 */
    private val _chunkPlans = MutableStateFlow<Map<String, MdChunkPlan>>(emptyMap())
    val chunkPlans: StateFlow<Map<String, MdChunkPlan>> = _chunkPlans

    /** 待提交分片计划（partId → plan + 入队时 display index[仅取证]）——私有。 */
    private val pendingChunkPlans = LinkedHashMap<String, Pair<MdChunkPlan, Int>>()

    /** 流式刚结束的 turn key（延迟分片——滚出窗口后释放）。 */
    private val _recentStreamedTurnKeys = MutableStateFlow<Set<String>>(emptySet())
    val recentStreamedTurnKeys: StateFlow<Set<String>> = _recentStreamedTurnKeys

    /** 预解析条目 LRU（#98 防无界增长）。 */
    private val preparseSeenKeys = LinkedHashSet<String>()

    /** 最近一次跳转终点时刻（clock 基，单调）——稳定窗口门控用。
     * 阶段 2 收编：原为 ChatMessageList 跨 effect 共享变量（写方=解锁
     * effect、读方=驱动 collect）——现写读同在模块内，耦合消灭。 */
    private var lastJumpEndAtMillis = 0L

    init {
        // 自记跳转终点（阶段 2）：相位到终态（Displayed/Failed）即打点。
        // 与 ChatMessageList 的 autoLoad 解锁 effect 各自独立收集同一
        // StateFlow——互不影响（collectLatest 语义仅本协程内取消旧块）。
        parseScope.launch {
            jumpPhase.collectLatest { ph ->
                if (ph is JumpPhase.Displayed || ph is JumpPhase.Failed) {
                    lastJumpEndAtMillis = clock()
                }
            }
        }
    }

    /** 流式结束瞬间记录 turn key（由 Compose 桥以当时快照算出 key 后调用）。 */
    fun noteStreamTurnEnded(turnKey: String) {
        _recentStreamedTurnKeys.value = _recentStreamedTurnKeys.value + turnKey
    }

    /**
     * 视口变化驱动：窗口计算 + 预解析供给 + LRU 淘汰 + 延迟分片释放 +
     * pending 分片计划提交（双门控 + partId 反查）。
     */
    fun onViewportChanged(firstIdx: Int, lastIdx: Int, world: RenderSupplyWorld) {
        val items = world.displayItems
        val groups = world.turnGroups
        val entriesNow = world.entries
        val bannerCount = world.bannerCount
        val window = LinkedHashSet<String>()
        // 窗口语义保持 display 粒度（entry index 先映射 displayIndex 再扩展——
        // chunk 化后 ±8 entries ≈ ±2 turns，物理窗口缩水会导致预解析供给不足）。
        val firstDisplay = entriesNow.entryDisplayIndex
            .getOrNull((firstIdx - bannerCount).coerceAtLeast(0)) ?: 0
        val lastDisplay = entriesNow.entryDisplayIndex
            .getOrNull(lastIdx - bannerCount) ?: (items.size - 1)
        val head = (firstDisplay - PREPARSE_AHEAD).coerceAtLeast(0)
        val tail = (lastDisplay + PREPARSE_AHEAD).coerceAtMost(items.size - 1)
        for (di in head..tail) {
            if (di !in items.indices) continue
            val (rawIdx, msg) = items[di]
            if (!msg.isAssistant) continue
            val turnMsgs = groups[rawIdx] ?: continue
            // 跳过流式 turn——流式中途的部分文本快照若被预解析，registry
            // 永不重析 → 分片渲染部分 AST = 回复尾部永久截断
            //（末段带统计栏显得完整，极具迷惑性）
            val streamingNow = world.streamingMsgId
            if (streamingNow != null && turnMsgs.any { it.message.id == streamingNow }) continue
            for (cm in turnMsgs) {
                for (part in cm.parts) {
                    if (part is Part.Text &&
                        part.text.length >= PREPARSE_MIN_CHARS &&
                        part.synthetic != true && part.ignored != true &&
                        // 与 PartContent 的 CollapsibleQuestionPart 分支保持互斥
                        !part.text.contains("User has answered")
                    ) {
                        // key = part.id（服务器全局唯一；多消息 turn 下代表消息
                        // id 与 part 归属消息可能不一致，不能混入 msgId）
                        val key = part.id
                        window.add(key)
                        if (registry.current(key) is RenderReadiness.Pending) {
                            val textForParse = part.text
                            // 2026-08-22：传原文——归一化已移入 preParse 后台链
                            //（原在此主线程同步执行，帧间隙 20-30ms 巨帧根因）
                            registry.preParse(
                                key,
                                textForParse,
                                parseScope,
                            ) { st ->
                                // 巨型 part 解析完成即计算块级分片计划（主线程
                                // 回调）——后续该 turn 进入视口时按计划发射
                                // N 个 chunk item（见 buildChatEntries）。
                                if (textForParse.length >= CHUNK_MIN_CHARS) {
                                    computeChunkPlan(key, st, CHUNK_MIN_CHARS, CHUNK_TARGET_CHARS)
                                        ?.let { plan ->
                                            if (BuildConfig.DEBUG) {
                                                AppLogger.d(
                                                    "ScrollDiag",
                                                    "CHUNK plan part=" + key.take(14) +
                                                        " blocks=" + st.node.children.size +
                                                        " chunks=" + plan.ranges.size
                                                )
                                            }
                                            // 入 pending 队列，视口外才提交
                                            pendingChunkPlans[key] = plan to di
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 有界性（#98 同款防无界增长）：裁剪窗口外条目，LRU 上限。
        // 注：chunkPlans 不随 LRU 淘汰——分片中的 turn 需要稳定计划（视口内
        // 淘汰会导致 chunk key 消失→回退单 item→巨帧回归）；巨型消息每会话
        // 个位数，AST 常驻内存可忽略（130K 字符 ≈ 400KB/条）。
        preparseSeenKeys.removeAll { it !in window }
        preparseSeenKeys.addAll(window)
        while (preparseSeenKeys.size > PREPARSE_LRU) {
            val oldest = preparseSeenKeys.firstOrNull() ?: break
            preparseSeenKeys.remove(oldest)
            registry.remove(oldest)
        }
        // recentStreamedTurnKeys 有界清理：turn 离开窗口（display 粒度）即允许分片
        if (_recentStreamedTurnKeys.value.isNotEmpty()) {
            val windowKeys = buildSet {
                for (di in head..tail) {
                    if (di !in items.indices) continue
                    val (ri, m) = items[di]
                    add(if (m.isUser) "u_" + m.message.id
                        else "t_" + (groups[ri]?.firstOrNull()?.message?.id ?: m.message.id))
                }
            }
            _recentStreamedTurnKeys.value =
                _recentStreamedTurnKeys.value.filterTo(mutableSetOf()) { it in windowKeys }
        }
        // pending 分片计划提交——仅当所属 turn 离开预解析窗口
        //（head..tail，±PREPARSE_AHEAD display 粒度）才写入 chunkPlans。
        // 视口/窗口内保持单 item（key 稳定不裂变）；turn 离开窗口后
        // 裂变点远离视口（±14 item 缓冲），预取到它时已是分片版。
        //
        // 2026-08-22 实验回滚记录：曾试把 F2 从窗口收紧为真实视口（视口外
        // 1-14 item 即提交，欲治冷态首滑单体组合 90ms 巨帧）——真机实证更差：
        // 14ms 帧桶暴涨（126 帧/轮 = 组合缓存被近距裂变持续扰动）+ 原本全清
        // 的滚离滚回协议出现 27-69ms。窗口保守性是承重的（组合缓存稳定性），
        // 回滚。冷态首滑单体巨帧属已知残余（每条巨型消息首次滑入一帧，
        // ~90ms），与 #168 残余尖刺同类登记观察。
        //
        // 竞态根治（2026-08-20 五轮叠放 bug 三处修复——保留全部语义）：
        // F1 锚点重解析：提交时用 partId 反查当前 turn 的 display index
        //   （入队 di 会因 loadAround 重建失效——陈旧即重算，永不失配）。
        // F2 窗口内防线：重解析后 index 仍在预解析窗口内 → 本轮跳过不提交
        //   （等真正滚出窗口）——即使 F1 有遗漏也不会视口内裂变。
        // F3 门控：『跳转进行中或稳定窗口内不提交』（终点+2s 内）。
        // 相位直读 StateFlow.value（同步快照）——桥接有 1-2 组合帧滞后，
        // 新跳转启动瞬间门是开的（跳转进行中提交）。
        val phaseNow = jumpPhase.value
        // 门控：非终态全挡（Preparing/Measuring/Settling）；终态在打点后
        // 2s 内=稳定窗口也挡。
        val jumpActiveOrSettling = phaseNow !is JumpPhase.Idle &&
            phaseNow !is JumpPhase.Displayed && phaseNow !is JumpPhase.Failed ||
            (lastJumpEndAtMillis > 0L && clock() - lastJumpEndAtMillis < 2000)
        if (pendingChunkPlans.isNotEmpty() && !jumpActiveOrSettling) {
            // F1：partId → 所属消息 → turn 首 key → 当前 display index
            fun resolveTurnDisplayIndex(partId: String): Int {
                for (di2 in items.indices) {
                    val (ri2, m2) = items[di2]
                    val msgs2 = groups[ri2] ?: listOf(m2)
                    if (msgs2.any { cm -> cm.parts.any { it.id == partId } }) return di2
                }
                return -1
            }
            val committed = HashMap<String, MdChunkPlan>()
            val staleDropped = mutableListOf<String>()
            for ((partId, planAndLegacyDi) in pendingChunkPlans) {
                val freshDi = resolveTurnDisplayIndex(partId)
                if (freshDi < 0) {
                    // 所属 turn 已不在当前列表（被过滤/会话切换）——真正丢弃
                    //（从 pending 移除且不进 chunkPlans）
                    staleDropped += partId
                    continue
                }
                if (freshDi in head..tail) continue // F2：窗口内防线
                committed[partId] = planAndLegacyDi.first
            }
            if (staleDropped.isNotEmpty()) {
                staleDropped.forEach { pendingChunkPlans.remove(it) }
                dev.leonardo.ocbeacon.debug.RaceProbe.probe {
                    "CHUNK drop-stale n=" + staleDropped.size
                }
            }
            if (committed.isNotEmpty()) {
                dev.leonardo.ocbeacon.debug.RaceProbe.probe {
                    "CHUNK commit n=" + committed.size + " legacyDi=" +
                        pendingChunkPlans.entries.joinToString(",", "[", "]") { (k, v) -> k.take(12) + "@" + v.second } +
                        " window=" + head + ".." + tail
                }
                _chunkPlans.value = _chunkPlans.value + committed
                committed.keys.forEach { pendingChunkPlans.remove(it) }
                if (BuildConfig.DEBUG) {
                    AppLogger.d("ScrollDiag", "CHUNK commit(anchored) n=" + committed.size + " remain=" + pendingChunkPlans.size)
                }
            }
        }
    }

    companion object {
        /**
         * 视口前后各预解析的 item 数（覆盖 fling/预组合窗口）。
         * 2026-08-22：8→14——真机 framestats 实证冷态快滑竞态窗口：±8 item
         * ≈ 10 帧（SafeFling 限速下）vs 归一化+解析 30-80ms（Default 线程
         * 满载时）——miss 即落入库的 rememberMarkdownState 主线程同步解析
         * 兜底（84ms 巨帧实测）。14 加宽供给余量（LRU 32 仍覆盖 2× 窗口）。
         */
        const val PREPARSE_AHEAD = 14

        /** 只预解析超过该字符数的文本 part（短文本同步解析成本可忽略）。 */
        const val PREPARSE_MIN_CHARS = 200

        /** 预解析条目 LRU 上限（Parsed state 持有 AST，防无界增长）。 */
        const val PREPARSE_LRU = 32

        /** 低于该字符数的 part 不分片（单次组合 ~20 块内可容忍）。 */
        const val CHUNK_MIN_CHARS = 3000

        /** 目标每片字符数（130K 消息 ≈ 26 片，单片组合 ~2ms）。 */
        const val CHUNK_TARGET_CHARS = 2500
    }
}

/**
 * 视口变化时的不可变世界快照——Compose 桥装配（读 rememberUpdatedState 桥
 * 的最新值），协调器不做任何 Compose 读取。
 */
internal class RenderSupplyWorld(
    val displayItems: List<Pair<Int, ChatMessage>>,
    val turnGroups: Map<Int, List<ChatMessage>>,
    val entries: ChatEntries,
    val bannerCount: Int,
    val streamingMsgId: String?,
)
