package dev.leonardo.ocbeacon.ui.sessions

import dev.leonardo.ocbeacon.ui.screens.sessions.components.shouldRevealArchiveBackground
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #342：归档揭示背景绘制判定——rest/复位/正向位移不绘制（透出列表 surface，
 * 红列表根因修复），仅负向位移（左滑揭示中）绘制。
 */
class ArchiveBackgroundRevealTest {

    @Test
    fun `rest and positive offsets do not reveal background`() {
        assertFalse(shouldRevealArchiveBackground(0f))
        assertFalse(shouldRevealArchiveBackground(1f))
        assertFalse(shouldRevealArchiveBackground(250f))
        // 亚像素抖动容错（-1f 边界内视为 rest）
        assertFalse(shouldRevealArchiveBackground(-0.5f))
        assertFalse(shouldRevealArchiveBackground(-1f))
    }

    @Test
    fun `negative swipe displacement reveals background`() {
        assertTrue(shouldRevealArchiveBackground(-1.5f))
        assertTrue(shouldRevealArchiveBackground(-120f))
        assertTrue(shouldRevealArchiveBackground(-1080f))
    }
}
