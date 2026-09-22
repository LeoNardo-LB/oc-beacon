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
    val out = mutableListOf<MutableList<PartGroup>>()
    var acc = 0
    for (g in groups) {
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
 * 切片内容指纹（#427 P2 高度账本键的原料）：part id 序列 + 文本当量长度。
 * - 同内容稳定（跨组合/跨进程持久化可命中）；
 * - part 增删、顺序变化、文本长度变化均互斥（内容变更不得命中旧高度）；
 * - 不含完整文本哈希：账本只需区分「内容变了没有」，长文本哈希成本高且
 *   长度变化已覆盖工具输出补全场景。
 */
internal fun sliceFingerprint(slice: List<PartGroup>): String {
    val sb = StringBuilder(slice.size * 24)
    for (g in slice) {
        when (g) {
            is PartGroup.Context -> {
                sb.append('c').append(g.parts.size).append(';')
                g.parts.forEach { sb.append(it.id).append(',').append(it.tool).append(';') }
            }
            is PartGroup.Single -> {
                val p = g.part
                sb.append('s').append(p.id).append(',')
                when (p) {
                    is Part.Text -> sb.append('t').append(p.text.length)
                    is Part.Reasoning -> sb.append('r').append(p.text.length)
                    is Part.Shell -> sb.append('h').append((p.output ?: "").length)
                    else -> sb.append('o')
                }
                sb.append(';')
            }
        }
    }
    return sb.toString()
}

/** 指纹等价便捷谓词（测试/账本用）。 */
internal fun List<PartGroup>.sameFingerprintAs(other: List<PartGroup>): Boolean =
    sliceFingerprint(this) == sliceFingerprint(other)
