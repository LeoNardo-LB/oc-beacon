package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #442 R2 分片唤醒（批次 A1）：流式 turn 毕业计划纯函数。
 *
 * 流式正文单容器内的每批 append 使组合/测量成本 O(总内容)（滑动 p90 19-27ms
 * 平台期主源，#437 二十五世轮定罪；settle 后整段追平批最重）。本计划把 gate
 * 已放行内容中的**已闭合稳定前缀**切为冻结块序列——毕业 = 迁出活跃 item 成
 * 独立冻结 chunk item（批次 A2 装配），活跃 item 只保留尾块 → 单帧成本
 * O(尾块)。冻结块渲染不可变：无失效 = 无重测（Compose 布局缓存命中）。
 *
 * 语义与不变量（[stableTailBoundary] 同源、多块化；测试
 * StreamingGraduationPlanTest 逐条钉死）：
 * - 毕业边界 = 空行块边界（≥2 连续 \n 的 run 之后）——块边界切割，两侧独立
 *   渲染布局不变（#258 完结分片同款语义、chunk item 零间距先例）；
 * - **冻结 append-only**：已产出的块区间序列永不改写（装配层 item 键稳定的
 *   前提——贪心打包只对新增区间进行，旧块不重切）；
 * - tailFrom 单调只前进；无块边界（通篇无空行）/增量不足门槛 → 不毕业
 *  （降级为现行单容器行为，correctness 不破）；
 * - 非前缀重建由调用方以 previous=null 重入；snapshot 缩短/released 回退时
 *   防御性返回空计划（调用方整树重建，与 pilot resetKey 路径对齐）。
 *
 * 坐标系：归一化快照坐标（#471③ 前移后 pilot 全链统一坐标）。
 */

/** 冻结块（文档序字符区间 [from,to)，归一化快照坐标）。 */
internal data class FrozenChunk(val from: Int, val to: Int)

/**
 * 流式分片计划：已毕业冻结块序列 + 尾块起点。不变量：区间自 0 起按序相邻
 * 无缝；末块 to == tailFrom（无块时 tailFrom==0）。
 */
internal data class StreamingGraduation(val chunks: List<FrozenChunk>, val tailFrom: Int)

/** 毕业积累门槛（新增冻结量不足则继续攒——限制毕业频率与重建成本）。 */
internal const val GRADUATE_MIN_CHARS = 2000

/** 单冻结块字符上限（贪心打包界；无内部边界的巨块允许超限独占）。 */
internal const val GRADUATE_MAX_CHUNK_CHARS = 4000

internal fun planStreamingGraduation(
    snapshot: String,
    released: Int,
    previous: StreamingGraduation? = null,
    minFreezeChars: Int = GRADUATE_MIN_CHARS,
    maxChunkChars: Int = GRADUATE_MAX_CHUNK_CHARS,
): StreamingGraduation {
    val prev = previous ?: StreamingGraduation(emptyList(), 0)
    // 非前缀防御：快照缩短/放行回退 = 重生成窗口——空计划，调用方整树重建
    if (snapshot.length < prev.tailFrom || released < prev.tailFrom) {
        return StreamingGraduation(emptyList(), 0)
    }
    val boundary = stableTailBoundary(snapshot, released)
    if (boundary < prev.tailFrom) return prev                  // 防御：绝不回退
    if (boundary - prev.tailFrom < minFreezeChars) return prev // 门槛未足，继续攒
    // 冻结 append-only：只对新增区间 [tailFrom, boundary) 打包
    return StreamingGraduation(
        prev.chunks + packBlocks(snapshot, prev.tailFrom, boundary, maxChunkChars),
        boundary,
    )
}

/**
 * [from,to) 内按空行块边界贪心打包为 ≤[maxChunkChars] 的块序列。切点只落
 * 空行 run 之后（块不跨块边界被切）；累积超限且已有切点时在最近切点落刀；
 * 无内部边界的巨块允许超限独占（切在块中间 = 两侧布局变，破坏零闪烁前提）。
 *
 * #516 围栏原子性（2026-10-05）：切点不落代码围栏内部——围栏内空行不设防
 * （围栏整体独占一块，走「无内部边界巨块」既有超限豁免）；否则冻结块/尾块
 * 各持半段围栏独立解析 = 一块代码碎成多段（真机定罪：163 行单围栏被内空行
 * 切成 3 段）。[from] 是既有边界（顶层，闭栏后空行），起始围栏态恒闭。
 */
private fun packBlocks(snapshot: String, from: Int, to: Int, maxChunkChars: Int): List<FrozenChunk> {
    if (from >= to) return emptyList()
    val out = mutableListOf<FrozenChunk>()
    var start = from
    var cut = from
    var i = from
    var fenceChar: Char? = null
    while (i < to) {
        if (i == from || snapshot[i - 1] == NL) {
            fenceMarkerAt(snapshot, i, to)?.let { marker ->
                fenceChar = if (fenceChar == marker) null
                else if (fenceChar == null) marker
                else fenceChar // 异型标记行是围栏内容，不翻转
            }
        }
        if (snapshot[i] == NL) {
            var j = i
            while (j < to && snapshot[j] == NL) j++
            if (j - i >= 2 && fenceChar == null) cut = j
            i = j
        } else {
            if (i - start >= maxChunkChars && cut > start) {
                out += FrozenChunk(start, cut)
                start = cut
            }
            i++
        }
    }
    if (to > start) out += FrozenChunk(start, to)
    return out
}

private const val NL = '\n'
