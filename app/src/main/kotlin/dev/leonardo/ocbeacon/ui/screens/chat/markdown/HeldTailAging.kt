package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 扣留尾部超龄状态机（spec §2 活性保障，纯逻辑 JVM 可测）。
 *
 * 扣留内容（SafePrefixGate 未放行尾部）在流中的可见性节奏：
 * - 扣留不足 [REVEAL_AFTER_MS]（约 6 个 48ms 批）→ 不显示——大概率下一两批
 *   就毕业（空行/换行边界推进），避免降亮区闪烁进出；
 * - 超龄 → 降亮区可见（锁高裁剪 + alpha 0.5 + 呼吸光标）——流中活性指示；
 * - 锁高刷新节流 [HEIGHT_REFRESH_MS]：显示高度最多每 500ms 变化一次，
 *   期间文本增长被裁剪（不触布局）——降亮区高度流=低频量子，交给 #435
 *   引擎配对无压力。
 * - 扣留清空（全部毕业/完结 flush/非前缀重建）→ 复位，下轮重新计时。
 */
internal class HeldTailAgingState(
    private val now: () -> Long,
    private val revealAfterMs: Long = REVEAL_AFTER_MS,
    private val heightRefreshMs: Long = HEIGHT_REFRESH_MS,
) {
    /** 超龄后为 true——降亮区可见。 */
    var visible: Boolean = false
        private set

    /** 锁定显示高度（px）；-1 = 未锁（不可见期间不锁高）。 */
    var lockedHeightPx: Int = -1
        private set

    /** 最近一次观测到的自然渲染高度（px）——供轮询期预估。 */
    var lastNaturalHeightPx: Int = 0
        private set

    private var heldSinceMs = -1L
    private var lastRefreshMs = -1L

    /** 当前扣留已持续时长（ms）；未扣留为 0——超龄观测日志用。 */
    val heldForMs: Long
        get() = if (heldSinceMs < 0) 0L else now() - heldSinceMs

    /**
     * 每批扣留更新（含自然高度观测）。
     * [heldText] 为空 = 全部毕业/完结/重建 → 复位。
     */
    fun update(heldText: String, naturalHeightPx: Int) {
        lastNaturalHeightPx = naturalHeightPx
        if (heldText.isEmpty()) {
            visible = false
            lockedHeightPx = -1
            heldSinceMs = -1
            lastRefreshMs = -1
            return
        }
        val t = now()
        if (heldSinceMs < 0) heldSinceMs = t
        if (t - heldSinceMs >= revealAfterMs) visible = true
        // naturalHeightPx<=0（可见前无布局观测）不锁高——防止可见瞬间锁 0
        if (visible && naturalHeightPx > 0 &&
            (lastRefreshMs < 0 || t - lastRefreshMs >= heightRefreshMs)
        ) {
            lockedHeightPx = naturalHeightPx
            lastRefreshMs = t
        }
    }

    companion object {
        /** 尾部超龄阈值（约 6 批）——此前大概率已毕业，不闪降亮区。 */
        const val REVEAL_AFTER_MS = 300L

        /** 锁高刷新节流（降亮区高度量子间隔）。 */
        const val HEIGHT_REFRESH_MS = 500L
    }
}
