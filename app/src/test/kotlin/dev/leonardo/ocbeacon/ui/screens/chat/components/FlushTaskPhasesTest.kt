package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ScrollQuiescence
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreDrawFlushTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 批次 C：flush 八职责深拆的表征测试（characterization）——拆分**前**钉住
 * 现行行为，拆分后逐条仍绿即证等价（终审 S2 深拆的前置安全网；终审 P1
 * 「拒绘方向反转」教训的直接补课：布尔语义必须有接线级测试）。
 *
 * 覆盖职责：帽首帧初始化 / 帽-only 释放免配对（贴底原点）/ 拒绘返回值契约 /
 * 滚动弃配 rebaseAll / 空账早退 / 对齐随态翻转门（追平态或手势期）。
 * 让位记忆已有 FlushTaskMemoryTest；释放计划纯函数已有 ReserveReleasePlanTest。
 */
class FlushTaskPhasesTest {

    private fun mockState(
        fii: Int,
        fiso: Int,
        scrolling: Boolean = false,
    ): LazyListState = mockk(relaxed = true) {
        every { firstVisibleItemIndex } returns fii
        every { firstVisibleItemScrollOffset } returns fiso
        every { isScrollInProgress } returns scrolling
        every { layoutInfo } returns mockk<LazyListLayoutInfo> {
            every { visibleItemsInfo } returns emptyList()
        }
    }

    private fun emptyLedger(): StreamingGrowLedger = mockk(relaxed = true) {
        every { hasPending } returns false
    }

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    // ============ #502：外部定居位静止采纳（永久让位死锁根修） ============

