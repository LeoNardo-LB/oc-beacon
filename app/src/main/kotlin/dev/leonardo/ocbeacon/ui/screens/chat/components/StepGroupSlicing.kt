package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem

/**
 * #427 P1：步组内容切片器（纯函数，JVM 可单测）。
 *
 * 基线语义复用 #422 已退役条目裂变路径的 [sliceStepGroupBodies]（宿主从
 * 列表条目移入卡片内部——spec §Implementation Decisions）。切口只落在
 * [PartGroup] 边界（Context 组不可分）；权重低于 [STEP_GROUP_SLICE_THRESHOLD_WEIGHT]
 * 的组不进入切片/窗口路径（spec 用户故事 8：小组零回归）。
 *
 * 配套 #427 P2 账本键原料 [sliceFingerprint]：同内容稳定、内容/结构变化互斥。
 */

/** 大组展开体单片权重预算（≈1 屏；#422 标定沿用）。 */
const val STEP_GROUP_BODY_TARGET_WEIGHT = 2200

/**
 * #427 切片阈值（≈2 屏权重）：达此值的步组进入切片+窗口路径。
 * 取 2 屏而非 1 屏：不足 2 屏的内容在「视口±1 屏」窗口内恒全量组合，
 * 切片只有账本开销没有组合收益；真正的多屏内容才值得换宿主。
 */
const val STEP_GROUP_SLICE_THRESHOLD_WEIGHT = STEP_GROUP_BODY_TARGET_WEIGHT * 2

/** 步组总权重（字符当量，见 turnItemWeight 标定）。 */
internal fun stepGroupWeight(groups: List<PartGroup>): Int =
    groups.sumOf { turnItemWeight(RenderItem.GroupedParts(it)) }

/** 是否进入 #427 切片路径（阈值边界含入：达阈值即切）。 */
internal fun stepGroupNeedsSlicing(groups: List<PartGroup>): Boolean =
    stepGroupWeight(groups) >= STEP_GROUP_SLICE_THRESHOLD_WEIGHT

/**
 * 大组展开体切片：按权重贪心聚合 groups 为若干片（每片≈[STEP_GROUP_BODY_TARGET_WEIGHT]）。
 * 纯函数、幂等（重切扁平化输出结果不变）、守恒（flatten(output)==input）、无空片。
 */
internal fun sliceStepGroupBodies(groups: List<PartGroup>): List<List<PartGroup>> {
    // #431:先做巨型 text part 的 markdown 块级分段——PartGroup 边界切不动
    // 万字符级单 part(独自成片≈5 屏,滚动进窗单帧全量组合=0.4-0.85s 冻结);
    // 分段后每段≈1 屏,窗口化按屏付费。
    val expanded = groups.flatMap { g ->
        val p = (g as? PartGroup.Single)?.part
        if (p is Part.Text && p.text.length > STEP_GROUP_BODY_TARGET_WEIGHT) {
            splitHeavyTextPart(p).map { PartGroup.Single(it) }
        } else {
            listOf(g)
        }
    }
    val out = mutableListOf<MutableList<PartGroup>>()
    var acc = 0
    for (g in expanded) {
        if (out.isEmpty() || acc >= STEP_GROUP_BODY_TARGET_WEIGHT) {
            out += mutableListOf<PartGroup>()
            acc = 0
        }
        out.last() += g
        acc += turnItemWeight(RenderItem.GroupedParts(g))
    }
    return out
}

/**
 * #431:巨型 Text part 的 markdown 块边界分段(纯函数,JVM 可单测)。
 * 块扫描规则([markdownBlocks]):空行分隔;表格(连续 | 行)与围栏代码整体
 * 不可分;段间以空行回接(块语义守恒)。贪心装段至 [budgetChars];原子块
 * 超预算(如 60 行表)独段保留——表内成本由 v4 行组分帧+列宽 LRU 承接。
 * 段 part 以 synthetic=true + id 后缀派生(账本指纹一次性转冷,可接受)。
 */
