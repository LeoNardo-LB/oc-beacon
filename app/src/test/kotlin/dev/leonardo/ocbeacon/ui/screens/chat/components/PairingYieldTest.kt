package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新bug 根修（SSE 输出上方内容闪烁消失）：GUARD 触底 pending 未被 measure 消费时，
 * flush task 读 stale 视口值误判非贴底 → 配对 set 覆盖 GUARD pending → 触底被抵消
 * +每批 +Δ 推走视口（取证：release set(fii=7,fiso 0→66→132…) 连续推高）。
 *
 * 修复缝：[shouldYieldPairing]——读位 ≠ 上批 set 目标 = 存在未消费的外部 pending
 * （GUARD/ForceScroll 显式意图）→ 本批让位（位置神圣：显式意图 > 引擎配对）。
 */
class PairingYieldTest {

    @Test
    fun `无上批目标不让位(首批)`() {
        assertFalse(shouldYieldPairing(7, 900, null, null))
    }

    @Test
    fun `读位等于上批目标(已消费)正常配对`() {
        assertFalse(shouldYieldPairing(7, 66, 7, 66))
        assertFalse(shouldYieldPairing(0, 0, 0, 0))
    }

    @Test
    fun `读位偏离上批目标=外部pending未消费 让位`() {
        // GUARD 触底 pending(0) 写入后，measure 前读值仍是旧位(7,66)——偏离上批目标(0,0)
        assertTrue(shouldYieldPairing(7, 66, 0, 0))
        assertTrue(shouldYieldPairing(7, 0, 0, 0))
        assertTrue(shouldYieldPairing(0, 3, 7, 66))
    }
}