    /** 可变读位 mock——同 task 跨帧驱动（模拟用户甩动后落位静止）。 */
    private fun mutableState(): LazyListState {
        val state = mockk<LazyListState>(relaxed = true) {
            every { layoutInfo } returns mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns emptyList()
            }
            every { isScrollInProgress } returns false
            every { firstVisibleItemIndex } returns 7
            every { firstVisibleItemScrollOffset } returns 393
        }
        return state
    }

    private fun readAt(state: LazyListState, fii: Int, fiso: Int) {
        every { state.firstVisibleItemIndex } returns fii
        every { state.firstVisibleItemScrollOffset } returns fiso
    }

    /** 反射读 task 闭包捕获的 mem（相位行为断言需要 lastSet 可观测——测试缝）。 */
    private fun memOf(task: PreDrawFlushTask): FlushTaskMemory {
        val fld = task.javaClass.getDeclaredFields().first { it.type == FlushTaskMemory::class.java }
        fld.isAccessible = true
        return fld.get(task) as FlushTaskMemory
    }

    @Test
    fun `外部定居位两帧静止后采纳并恢复帽释放 - 永久让位死锁根修`() {
        motive("#502 相位级主断言：真机 17:08 场景——引擎在阅读位 set(7,393+Δ) 后用户甩回底 (0,0)。旧行为：yield 每帧作废帽释放计划（真机 yield×1448 / reserved 冻 685 达 12s = 流式消息被裁剪在视口小块直到完结）。新行为：首帧让位观察，次帧静止即采纳基线并当帧释放帽")
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val state = mutableState()
        val reserve = HeightReserveState().apply { itemKey = "k1"; reserved = 685; trueHeight = 685 }
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 66f
        val task = streamingGrowFlushTask(state, ledger, reserve)

        // 帧 1：阅读位增长配对——引擎 apply set → lastSet=(7,459)（真机 set(7,393) 同构）
        task.onPreDraw()
        val mem = memOf(task)
        assertEquals(7, mem.lastSetFii)

        // 帧 2：用户甩回底 (0,0)，真高增长（1128>685 待释放）→ divergence 首帧：让位观察，帽不动
        every { ledger.hasPending } returns false
        every { ledger.takePaired(any(), any(), any()) } returns 0f
        readAt(state, 0, 0)
        reserve.trueHeight = 1128
        assertTrue(task.onPreDraw())
        assertEquals("让位观察帧不得释放帽", 685, reserve.reserved)

        // 帧 3：读位静止 (0,0) → 采纳基线 + 当帧帽释放（旧代码此处永远 yield——死锁点）
        assertTrue(task.onPreDraw())
        assertEquals("采纳后帽释放必须当帧生效（真高直通）", 1128, reserve.reserved)
        assertEquals(0, mem.lastSetFii)
    }

    @Test
    fun `引擎pending未消费窗口不采纳 - 近帧set保护`() {
        motive("#502 回归锚：引擎 set 请求时即写 lastSet、measure 消费前读位=旧位且静止（上方内容闪烁消失根修场景）——近帧（<2 帧）set 存在时不得把旧位采纳为基线毁掉 pending 保护")
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val state = mutableState()
        readAt(state, 7, 66)
        val reserve = HeightReserveState().apply { itemKey = "k1"; reserved = 100; trueHeight = 100 }
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 66f
        val task = streamingGrowFlushTask(state, ledger, reserve)

        // 帧 1：引擎配对 set → lastSet=(7,132)（目标位=读位+Δ），lastSetFrame=帧1
        task.onPreDraw()
        assertEquals(7, memOf(task).lastSetFii)

        // 帧 2：pending 未消费——读位仍旧位 (7,66) ≠ lastSet(7,132)；静止但 set 在近帧 → 继续让位
        every { ledger.hasPending } returns false
        reserve.trueHeight = 200
        assertTrue(task.onPreDraw())
        assertEquals("近帧 set 保护期不得采纳释放帽", 100, reserve.reserved)
    }

    @Test
    fun `帽首帧初始化 - reserved未初始化时trueHeight直通且零派发`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = -1
        }
        val task = streamingGrowFlushTask(mockState(0, 0), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(500, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `帽only释放贴底原点 - 免配对零滚动派发且reserved原子推进不拒绘`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1066
            reserved = 1000
        }
        // fii=0/fiso=5=贴底原点 → plan(paired=false)；visibleItems 空 → growthIndex=null 兜底同判
        val task = streamingGrowFlushTask(mockState(0, 5), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(1066, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    // ============ 五报 F4''（2026-10-12）：空驱动窗不得劫持贴底跟随 ============

    @Test
    fun `五报F4 空卡窗内帽增长回落锚规则 - 贴底原点免派发（拉锯根修）`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        motive("ep2 定罪场景：toggle 开窗但驱动 tgt 恒 -1（空内容卡）——旧行为窗内恒配对把流式增长升格「离底让位」，与回贴底力逐帧互搏（fiso 391↔0）；新行为回落 StreamingAnchorRule=贴底免派发")
        val empty = DegradeDriveState(hInitial = 0, hTarget = -1)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1391
            reserved = 1000
            expandPairingWindows = 1
            expandCollapseStickTop = 265
            expandCollapseSpanDepth = 0
            degradeDrives.add(empty)
        }
        val task = streamingGrowFlushTask(mockState(0, 0), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        // 帽推进但零滚动派发（贴底原点免派发）
        assertEquals(1391, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `五报F4 有效驱动窗内帽增长恒配对 - 离底让位语义保持`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val drive = DegradeDriveState(hInitial = 0, hTarget = 260, stickTop = 56, spanDepth = 0)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1000
            reserved = 1000
            expandPairingWindows = 1
            degradeDrives.add(drive)
        }
        val state = stateWithOwnerItem(0, 0, ownerIndex = 0)
        every { state.dispatchRawDelta(any()) } returns 0f
        streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw()
        // 首步进 0.32×260=83 配对派发（离底让位；落点由 resolvePairedTarget 走
        // 零高横幅带换算，断言派发次数+驱动推进而非具体坐标）
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
        assertEquals(83, drive.h)
    }

    @Test
    fun `ledger派发帧必须拒绘 - 终审P1契约`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true) {
            every { hasPending } returns true
            every { takePaired(any(), any(), any()) } returns 100f
        }
        val task = streamingGrowFlushTask(mockState(7, 900), ledger, reserve = null)
        // StreamingGrowNode 直报真高（无裁剪）——ledger 派发帧拒绘是中间态唯一防线
        assertEquals(false, task.onPreDraw())
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 7, 1000, null) }
    }

    @Test
    fun `滚动进行中 - 弃配rebaseAll零派发takePaired不触`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true) {
            every { hasPending } returns true
        }
        val task = streamingGrowFlushTask(mockState(7, 900, scrolling = true), ledger, reserve = null)
        assertTrue(task.onPreDraw())
        verify(exactly = 1) { ledger.rebaseAll() }
        verify(exactly = 0) { ledger.takePaired(any(), any(), any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `空账早退 - takePaired不触`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = emptyLedger()
        val task = streamingGrowFlushTask(mockState(3, 200), ledger, reserve = null)
        assertTrue(task.onPreDraw())
        verify(exactly = 0) { ledger.takePaired(any(), any(), any()) }
    }

    @Test
    fun `对齐随态翻转 - 追平态允许翻转`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = 500 // 追平：两种对齐像素等价=零位移翻转窗
            alignBottom = false
        }
        streamingGrowFlushTask(mockState(0, 0), emptyLedger(), reserve).onPreDraw()
        assertEquals(true, reserve.alignBottom)
    }

    @Test
    fun `对齐随态翻转 - 溢出且非手势期不翻转（刀锋防振荡）`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = 400 // 溢出中：place 偏移≠0，翻转=一次性位移
            alignBottom = true // wantBottom=false(fii=3) 与当前不同，但门不通电
        }
        streamingGrowFlushTask(mockState(3, 500), emptyLedger(), reserve).onPreDraw()
        assertEquals(true, reserve.alignBottom)
    }

    // ============ #540 方向 A：展开配对窗的 flush 接线 ============

    @Test
    fun `540 展开窗内帽释放恒配对 - 贴底原点走反射不nodis`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1066
            reserved = 1000
            expandPairingWindows = 1
        }
        // 贴底原点存量语义是 nodis；窗内必须反射配对（消除流式期展开上推源）
        val task = streamingGrowFlushTask(mockState(0, 5), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(1066, reserve.reserved)
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 0, 71, null) }
    }

    /** 可见窗内含物主 item 的 mock（物主守卫测试缝）。 */
    private fun stateWithOwnerItem(
        fii: Int,
        fiso: Int,
        ownerIndex: Int,
        ownerKey: Any = "k1",
        ownerSize: Int = 2400,
    ): LazyListState =
        mockk(relaxed = true) {
            every { firstVisibleItemIndex } returns fii
            every { firstVisibleItemScrollOffset } returns fiso
            every { isScrollInProgress } returns false
            every { layoutInfo } returns mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns listOf(
                    mockk<androidx.compose.foundation.lazy.LazyListItemInfo> {
                        every { index } returns ownerIndex
                        every { key } returns ownerKey
                        every { size } returns ownerSize
                    },
                )
            }
        }

    @Test
    fun `540 二轮 未捕获几何的窗内负向 - 反射保守镜像 不再走dispatch`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 900
            reserved = 1000
            expandPairingWindows = 1
            // 未捕获 stickTop/spanDepth（默认 -1）——保守镜像：100−100=0
        }
        val state = stateWithOwnerItem(0, 100, ownerIndex = 0)
        every { state.dispatchRawDelta(any()) } returns -100f
        val task = streamingGrowFlushTask(state, emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(900, reserve.reserved)
        // 二轮裁决（2026-10-11）：负向弃 dispatch 镜像（底缘钳制缺口 D3 定罪）
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 0, 0, null) }
    }

    @Test
    fun `540 二轮修订 锚在塌缩区内 - 逐帧镜像骑行不跳揭示缘`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200 // 本帧收缩后真高
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 锚 fiso=200 → 距顶 4200−200=4000 ≤ 4800（镜像侧）。修订前「跳揭示缘」
        // 在锚深体内时一次性拖下半部分（用户复测定罪）；修订后逐帧镜像：
        // 200−800 → 钳 0
        val state = stateWithOwnerItem(3, 200, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 0, null) }
    }

    @Test
    fun `540 二修订 锚在盒下方新内容侧 - 零操作仅帽回写`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 夹具：盒 [4000,4100]（span 收窄为 100），盒下方新内容 [4100,4200]；
        // 锚 fiso=50 → 距顶 4150 > 4100（盒下方）——零操作
        reserve.expandCollapseSpanDepth = 100
        val state = stateWithOwnerItem(3, 50, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 review 物主守卫 - 锚不在物主item按区外零操作`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 排队发消息形态：物主可见于 index 7，锚在 index 3（fiso 属他 item——
        // anchorTop=itemTrueH−fiso 为跨 item 垃圾值，曾可误判入区向错误 item
        // 写 stick 目标；review 轮 P2 守卫后一律区外零操作）
        val state = stateWithOwnerItem(3, 200, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 review 混合帧拆账 - 镜像格ledger增长折加不被吞`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 输出中收起且锚在镜像侧（距顶 4200−1500=2700 ≤4800）：帽净负 −800 镜像
        // 骑行 + 同帧 ledger 增长 +150 折加——review 轮 P1 拆账：1500−800+150=850
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 150f
        val state = stateWithOwnerItem(3, 1500, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 850, null) }
    }

    @Test
    fun `540 review 混合帧拆账 - noop格（盒下方）ledger仍折加`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 100 // 盒 [4000,4100]，下方 [4100,4200]
        }
        // 锚 fiso=50 → 距顶 4150（盒下方）塌缩零操作，但 ledger +150 仍配对
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 150f
        val state = stateWithOwnerItem(3, 50, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        // noop 格 ledger 折加：50+150=200（塌缩分量零操作）
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 200, null) }
    }

    @Test
    fun `540 review 亚像素负余量 - 净量(-1,0)不崩flush`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4999
            reserved = 5000
            expandPairingWindows = 1
        }
        // 帽净负 −1（整数 delta）+ ledger 分数 +0.6 → total=−0.4∈(−1,0)：
        // 旧实现 total.toInt()=0 传 require(delta<0) 抛 IAE 崩 flush（review 轮
        // P2 防御缺口）。新实现以 reserveShift（−1）过判定、ledger 折加取整。
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 0.6f
        val state = stateWithOwnerItem(3, 500, ownerIndex = 3)
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        // 无崩溃即主断言；未捕获几何走镜像格：500−1+0=499
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 499, null) }
    }

    // ============ #540 三轮：降级驱动（AV 尺寸退役→flush 单事务步进）============

    @Test
    fun `540 三轮 驱动步进纯函数 - 指数逼近·终态直达·挂起守卫`() {
        assertEquals(-160, degradeDriveStep(500, 0))   // 收起向首步 32%
        assertEquals(30, degradeDriveStep(100, 196))   // 展开向 96×0.32=30.72→30
        assertEquals(-2, degradeDriveStep(2, 0))       // ≤2px 终态直达
        assertEquals(0, degradeDriveStep(0, 0))        // 稳态零步进
        assertEquals(0, degradeDriveStep(50, -1))      // 展开中等测高：挂起不步进
        assertEquals(1, degradeDriveStep(0, 3))        // 截断保底 1px 防停滞
        assertEquals(-1, degradeDriveStep(3, 0))
    }

    @Test
    fun `540 三轮 纯驱动帧 - 步进与滚动写同快照原子推进（震荡根修）`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val drive = DegradeDriveState(hInitial = 1000, hTarget = 0, stickTop = 405, spanDepth = 1000)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 2303 // 实测已含历史步进；预记账恰好追平 → 纯驱动帧
            reserved = 2303
            expandPairingWindows = 1
            expandCollapseStickTop = 405
            expandCollapseSpanDepth = 1000
            degradeDrives.add(drive)
        }
        // 锚 fiso=1303 → 距顶 2303−1303=1000 ≤ 1405（镜像侧）
        val state = stateWithOwnerItem(7, 1303, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        val task = streamingGrowFlushTask(state, emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        // 步进 −320 与滚动写同事务：目标 1303−320=983；弃 dispatch（二轮裁决）
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 7, 983, null) }
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        // 驱动态推进 + 帽预记步进（=trueHeight+step；旧 `=trueHeight` 会残差帧
        // 再释放一步=重复记账）
        assertEquals(680, drive.h)
        assertEquals(1983, reserve.reserved)
    }

    @Test
    fun `540 三轮 手势帧 - 驱动步进随计划丢弃冻结不推进`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val drive = DegradeDriveState(hInitial = 500, hTarget = 0)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1000
            reserved = 1000
            expandPairingWindows = 1
            degradeDrives.add(drive)
        }
        val state = mockk<LazyListState>(relaxed = true) {
            every { firstVisibleItemIndex } returns 7
            every { firstVisibleItemScrollOffset } returns 100
            every { isScrollInProgress } returns true // 手势进行中：上游更硬的门
            every { layoutInfo } returns mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns emptyList()
            }
        }
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(500, drive.h) // 冻结未推进（幂等重算，非错位）
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 三轮 展开步进锚在揭示缘上方 - 否决配对但高度仍原子推进`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val drive = DegradeDriveState(hInitial = 0, hTarget = 1000, stickTop = 1500, spanDepth = 400)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 2000
            reserved = 2000
            expandPairingWindows = 1
            expandCollapseStickTop = 1500
            expandCollapseSpanDepth = 400
            degradeDrives.add(drive)
        }
        // 锚距顶 2000−1000=1000 < 1500 → 锚在揭示缘上方：正步进否决配对（二轮
        // 修订同款——锚所见内容不动，配对位移=自造推动）
        val state = stateWithOwnerItem(7, 1000, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        // 高度照推 + 帽预记；滚动零写（否决）
        assertEquals(320, drive.h)
        assertEquals(2320, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 四报 多卡驱动 - 锚上方卡镜像+锚下方卡零操作,双卡高度均推进`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        // 卡A：stick=300 区 [300,1300]，步 -320（锚 anchorTop=1000 区内→镜像）
        val driveA = DegradeDriveState(hInitial = 1000, hTarget = 0, stickTop = 300, spanDepth = 1000)
        // 卡B：stick=100 区 [100,150]，步 -160（锚 1000 > 150 卡下方→零操作）
        val driveB = DegradeDriveState(hInitial = 500, hTarget = 0, stickTop = 100, spanDepth = 50)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 3500
            reserved = 3500
            expandPairingWindows = 1
            expandCollapseStickTop = 100 // reserve 级镜像=最新 toggle（卡B）
            expandCollapseSpanDepth = 50
            degradeDrives.add(driveA)
            degradeDrives.add(driveB)
        }
        // 锚 fiso=2500 → anchorTop = 3500−2500 = 1000
        val state = stateWithOwnerItem(7, 2500, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        // 滚动分量=只有卡A（-320）：目标 2500−320=2180；卡B 零操作（区外）
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 7, 2180, null) }
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        // 双卡高度都推进（原子性不分卡）
        assertEquals(680, driveA.h)
        assertEquals(340, driveB.h)
        // 帽预记 = trueHeight + Σ(-480)
        assertEquals(3020, reserve.reserved)
    }

    @Test
    fun `540 四报 driveScrollShift 纯函数 - containment 逐卡判定与未捕获保守`() {
        val a = DegradeDriveState(hInitial = 100, hTarget = 0, stickTop = 100, spanDepth = 50).apply { pendingStep = -30 }
        val b = DegradeDriveState(hInitial = 100, hTarget = 0, stickTop = 400, spanDepth = 50).apply { pendingStep = -20 }
        val uncaptured = DegradeDriveState(hInitial = 100, hTarget = 0).apply { pendingStep = -10 }
        // 语义：锚在卡上方（anchorTop<stick）或区内 → 该卡塌缩移动锚内容 → 计入；
        //        锚在卡下方（anchorTop>stick+span）→ 零操作。锚 120 在 A 区内且在 B 上方 → 三者全计入
        assertEquals(-60, driveScrollShift(120, listOf(a, b, uncaptured)))
        // 锚 500：在 A 下方（500>150）且 B 下方（500>450）→ 仅未捕获保守计入
        assertEquals(-10, driveScrollShift(500, listOf(a, b, uncaptured)))
        // 边界：锚=stick+span 恰在界上（区内含，A 计入；锚仍处 B 上方计入）
        assertEquals(-50, driveScrollShift(150, listOf(a, b)))
        // 锚 151：A 下方零操作；B 上方仍计入
        assertEquals(-20, driveScrollShift(151, listOf(a, b)))
    }

    // ============ 五报（2026-10-12）F2/F3：符号感知 + 正向逐卡配对 ============

    @Test
    fun `五报 F2 driveScrollShift 符号感知 - 正步进不经塌缩 containment 表`() {
        // 定罪场景：净负混合帧中正步进被 span=0 展开态几何判 outside-span 拒发
        //（r2 取证 drv=+56/+38 noop），高度推进无配对=残差偏移源——正步进一律 0。
        val expanding = DegradeDriveState(hInitial = 0, hTarget = 260, stickTop = 56, spanDepth = 0).apply { pendingStep = 56 }
        val collapsing = DegradeDriveState(hInitial = 870, hTarget = 0, stickTop = 56, spanDepth = 870).apply { pendingStep = -38 }
        // 锚在区内（两卡 containment 都会过）——但正步进不计入
        assertEquals(-38, driveScrollShift(100, listOf(expanding, collapsing)))
        // 正步进单独在场：恒 0（即便锚在揭示缘上方）
        assertEquals(0, driveScrollShift(10, listOf(expanding)))
    }

    @Test
    fun `五报 F3 drivePositivePairedShift - 揭示缘逐卡否决与未捕获保守`() {
        val a = DegradeDriveState(hInitial = 0, hTarget = 260, stickTop = 100).apply { pendingStep = 56 }
        val b = DegradeDriveState(hInitial = 0, hTarget = 300, stickTop = 400).apply { pendingStep = 30 }
        val uncaptured = DegradeDriveState(hInitial = 0, hTarget = 200).apply { pendingStep = 10 }
        val neg = DegradeDriveState(hInitial = 870, hTarget = 0, stickTop = 100, spanDepth = 870).apply { pendingStep = -38 }
        // 锚在揭示缘上方（anchorTop<stick）：该卡增长在锚下方，配对=自造位移 → 否决
        assertEquals(0, drivePositivePairedShift(80, listOf(a)))          // 锚在 A 上方→否决
        assertEquals(10, drivePositivePairedShift(80, listOf(a, b, uncaptured, neg))) // 双卡皆否决+负步进0，仅未捕获保守
        assertEquals(56, drivePositivePairedShift(100, listOf(a)))        // 锚=stick 恰在缘→配对（离底让位）
        assertEquals(86, drivePositivePairedShift(500, listOf(a, b)))     // 锚在两卡下方→全配对（56+30）
        assertEquals(10, drivePositivePairedShift(80, listOf(uncaptured))) // 未捕获→保守配对（legacy 盲配）
        assertEquals(0, drivePositivePairedShift(500, listOf(neg)))       // 负步进不经本表
    }

    @Test
    fun `551 离底让位预算截断 - 超预算截断与小卡零影响`() {
        // 七报定罪形态：巨卡全高配对让位推飞视口（fiso +11.6k≈5 屏）。
        // 预算内（小卡）全配对；超预算截断到剩余量；负帽不占预算。
        assertEquals(500, capDepartureByBudget(drivePos = 300, hatPos = 200, budgetLeft = 1000))   // 预算内全量
        assertEquals(800, capDepartureByBudget(drivePos = 600, hatPos = 300, budgetLeft = 800))    // 恰好
        assertEquals(700, capDepartureByBudget(drivePos = 900, hatPos = 500, budgetLeft = 700))    // 截断
        assertEquals(0, capDepartureByBudget(drivePos = 400, hatPos = 100, budgetLeft = 0))        // 耗尽
        assertEquals(300, capDepartureByBudget(drivePos = 300, hatPos = -50, budgetLeft = 1000))   // 负帽不占
    }

    @Test
    fun `551八报 窗外阅读跟随不受预算截断 - 预算耗尽仍配对`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        motive("八报热修回归钉：窗耗尽预算后，稳态（无窗）阅读位增长配对必须照常派发——被截断会把阅读位逐帧往下拖");
        val reserve = HeightReserveState().apply {
            itemKey = "k1"; trueHeight = 2000; reserved = 1000
            expandDepartureBudgetLeft = 0   // 预算已耗尽（此前 episode 用完）
        }
        // 阅读位（fii=7 深处）增长配对——无窗：不得吃预算
        val state = stateWithOwnerItem(7, 900, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw()
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 7, 1900, any()) }
        assertEquals(0, reserve.expandDepartureBudgetLeft)  // 预算未被消费
        assertEquals(2000, reserve.reserved)
    }
}
