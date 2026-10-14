package dev.leonardo.ocbeacon.ui.screens.chat.components

/**
 * #533：展开卡终高进程级存储（纯数据结构，JVM 可单测）。
 *
 * 根因（5h soak 05:32:55 三帧定罪）：SliceHost 槽位出窗即弃（subcompose 无
 * SaveableStateHolder），槽内 `rememberSaveable(finalHCache)` 随槽位销毁 →
 * 展开卡重入窗新鲜组合时冷抬底被缴械 → 盒瞬态欠测（~0/部分高）→ 与片高账本
 * 稳态（含展开高）的落差 = item −H 手势中塌缩脉冲（86ms-83s 回长族，5h 13 次）。
 * LazyList item 级回收不受此害（SaveableStateHolder 在役，01e4ba2b 域）。
 *
 * 本店以 (cacheKey, widthKey) 键控进程存活——槽位销毁/组合回收/子树重建全存活，
 * 与 [StepGroupHeightLedger] 的宽度键控同语义（宽度变化整体失效，防旋转/折叠
 * 形态下的陈旧高占位）。LRU 上限防膨胀（条目为 Int，512 条内存可忽略）。
 */
internal object CardExpandFinalHStore {
    private const val MAX_KEYS = 512
    private data class Key(val cacheKey: String, val widthKey: Int)

    private val cache = object : LinkedHashMap<Key, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Int>): Boolean =
            size > MAX_KEYS
    }

    @Synchronized
    fun get(cacheKey: String, widthKey: Int): Int? = cache[Key(cacheKey, widthKey)]

    /**
     * #551 高度参照系裁决（2026-10-13 双轴复审收口）：事件时刻直写（与 saveable
     * finalHCache 刀②同语义）——调用点均为 settle/落地实测；max 棘轮会把暂态
     * 膨胀高跨事件钉住（铁律禁历史高水位）。冷抬底消费方 maxOf(storeFloor,
     * saveableFloor) 取双源较新估计，floor 降低=少抬不破 #533（1.5s 桥接不变）。
     */
    @Synchronized
    fun record(cacheKey: String, widthKey: Int, heightPx: Int) {
        if (heightPx <= 0) return
        cache[Key(cacheKey, widthKey)] = heightPx
    }

    /** 单测隔离。 */
    fun clearForTest() = synchronized(cache) { cache.clear() }
}

/**
 * #533：冷抬底欠测桥接状态机（纯逻辑，JVM 可单测）。
 *
 * 在 [coldFloorEligible] 经典冷窗（count≤2）之外，对「重入窗/回收重组合的
 * 展开卡」把 floor 持有期从 `measured==0` 扩到 **measured<floor 的整个异步
 * 落地期**——部分高欠测（loading 占位/分段表格逐组落地）同样不得把盒压低于
 * 已知终高（那是脉冲的另一半族：−6742/−319/−2189 级联）。
 *
 * 释放（floor 让位于真测）唯一条件：**欠测值连续 [RELEASE_AFTER_NS] 无变化**
 * ——迟到落地（分批表格逐组/asyncParse）每步变化都会重置计时；1.5s 静止仍
 * 低于 floor = 合法收缩（宽度已由键控排除，余为内容真变矮），差异走引擎
 * steady 配对（片高账本 #427 契约：差异封顶 ≤该片高）。
 */
internal class CardExpandFloorBridge(
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var lastBelow = Int.MIN_VALUE
    private var lastChangeNs = 0L

    /**
     * 本测遍 floor 是否持有。经典冷窗直通（无状态）；欠测域要求 fraction=1
     * 稳态 ∧ 非 animating（episode 独占语义不变，抬底不进锚点基维持）。
     */
    fun holds(fraction: Float, animating: Boolean, measureCount: Int, measured: Int, floor: Int): Boolean {
        if (coldFloorEligible(fraction, animating, measureCount, measured)) return true
        if (fraction < 1f || animating || floor <= 0 || measured >= floor) {
            lastBelow = Int.MIN_VALUE // 离开欠测域即清态（下次进入重新计时）
            return false
        }
        if (measured != lastBelow) {
            lastBelow = measured
            lastChangeNs = nowNs()
        }
        return nowNs() - lastChangeNs < RELEASE_AFTER_NS
    }

    companion object {
        internal val RELEASE_AFTER_NS = 1_500_000_000L
    }
}
