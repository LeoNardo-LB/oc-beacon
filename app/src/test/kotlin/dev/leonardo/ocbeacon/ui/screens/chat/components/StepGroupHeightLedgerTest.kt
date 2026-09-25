package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #427 P2 高度账本外部行为（spec §Testing Decisions 预定 seam）：
 * Σ 守恒、宽度变化失效、跨回收持久化往返、冷降级触发条件、差异封顶（≤单片高）。
 * 账本本体为纯数据结构——组合级行为（rememberSaveable 附着）由 P3 宿主接线。
 */
class StepGroupHeightLedgerTest {

    private val fps = listOf("fp1", "fp2", "fp3")

    @Test
    fun newLedgerIsCold() {
        val ledger = StepGroupHeightLedger()
        assertFalse(ledger.isWarm(fps))
        assertNull(ledger.heightOf("fp1"))
        assertEquals(0, ledger.totalHeight(fps))
    }

    @Test
    fun recordedSlicesSumToTotal() {
        val ledger = StepGroupHeightLedger()
        ledger.record(widthKey = 1080, fingerprint = "fp1", heightPx = 100)
        ledger.record(widthKey = 1080, fingerprint = "fp2", heightPx = 200)
        ledger.record(widthKey = 1080, fingerprint = "fp3", heightPx = 300)
        assertTrue(ledger.isWarm(fps))
        assertEquals(600, ledger.totalHeight(fps))
        assertEquals(200, ledger.heightOf("fp2"))
    }

