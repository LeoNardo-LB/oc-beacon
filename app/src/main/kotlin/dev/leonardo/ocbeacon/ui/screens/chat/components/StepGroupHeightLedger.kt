package dev.leonardo.ocbeacon.ui.screens.chat.components

/**
 * #427 P2：步组片高账本（纯数据结构，JVM 可单测）。
 *
 * 职责：按「内容指纹 + 可用宽度」缓存每片实测高度；总高 = Σ片高。
 * - 宽度键控：宽度变化（旋转/折叠形态）整体失效（spec 用户故事 9）；
 * - 跨回收持久化：[encode]/[fromEncoded] 字符串即 rememberSaveable 载荷
 *   （与引擎 finalH 缓存同机制——组合回收后二次展开零等待）；
 * - 冷降级：[isWarm] 为假时宿主降级现行整体 ε 沉降（正确性不依赖预热）；
 * - 差异封顶：重测只替换对应片高度，Σ 漂移 ≤ 该片高度（迟到增量经引擎
 *   既有配对通道消化）。
 *
 * 非目标：不缓存宽度未定/内容未稳的中间态——写入方（P3 宿主）只在片
 * 实测完成后 record。
 */
internal class StepGroupHeightLedger private constructor(
    private var widthKey: Int,
    private val heights: LinkedHashMap<String, Int>,
) {

    constructor() : this(WIDTH_UNSET, LinkedHashMap())

    val entryCount: Int get() = heights.size

    /** 片高查询；冷（未量/宽度失效）返回 null。 */
    fun heightOf(fingerprint: String): Int? = heights[fingerprint]

    /**
     * 是否全暖：宽度已定且 [fingerprints] 全部有实测记录。
     * 冷降级判定的唯一依据（spec §Testing Decisions）。
     */
    fun isWarm(fingerprints: List<String>): Boolean =
        widthKey != WIDTH_UNSET && fingerprints.isNotEmpty() &&
            fingerprints.all { heights.containsKey(it) }

    /** 已知片高之和（缺席记 0）——全暖时即总高；部分暖仅可作 floor/debug。 */
    fun totalHeight(fingerprints: List<String>): Int =
        fingerprints.sumOf { heights[it] ?: 0 }

    /**
     * 记录片实测高度。宽度键变化 → 先全量失效（旧宽度高度不可跨宽度复用）；
     * 非正高度拒绝写入（与 fromEncoded 对称——0 高片无占位意义，写入会让
     * 持久化恢复整本丢弃，双轴审查 #427 定为校验不对称）。
     */
    fun record(widthKey: Int, fingerprint: String, heightPx: Int) {
        if (heightPx <= 0) return
        if (this.widthKey != widthKey) {
            heights.clear()
            this.widthKey = widthKey
        }
        heights[fingerprint] = heightPx
    }

    /** 内容变化后修剪孤儿键（指纹集之外的旧记录），防账本无限增长。 */
    fun prune(validFingerprints: List<String>) {
        val valid = validFingerprints.toHashSet()
        heights.keys.retainAll(valid)
    }

    /**
     * 持久化编码："v1\u001F<width>\u001F<fp>\u001F<h>\u001F<fp>..."。
     * 分隔符取单元分隔符（part id 不含控制字符），圆整往返无损。
     */
    fun encode(): String = buildString {
        append("v1").append(US).append(widthKey)
        for ((fp, h) in heights) {
            append(US).append(fp).append(US).append(h)
        }
    }

    companion object {
        internal const val WIDTH_UNSET = -1
        private const val US = '\u001F'

        /** 解码持久化载荷；null/畸形输入一律回冷账本（不抛、不部分恢复）。 */
        fun fromEncoded(encoded: String?): StepGroupHeightLedger {
            if (encoded == null) return StepGroupHeightLedger()
            val parts = encoded.split(US)
            if (parts.size < 2 || parts[0] != "v1") return StepGroupHeightLedger()
            val width = parts[1].toIntOrNull() ?: return StepGroupHeightLedger()
            if (width <= 0) return StepGroupHeightLedger()
            val map = LinkedHashMap<String, Int>()
            var i = 2
            while (i + 1 < parts.size) {
                val h = parts[i + 1].toIntOrNull() ?: return StepGroupHeightLedger()
                if (h <= 0) return StepGroupHeightLedger()
                map[parts[i]] = h
                i += 2
            }
            if (i != parts.size) return StepGroupHeightLedger() // 奇数尾巴=畸形
            return StepGroupHeightLedger(width, map)
        }
    }
}
