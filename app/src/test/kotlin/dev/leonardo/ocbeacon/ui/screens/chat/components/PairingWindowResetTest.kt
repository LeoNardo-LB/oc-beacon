package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #540 review 轮定罪回归：expandPairingWindows 是列表级引用计数，所有权归
 * 卡侧窗协程（开窗 ++ / 关窗 finally -- 严格配对）。换主 reset 若清零在飞窗
 * 计数：关窗减成 −1 → `expandPairingWindows > 0` 恒配对判据自此永久失效
 * （步边界换流式宿主可触发——DSH 多步 turn 的常规路径）。
 */
class PairingWindowResetTest {

    @Test
    fun `owner reset reinitializes hat fields but preserves open pairing windows`() {
        val state = HeightReserveState()
        state.itemKey = "host-a"
        state.reserved = 120
        state.trueHeight = 340
        state.arrangeForTest(windows = 2) // 两卡窗在飞

        Modifier.streamingHeightReserve(state, "host-b") // 组合期换主 reset

        assertEquals("帽字段应重立基线", "host-b", state.itemKey)
        assertEquals(-1, state.reserved)
        assertEquals(-1, state.trueHeight)
        assertEquals("换主不得清零卡侧在飞窗计数（负计数→恒配对永久失效）", 2, state.expandPairingWindows)
    }

    @Test
    fun `same owner reattach keeps everything`() {
        val state = HeightReserveState()
        state.itemKey = "host-a"
        state.reserved = 120
        state.trueHeight = 340
        state.arrangeForTest(windows = 1)

        Modifier.streamingHeightReserve(state, "host-a") // 同主重挂：非换主

        assertEquals(120, state.reserved)
        assertEquals(340, state.trueHeight)
        assertEquals(1, state.expandPairingWindows)
    }
}