    @Test
    fun partialRecordsRemainCold() {
        // 冷降级触发条件：任一片缺席即冷（引擎降级整体 ε 沉降）
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 100)
        ledger.record(1080, "fp2", 200)
        assertFalse(ledger.isWarm(fps))
        // 部分和仅可作 floor/debug
        assertEquals(300, ledger.totalHeight(fps))
    }

    @Test
    fun widthChangeInvalidatesAllEntries() {
        // 旋转/折叠形态：宽度键变化 → 全量失效重算（spec 用户故事 9）
        val ledger = StepGroupHeightLedger()
        fps.forEachIndexed { i, fp -> ledger.record(1080, fp, (i + 1) * 100) }
        assertTrue(ledger.isWarm(fps))
        ledger.record(720, "fp1", 80)
        assertFalse(ledger.isWarm(fps))
        assertNull(ledger.heightOf("fp2"))
        assertEquals(80, ledger.heightOf("fp1"))
        // 新宽度下重新量满 → 暖
        ledger.record(720, "fp2", 160)
        ledger.record(720, "fp3", 240)
        assertTrue(ledger.isWarm(fps))
        assertEquals(480, ledger.totalHeight(fps))
    }

    @Test
    fun sameWidthRecordsAccumulate() {
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 100)
        ledger.record(1080, "fp2", 100)
        assertEquals(2, ledger.entryCount)
    }

    @Test
    fun reMeasurementReplacesHeightAndShiftBoundedBySlice() {
        // 差异封顶：单片重测只有该片高度变化，Σ 漂移=|new-old| ≤ 该片旧高
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 100)
        ledger.record(1080, "fp2", 200)
        ledger.record(1080, "fp3", 300)
        val before = ledger.totalHeight(fps)
        ledger.record(1080, "fp2", 250)
        val after = ledger.totalHeight(fps)
        assertEquals(50, after - before)
        assertTrue(kotlin.math.abs(after - before) <= 200)
    }

    @Test
    fun pruneDropsOrphanedEntries() {
        // 内容变化 → 指纹集变化 → 旧键修剪（防无限增长）
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "old1", 100)
        ledger.record(1080, "old2", 100)
        ledger.prune(validFingerprints = listOf("fp1", "fp2"))
        assertFalse(ledger.isWarm(listOf("old1", "old2")))
        assertNull(ledger.heightOf("old1"))
        // 有效键不受影响
        ledger.record(1080, "fp1", 120)
        ledger.record(1080, "fp2", 140)
        assertTrue(ledger.isWarm(listOf("fp1", "fp2")))
    }

    @Test
    fun encodedLedgerRoundTripsThroughPersistence() {
        // 跨回收持久化往返（rememberSaveable bundle 值即此字符串）
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 111)
        ledger.record(1080, "fp2", 222)
        val restored = StepGroupHeightLedger.fromEncoded(ledger.encode())
        assertTrue(restored.isWarm(listOf("fp1", "fp2")))
        assertEquals(333, restored.totalHeight(listOf("fp1", "fp2")))
        assertEquals(111, restored.heightOf("fp1"))
    }

    @Test
    fun coldLedgerEncodesToRoundTrippableEmptyForm() {
        val ledger = StepGroupHeightLedger()
        val restored = StepGroupHeightLedger.fromEncoded(ledger.encode())
        assertFalse(restored.isWarm(fps))
        assertEquals(0, restored.entryCount)
    }

    @Test
    fun decodingGarbageYieldsColdLedger() {
        val restored = StepGroupHeightLedger.fromEncoded("not a ledger at all")
        assertFalse(restored.isWarm(fps))
        assertEquals(0, restored.entryCount)
    }

    @Test
    fun decodingNullYieldsColdLedger() {
        val restored = StepGroupHeightLedger.fromEncoded(null)
        assertFalse(restored.isWarm(fps))
    }

    @Test
    fun fingerprintedEntriesDoNotCollideAcrossOrder() {
        // 同片不同序（内容重排）→ 指纹互斥 → 各自独立记账
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "s#p1,t3;", 100)
        ledger.record(1080, "s#p2,t3;", 200)
        assertEquals(100, ledger.heightOf("s#p1,t3;"))
        assertEquals(200, ledger.heightOf("s#p2,t3;"))
    }

    // ===== #437 验收十三轮：进程级账本店 + Σ 桩高 =====

    @Test
    fun storeReturnsSameInstanceAcrossBranchSwap() {
        // 根修三核心语义：同一 msgId 的账本在"分支互换/子树重建"（等价于丢弃
        // rememberSaveable 后再次 getOrCreate）后必须还是同一实例——暖度存活。
        StepGroupLedgerStore.clearForTest()
        val first = StepGroupLedgerStore.getOrCreate("m1")
        first.record(1080, "fp1", 100)
        first.record(1080, "fp2", 200)
        val second = StepGroupLedgerStore.getOrCreate("m1")
        assertTrue(first === second)
        assertTrue(second.isWarm(listOf("fp1", "fp2")))
    }

    @Test
    fun storeKeepsSeparateLedgersPerMsgId() {
        StepGroupLedgerStore.clearForTest()
        val a = StepGroupLedgerStore.getOrCreate("msgA")
        val b = StepGroupLedgerStore.getOrCreate("msgB")
        assertFalse(a === b)
        a.record(1080, "fp1", 100)
        assertNull(b.heightOf("fp1"))
    }

    @Test
    fun storeEvictsEldestBeyondCap() {
        StepGroupLedgerStore.clearForTest()
        // 装满 CAP+1 个键 → 最老键被逐出（LRU 上限防膨胀）
        for (i in 0..129) StepGroupLedgerStore.getOrCreate("k$i")
        val eldest = StepGroupLedgerStore.getOrCreate("k0") // 已被逐出 → 新实例
        assertFalse(eldest.isWarm(listOf("fp1")))
    }

    @Test
    fun stubHeightMatchesLedgerSumPlusSpacingWhenWarm() {
        // 根修二核心语义：全暖时桩高 = Σ片高 + 间距×(n-1)，与窗口宿主总高式对齐
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 100)
        ledger.record(1080, "fp2", 200)
        ledger.record(1080, "fp3", 300)
        assertEquals(600 + 4 * 2, stubHeightPx(ledger, fps, sliceCount = 3, spacingPx = 4))
    }

    @Test
    fun stubHeightNullWhenColdOrMismatched() {
        val ledger = StepGroupHeightLedger()
        ledger.record(1080, "fp1", 100)
        // 任一片冷 → null（调用方退 24dp 固定桩）
        assertNull(stubHeightPx(ledger, fps, sliceCount = 3, spacingPx = 4))
        // 指纹数与片数不一致 → null（防御：切片重派生中间态）
        assertNull(stubHeightPx(ledger, listOf("fp1"), sliceCount = 3, spacingPx = 4))
        // 零片 → null
        assertNull(stubHeightPx(ledger, emptyList(), sliceCount = 0, spacingPx = 4))
    }
}