internal fun splitHeavyTextPart(
    part: Part.Text,
    budgetChars: Int = STEP_GROUP_BODY_TARGET_WEIGHT,
): List<Part.Text> {
    if (part.text.length <= budgetChars) return listOf(part)
    val blocks = markdownBlocks(part.text)
    val segs = mutableListOf<String>()
    val cur = StringBuilder()
    for (b in blocks) {
        if (cur.isNotEmpty() && cur.length + 2 + b.length > budgetChars) {
            segs += cur.toString()
            cur.setLength(0)
        }
        if (cur.isNotEmpty()) cur.append("\n\n")
        cur.append(b)
        if (cur.length >= budgetChars) { // 单块即超预算:独段(原子性优先)
            segs += cur.toString()
            cur.setLength(0)
        }
    }
    if (cur.isNotEmpty()) segs += cur.toString()
    if (segs.size <= 1) return listOf(part)
    return segs.mapIndexed { i, s ->
        part.copy(id = part.id + "#sg" + i, text = s, synthetic = true)
    }
}

/**
 * markdown 块扫描(纯函数):返回块列表(块内保留原始单换行)。
 * - 围栏代码(trim 后 `'```'` 开头):整体一块(内部空行不切);
 * - 表格(连续 | 开头行,含分隔行):整体一块;
 * - 其余:连续非空行且不以 |/``` 开头 = 一块(段落内换行守恒)。
 */
internal fun markdownBlocks(src: String): List<String> {
    val lines = src.split('\n')
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val start = i
        val fence = line.trimStart().startsWith("```")
        val table = !fence && line.trimStart().startsWith("|")
        i++
        when {
            fence -> while (i < lines.size && !lines[i].trimStart().startsWith("```")) i++
            table -> while (i < lines.size && lines[i].trimStart().startsWith("|")) i++
            else -> while (i < lines.size && !lines[i].isBlank() &&
                !lines[i].trimStart().startsWith("|") &&
                !lines[i].trimStart().startsWith("```")) i++
        }
        if (fence && i < lines.size) i++ // 含闭合围栏
        out += lines.subList(start, i).joinToString("\n")
    }
    return out
}

/**
 * 切片内容指纹（#427 P2 高度账本键的原料）：part id 序列 + 内容当量体量
 *（文本/推理/Shell 输出/工具 state 的输出与附件长度）。
 * - 同内容稳定（跨组合/跨进程持久化可命中）；
 * - part 增删、顺序变化、内容体量变化均互斥（内容变更不得命中旧高度——
 *   双轴审查 #427 指出：工具输出主体在 state，长度不进指纹=同 id 增长漏判）；
 * - 不含完整文本哈希：账本只需区分「内容变了没有」，长度当量已覆盖补全/改写
 *   主场景，等长异内容改写由重测差异封顶（≤单片高）兜底。
 */
internal fun sliceFingerprint(slice: List<PartGroup>): String {
    val sb = StringBuilder(slice.size * 24)
    for (g in slice) {
        when (g) {
            is PartGroup.Context -> {
                sb.append('c').append(g.parts.size).append(';')
                g.parts.forEach { sb.append(it.id).append(',').append(it.tool).append(',').append(toolStateBulk(it.state)).append(';') }
            }
            is PartGroup.Single -> {
                val p = g.part
                sb.append('s').append(p.id).append(',')
                when (p) {
                    is Part.Text -> sb.append('t').append(p.text.length)
                    is Part.Reasoning -> sb.append('r').append(p.text.length)
                    is Part.Shell -> sb.append('h').append((p.output ?: "").length)
                    is Part.Tool -> sb.append('w').append(toolStateBulk(p.state))
                    else -> sb.append('o')
                }
                sb.append(';')
            }
        }
    }
    return sb.toString()
}

/** 工具 state 的内容当量体量（输出/原始入参/错误/附件长度——状态机阶段+体量）。 */
private fun toolStateBulk(state: dev.leonardo.ocbeacon.domain.model.ToolState): String =
    when (state) {
        is dev.leonardo.ocbeacon.domain.model.ToolState.Pending ->
            "p" + (state.raw ?: "").length
        is dev.leonardo.ocbeacon.domain.model.ToolState.Running ->
            "g" + state.output.length + "," + (state.title ?: "").length
        is dev.leonardo.ocbeacon.domain.model.ToolState.Completed ->
            "c" + state.output.length + "," + (state.title ?: "").length + "," +
                (state.attachments?.sumOf { (it.data ?: "").length } ?: 0)
        is dev.leonardo.ocbeacon.domain.model.ToolState.Error ->
            "e" + state.error.length
    }
