package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 非前缀事件风暴探测与重建限频（spec §4，JVM 可测）。
 *
 * 数据层快照摆动（reconciler vs live 竞态）会以「非前缀」形态到达 pilot——
 * 每次非前缀触发 resetKey 整体重建 = 全量重解析+布局翻转（重建风暴）。
 * 本探测在 [windowMs] 窗口内非前缀事件超过 [maxEvents] 时进入抑制：
 * 冻结放行与重建（pilot prev 保持旧值——旧串回来即无缝恢复），
 * 直到窗口内事件回落——根因（数据层双写）实证另立卡，本类只观测+止血。
 */
internal class FlapDetector(
    private val now: () -> Long,
    private val windowMs: Long = 1000L,
    private val maxEvents: Int = 2,
) {
    private val events = ArrayDeque<Long>()
    private var suppressedUntilMs = -1L

    /** 累计进入抑制的次数（风暴计数，观测日志用）。 */
    var stormCount = 0
        private set

    /**
     * 记录一次非前缀事件；返回 true = 应抑制（冻结放行与重建）。
     * 抑制期内事件继续入窗——窗口滑动维持判断，风暴停止后自动解除。
     */
    fun onNonPrefix(): Boolean {
        val t = now()
        while (events.isNotEmpty() && t - events.first() > windowMs) events.removeFirst()
        events.addLast(t)
        if (events.size > maxEvents) {
            suppressedUntilMs = t + windowMs
            stormCount++
            return true
        }
        return t < suppressedUntilMs
    }
}
