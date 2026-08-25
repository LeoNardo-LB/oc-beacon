package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动分页触发策略表驱动单测（C10-B）。
 *
 * 覆盖矩阵：
 * - reverseLayout 索引方向（2026-08-12：视觉顶 = index 最大——原 firstOrNull
 *   方向错误致「滚动不上去」回归哨兵）
 * - nearTop ≤8 边界（含恰 8/9 分界、进入会话不触发）
 * - 内容不足一屏触发（初始 13 条无法滚动场景）
 * - 门控矩阵（无更多/加载中/暂停/跳转锁）
 * - 退避等待值透传（500ms 指数退避）
 * - fire-time 跳转互斥复查（2026-08-21 ×2 竞态修复）
 * - 08-10 语义：触发不依赖滚动状态（静止布局照常触发）
 */
class AutoLoadPolicyTest {

    // ===== 构造助手 =====

    private val VIEWPORT_END = 1800

    /** 可见项构造：topMostEnd < viewportEnd 即「最顶可见项未填满视口」。 */
    private fun layoutOf(
        total: Int,
        visibleIndices: IntRange,
        topMostEnd: Int = VIEWPORT_END,
    ): AutoLoadLayoutSnapshot {
        val top = visibleIndices.maxOrNull() ?: -1
        val items = visibleIndices.map { idx ->
            val end = if (idx == top) topMostEnd else VIEWPORT_END
            VisibleItemSnapshot(index = idx, offset = 0, size = end)
        }
        return AutoLoadLayoutSnapshot(
            totalItemsCount = total,
            visibleItems = items,
            viewportEndOffset = VIEWPORT_END,
        )
    }

    private val emptyLayout = AutoLoadLayoutSnapshot(
        totalItemsCount = 30,
        visibleItems = emptyList(),
        viewportEndOffset = VIEWPORT_END,
    )

    private val openPaging = AutoLoadPagingState(hasMore = true, isLoading = false, paused = false)

    // ============ older：nearTop ≤8 reverseLayout 索引矩阵 ============

    @Test
    fun olderNearTop_索引矩阵表驱动() {
        data class Case(
            val name: String,
            val total: Int,
            val visible: IntRange,
            val topMostEnd: Int,
            val expected: Boolean,
        )
        val cases = listOf(
            // 08-12 回归哨兵：滑到顶（max index=29，差 1 项）——原实现取
            // firstOrNull（min index=21，差 9）→ 永不满足 →「滚动不上去了」
            Case("滑到顶差1项_0812方向修复", 30, 21..29, VIEWPORT_END, true),
            Case("距顶恰8项_边界含", 30, 20..22, VIEWPORT_END, true),
            Case("距顶9项_内容填满_不触发", 30, 19..21, VIEWPORT_END, false),
            // 进入会话：视觉底部（index 小），距顶远——不无限翻页拉网络
            Case("进入会话_视觉底部不触发", 30, 0..8, VIEWPORT_END, false),
            Case("进入会话_仅index0可见", 30, 0..0, VIEWPORT_END, false),
            // 内容不足一屏（最顶可见项未填满视口）→ 触发（初始窗口可能仅 13 条，
            // 用户无法滚动，nearTop 永不可达——「向上滑动加载历史消息也没有」）
            Case("内容不足一屏_触发", 30, 0..5, topMostEnd = 900, expected = true),
            Case("不足一屏但距顶近_双因均触发", 30, 24..28, topMostEnd = 900, expected = true),
        )
        cases.forEach { c ->
            assertEquals(
                c.name,
                c.expected,
                AutoLoadPolicy.olderThresholdMet(layoutOf(c.total, c.visible, c.topMostEnd)),
            )
        }
    }

    @Test
    fun olderNearTop_空可见项触发() {
        // lastVisible==null → 内容视为未填满（原实现语义）
        assertTrue(AutoLoadPolicy.olderThresholdMet(emptyLayout))
    }

    @Test
    fun older_静止布局仍触发_不依赖滚动状态() {
        // 08-10 语义：用户滑到顶"停住"（isScrollInProgress=false）也触发——
        // policy 输入只有布局/分页/跳转，无滚动状态参数；静止的近顶布局 → Trigger
        val still = layoutOf(30, 21..29)
        assertEquals(
            AutoLoadDecision.Trigger(backoffMillis = 0L),
            AutoLoadPolicy.olderDecision(still, openPaging, jumpLocked = false),
        )
    }

    // ============ newer：nearBottom ≤8 ============

    @Test
    fun newerNearBottom_索引矩阵表驱动() {
        data class Case(val name: String, val minIndex: Int, val expected: Boolean)
        val cases = listOf(
            Case("贴底index0", 0, true),
            Case("距底恰8项_边界含", 8, true),
            Case("距底9项_不触发", 9, false),
            Case("视觉中部", 15, false),
        )
        cases.forEach { c ->
            val layout = layoutOf(30, c.minIndex..(c.minIndex + 6))
            assertEquals(c.name, c.expected, AutoLoadPolicy.newerThresholdMet(layout))
        }
    }

