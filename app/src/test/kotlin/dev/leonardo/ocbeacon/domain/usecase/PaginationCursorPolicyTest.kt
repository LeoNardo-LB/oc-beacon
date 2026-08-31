package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.util.CursorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #172 游标策略双版本契约 + 能力位映射。
 * V2 行为规格来源：2026-08-16 cursor-400 根治注释链（窗口语义，curl 实证）。
 */
class PaginationCursorPolicyTest {

    @Test
    fun `V1 local anchor encodes id-time pair`() {
        assertEquals(CursorCodec.encode("m1", 100L), V1CursorPolicy.localAnchorCursor("m1", 100L))
        assertNull(V1CursorPolicy.localAnchorCursor(null, 100L))
        assertNull(V1CursorPolicy.localAnchorCursor("m1", null))
    }

    @Test
    fun `V2 local anchor is null - fetch latest window`() {
        // V2 窗口语义：本地构造锚点不可靠 → 不传 cursor（服务器窗口 + id 去重）
        assertNull(V2CursorPolicy.localAnchorCursor("m1", 100L))
    }

    @Test
    fun `V1 around is single-direction with local anchor`() {
        val c = V1CursorPolicy.aroundCursors("m1", 100L)
        assertEquals(CursorCodec.encode("m1", 100L), c.older)
        assertNull(c.newer)
        assertFalse(c.supportsNewer)
    }

    @Test
    fun `V2 around is dual-direction`() {
        val c = V2CursorPolicy.aroundCursors("m1", 100L)
        assertEquals(CursorCodec.encodeV2("m1", CursorCodec.V2Direction.OLDER), c.older)
        assertEquals(CursorCodec.encodeV2("m1", CursorCodec.V2Direction.NEWER), c.newer)
        assertTrue(c.supportsNewer)
    }

    @Test
    fun `newer anchor - V2 encodes NEWER, V1 null`() {
        assertEquals(CursorCodec.encodeV2("m1", CursorCodec.V2Direction.NEWER), V2CursorPolicy.newerAnchorCursor("m1"))
        assertNull(V1CursorPolicy.newerAnchorCursor("m1"))
    }

    @Test
    fun `supportsNewerDirection capability`() {
        assertTrue(V2CursorPolicy.supportsNewerDirection)
        assertFalse(V1CursorPolicy.supportsNewerDirection)
    }

    // ===== 2026-09-01（定位跳转失效根因——DSH id 形态 vs V1）=====

    @Test
    fun `Dsh localAnchorCursor is null - latest window semantics`() {
        // DSH session.history 只有排他 beforeSeq（向后翻页），无增量/after 方向——
        // 增量 = 拉最新窗口 + id 去重（V2 同款）。V1 base64 游标会被 DshApiClient
        // 静默丢弃（toLongOrNull 失败），显式 null 使语义不再依赖静默丢弃。
        assertNull(DshCursorPolicy.localAnchorCursor("seq-4096", 100L))
        assertNull(DshCursorPolicy.localAnchorCursor(null, 100L))
    }

    @Test
    fun `Dsh around older is numeric beforeSeq`() {
        val c = DshCursorPolicy.aroundCursors("seq-4096", 100L)
        // 数字 beforeSeq——DshApiClient.listMessages 的排他游标（seq < 4096）
        assertEquals("4096", c.older)
        assertNull(c.newer)
        assertFalse(c.supportsNewer)
    }

    @Test
    fun `Dsh around for non seq id falls back null`() {
        // 流式宿主/工具卡宿主无 seq 分页语义：older 回退 null（不传游标 → 最新窗口）
        assertNull(DshCursorPolicy.aroundCursors("dsh-t1s10", 100L).older)
        assertNull(DshCursorPolicy.aroundCursors("dsh-call-call_abc", 100L).older)
    }

    @Test
    fun `Dsh newer direction unavailable`() {
        assertNull(DshCursorPolicy.newerAnchorCursor("seq-1"))
        assertFalse(DshCursorPolicy.supportsNewerDirection)
    }

    @Test
    fun `capabilities mapping per version`() {
        val v2 = ServerCapabilities.of(ApiVersion.V2)
        assertFalse(v2.shareSupported)
        assertTrue(v2.backgroundSessionsSupported)
        assertTrue(v2.runningSessionsFilterSupported)
        assertFalse(v2.configEditable)

        val v1 = ServerCapabilities.of(ApiVersion.V1)
        assertTrue(v1.shareSupported)
        assertFalse(v1.backgroundSessionsSupported)
        assertFalse(v1.runningSessionsFilterSupported)
        assertTrue(v1.configEditable)

        // null（未知/未加载）→ 全开放（原 permissive 比较语义保持）
        val unknown = ServerCapabilities.of(null)
        assertTrue(unknown.shareSupported)
        assertTrue(unknown.backgroundSessionsSupported)
        assertTrue(unknown.runningSessionsFilterSupported)
        assertTrue(unknown.configEditable)
    }
}
