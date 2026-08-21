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