    @Test
    fun newer_空可见项_minIndex回退0触发() {
        assertTrue(AutoLoadPolicy.newerThresholdMet(emptyLayout))
    }

    // ============ 门控矩阵（older/newer 同构） ============

    @Test
    fun olderDecision_门控矩阵() {
        val nearTop = layoutOf(30, 21..29)
        data class Case(val name: String, val paging: AutoLoadPagingState, val jump: Boolean)
        val gated = listOf(
            Case("无更多", openPaging.copy(hasMore = false), false),
            Case("加载中", openPaging.copy(isLoading = true), false),
            Case("暂停防风暴", openPaging.copy(paused = true), false),
            Case("跳转锁", openPaging, true),
        )
        gated.forEach { c ->
            assertEquals(
                c.name,
                AutoLoadDecision.Gated,
                AutoLoadPolicy.olderDecision(nearTop, c.paging, c.jump),
            )
        }
        // 门控通过 + 近顶 → Trigger（退避值透传）
        assertEquals(
            AutoLoadDecision.Trigger(500L),
            AutoLoadPolicy.olderDecision(nearTop, openPaging, jumpLocked = false, backoffWaitMillis = 500L),
        )
        // 门控通过 + 远离顶 → Wait
        val farFromTop = layoutOf(30, 0..8)
        assertEquals(
            AutoLoadDecision.Wait,
            AutoLoadPolicy.olderDecision(farFromTop, openPaging, jumpLocked = false),
        )
    }

    @Test
    fun newerDecision_门控矩阵() {
        val nearBottom = layoutOf(30, 0..6)
        assertEquals(
            AutoLoadDecision.Gated,
            AutoLoadPolicy.newerDecision(nearBottom, openPaging.copy(hasMore = false), false),
        )
        assertEquals(
            AutoLoadDecision.Gated,
            AutoLoadPolicy.newerDecision(nearBottom, openPaging.copy(isLoading = true), false),
        )
        assertEquals(
            AutoLoadDecision.Gated,
            AutoLoadPolicy.newerDecision(nearBottom, openPaging, jumpLocked = true),
        )
        assertEquals(
            AutoLoadDecision.Trigger(backoffMillis = 0L),
            AutoLoadPolicy.newerDecision(nearBottom, openPaging, jumpLocked = false),
        )
        assertEquals(
            AutoLoadDecision.Wait,
            AutoLoadPolicy.newerDecision(layoutOf(30, 15..21), openPaging, jumpLocked = false),
        )
    }

    // ============ 退避与跳转互斥 ============

    @Test
    fun trigger_退避等待值透传() {
        // delegate autoLoadWaitMillis（失败后 500ms 指数退避）→ Trigger 携带
        assertEquals(0L, AutoLoadPolicy.trigger(0L).backoffMillis)
        assertEquals(500L, AutoLoadPolicy.trigger(500L).backoffMillis)
        assertEquals(2000L, AutoLoadPolicy.trigger(2000L).backoffMillis)
    }

    @Test
    fun fireAllowed_fireTime跳转互斥复查() {
        // 08-21 ×2：启动闸门在重组延迟窗口下失效——fire-time 必须复查
        assertTrue(AutoLoadPolicy.fireAllowed(jumpInProgressAtFire = false))
        assertFalse(AutoLoadPolicy.fireAllowed(jumpInProgressAtFire = true))
    }

    @Test
    fun startGate_四条件合取() {
        assertTrue(AutoLoadPolicy.startGate(openPaging, jumpLocked = false))
        assertFalse(AutoLoadPolicy.startGate(openPaging.copy(hasMore = false), false))
        assertFalse(AutoLoadPolicy.startGate(openPaging.copy(isLoading = true), false))
        assertFalse(AutoLoadPolicy.startGate(openPaging.copy(paused = true), false))
        assertFalse(AutoLoadPolicy.startGate(openPaging, jumpLocked = true))
    }

    // ============ 低频诊断 ============

    @Test
    fun probeReason_近阈值才有值() {
        assertNull(AutoLoadPolicy.olderProbeReason(layoutOf(30, 0..8)))   // 远离顶
        assertNull(AutoLoadPolicy.olderProbeReason(
            AutoLoadLayoutSnapshot(0, emptyList(), VIEWPORT_END),          // total=0
        ))
        assertNotNull(AutoLoadPolicy.olderProbeReason(layoutOf(30, 21..29)))
        assertNull(AutoLoadPolicy.newerProbeReason(layoutOf(30, 15..21)))
        assertNotNull(AutoLoadPolicy.newerProbeReason(layoutOf(30, 0..6)))
    }
}
