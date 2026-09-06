package dev.leonardo.ocbeacon.ui.screens.sessions.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #331 A2-r3（2026-09-06 真机插桩终局）：fork 行「数据全绿但视口不见」的 UI 侧裁决。
 *
 * 设备取证（/tmp/a2r3_probe2.log + a2r3_fix.log，四 seam + 复核探针）：fork 行
 * 四 seam 全部落库/发射/入 contentState 头位，但屏幕首行仍是旧头——LazyColumn
 * 滚动位按 key 锚定在旧首行，返回后前插的新行位于锚点之上、视口不动；且数据
 * 变更重布局瞬间可见键读数抖动（first=新行/idx=1），故裁决只吃无竞态信号：
 * head id（纯数据）+ 组合期首可见索引（前次布局稳定值）。
 *
 * 裁决：head id 变化（有前插）且用户本在列表顶 → 揭示（scrollToItem(0)）。
 * 其余（初见/无变化/用户在列表中部）不动作。
 */
class SessionListPrependRevealTest {

    @Test
    fun latePrependWhileAwayFromTopIsRevealed() {
        // 设备场景还原：用户在列表顶离开（首可见索引 0），期间 fork 行前插到 head，
        // 返回后 head 变化被前次布局的 atTop 信号捕获 → 必须揭示
        assertTrue(
            shouldRevealPrependedHead(
                previousHeadId = "sess-old-head",
                newHeadId = "sess-fork-new",
                wasAtTopWhenChanged = true,
            ),
        )
    }

    @Test
    fun anchoredSecondRowAlsoReveals() {
        // 重布局竞态形态（真机复核取证 idx=1 抖动前的稳定读数）：前插把锚定旧头挤到
        // 索引 1 之前，组合期读到索引 0（旧头原位）——同属「用户在顶」→ 揭示
        assertTrue(
            shouldRevealPrependedHead(
                previousHeadId = "sess-old-head",
                newHeadId = "sess-fork-new",
                wasAtTopWhenChanged = true,
            ),
        )
    }

    @Test
    fun firstCompositionAfterReturnDoesNotScroll() {
        // 返回后首帧：prev 未记录（remember 重置）——即使状态已含新头也不动作
        assertFalse(
            shouldRevealPrependedHead(
                previousHeadId = null,
                newHeadId = "sess-fork-new",
                wasAtTopWhenChanged = true,
            ),
        )
    }

    @Test
    fun unchangedHeadDoesNotScroll() {
        // 列表内容更新但头未变（状态/时间戳刷新）→ 不动作
        assertFalse(
            shouldRevealPrependedHead(
                previousHeadId = "sess-old-head",
                newHeadId = "sess-old-head",
                wasAtTopWhenChanged = true,
            ),
        )
    }

    @Test
    fun userScrolledMidListIsNotYanked() {
        // 用户在列表中部浏览时 head 变化（别端新会话前插）→ 不拉动视口
        assertFalse(
            shouldRevealPrependedHead(
                previousHeadId = "sess-old-head",
                newHeadId = "sess-fork-new",
                wasAtTopWhenChanged = false,
            ),
        )
    }

    @Test
    fun nullNewHeadGuard() {
        // 树空（全部删除）→ 不动作
        assertFalse(
            shouldRevealPrependedHead(
                previousHeadId = "sess-old-head",
                newHeadId = null,
                wasAtTopWhenChanged = true,
            ),
        )
    }
}
