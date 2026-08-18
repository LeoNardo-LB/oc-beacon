package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.PaginationCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PaginationFSM] 纯函数转移矩阵测试（#56 TD-1）。
 * 覆盖：游标推进（ARCHIVE/NETWORK/空页）、hasOlder 边界、防风暴退避/暂停/恢复。
 * 参照 SessionStateFSM 测试风格：给定 (state, event) → 断言新 state。
 */
class PaginationFSMTest {

    private val now = 1_000_000L

    private fun succeeded(
        source: LoadOlderSource,
        oldestId: String? = null,
        oldestCreated: Long? = null,
        nextCursor: String? = null,
        pageSize: Int,
        limit: Int,
    ) = PaginationFSM.Event.LoadSucceeded(source, oldestId, oldestCreated, nextCursor, pageSize, limit)

    // ============ SessionReloaded ============

    @Test
    fun `sessionReloaded resets cursor to hot start and sets hasOlder`() {
        val before = PaginationFSM.State(
            cursor = PaginationCursor.Network(serverCursor = "server-cursor-1", id = "m-50", created = 50L),
            hasOlderMessages = true,
            autoLoadFailures = 2,
            autoLoadPaused = true,
        )
        val after = PaginationFSM.transition(before, PaginationFSM.Event.SessionReloaded(true))
        assertEquals(PaginationCursor.HotStart, after.cursor)
        assertTrue(after.hasOlderMessages)
        // 会话重载只重置游标/hasOlder，不触碰防风暴状态（由后续加载结果决定）
        assertEquals(2, after.autoLoadFailures)
        assertTrue(after.autoLoadPaused)
    }

    // ============ LoadSucceeded: ARCHIVE ============

    @Test
    fun `archive success advances archive cursor and keeps hasOlder true`() {
        val before = PaginationFSM.State(cursor = PaginationCursor.HotStart)
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.ARCHIVE,
                oldestId = "m-100",
                oldestCreated = 100L,
                pageSize = 30,
                limit = 30,
            ),
        )
        assertEquals(PaginationCursor.Archive(100L), after.cursor)
        assertTrue("ARCHIVE 来源 always hasOlder=true（归档桶未读尽）", after.hasOlderMessages)
        assertEquals(0, after.autoLoadFailures)
        assertFalse(after.autoLoadPaused)
        assertEquals(0L, after.autoLoadPausedUntil)
    }

    @Test
    fun `archive empty page keeps cursor`() {
        val before = PaginationFSM.State(cursor = PaginationCursor.Archive(50L))
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.ARCHIVE,
                oldestId = null,
                oldestCreated = null,
                pageSize = 0,
                limit = 30,
            ),
        )
        // 防御：空页不推进游标（与重构前 ?: archiveCursorCreated 语义一致）
        assertEquals(PaginationCursor.Archive(50L), after.cursor)
        assertTrue(after.hasOlderMessages)
    }

    // ============ LoadSucceeded: NETWORK ============

    @Test
    fun `network success with server cursor stores it for next page`() {
        val before = PaginationFSM.State(cursor = PaginationCursor.HotStart)
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-60",
                oldestCreated = 60L,
                nextCursor = "server-cursor-60",
                pageSize = 30,
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertEquals("server-cursor-60", cursor.serverCursor)
        assertEquals("m-60", cursor.id)
        assertEquals(60L, cursor.created)
        assertTrue("满页 → 服务器可能还有更早", after.hasOlderMessages)
    }

    @Test
    fun `network success without server cursor falls back to id plus created`() {
        // V1 路径：无服务器游标 → Network(id, created)（use case 用 CursorCodec 编码）
        val before = PaginationFSM.State(cursor = PaginationCursor.HotStart)
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-60",
                oldestCreated = 60L,
                nextCursor = null,
                pageSize = 30,
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertNull(cursor.serverCursor)
        assertEquals("m-60", cursor.id)
        assertEquals(60L, cursor.created)
    }

    @Test
    fun `network overlap page with server cursor keeps hasOlder true even if partial`() {
        // 2026-08-18 回归（V2 长会话历史不可达）：null-cursor 首翻返回的重叠页——
        // 即使页大小 < limit（服务器窗口截断），只要携带 cursor.next 就还有更早。
        val before = PaginationFSM.State(cursor = PaginationCursor.HotStart)
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-60",
                oldestCreated = 60L,
                nextCursor = "server-cursor-60",
                pageSize = 12, // 不足一页但有游标
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertEquals("server-cursor-60", cursor.serverCursor)
        assertTrue("服务器游标非空 → 一定还有更早", after.hasOlderMessages)
    }

    @Test
    fun `network overlap full page with server cursor advances to network state`() {
        // 2026-08-18 回归：V2 首翻 null-cursor 路径的满页重叠场景——
        // 满页（全是已加载重复）+ cursor.next → FSM 必须进入 Network(serverCursor)
        // 态且 hasOlder=true，后续翻页才能透传服务器游标。
        val before = PaginationFSM.State(cursor = PaginationCursor.HotStart)
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-30",
                oldestCreated = 30L,
                nextCursor = "native-next-cursor",
                pageSize = 30,
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertEquals("native-next-cursor", cursor.serverCursor)
        assertTrue(after.hasOlderMessages)
    }

    @Test
    fun `network partial page sets hasOlder false`() {
        // 2026-08-18 勘误：本测试原参数 nextCursor 非空却断言读尽——自相矛盾
        //（服务器返回游标 = 一定还有更早，见 LoadNewerSucceeded 对称语义）。
        // 修正为真正的读尽场景：不足一页且无游标。
        val before = PaginationFSM.State(cursor = PaginationCursor.Archive(30L))
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-90",
                oldestCreated = 90L,
                nextCursor = null,
                pageSize = 10,
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertNull(cursor.serverCursor)
        assertEquals("m-90", cursor.id)
        assertFalse("不足一页且无游标 → 已读尽", after.hasOlderMessages)
    }

    @Test
    fun `network empty page keeps cursor and hasOlder false`() {
        val before = PaginationFSM.State(
            cursor = PaginationCursor.Network(serverCursor = "server-cursor-60", id = "m-60", created = 60L),
            hasOlderMessages = true,
        )
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = null,
                oldestCreated = null,
                nextCursor = null,
                pageSize = 0,
                limit = 30,
            ),
        )
        // 空页：不推进游标（读尽后 UI 停止触发，无死循环），hasOlder=false
        val cursor = after.cursor as PaginationCursor.Network
        assertEquals("server-cursor-60", cursor.serverCursor)
        assertFalse(after.hasOlderMessages)
    }

    @Test
    fun `success resets backoff and unpauses`() {
        val before = PaginationFSM.State(
            cursor = PaginationCursor.Archive(10L),
            autoLoadFailures = 3,
            autoLoadPausedUntil = now + 8000L,
            autoLoadPaused = true,
        )
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-1",
                oldestCreated = 1L,
                nextCursor = "server-cursor-1",
                pageSize = 30,
                limit = 30,
            ),
        )
        assertEquals(0, after.autoLoadFailures)
        assertEquals(0L, after.autoLoadPausedUntil)
        assertFalse(after.autoLoadPaused)
    }

    // ============ LoadFailed: 退避 / 暂停 ============

    @Test
    fun `first failure sets 500ms backoff without pause`() {
        val after = PaginationFSM.transition(
            PaginationFSM.State(),
            PaginationFSM.Event.LoadFailed(now),
        )
        assertEquals(1, after.autoLoadFailures)
        assertEquals(now + 500L, after.autoLoadPausedUntil)
        assertFalse(after.autoLoadPaused)
    }

    @Test
    fun `failures backoff grows exponentially`() {
        var state = PaginationFSM.State()
        val waits = mutableListOf<Long>()
        repeat(4) {
            state = PaginationFSM.transition(state, PaginationFSM.Event.LoadFailed(now))
            waits += state.autoLoadPausedUntil - now
        }
        assertEquals(listOf(500L, 1000L, 2000L, 4000L), waits)
        // 第 4 次失败已超 MAX=3 → 暂停
        assertTrue(state.autoLoadPaused)
    }

    @Test
    fun `pauses after max consecutive failures`() {
        var state = PaginationFSM.State()
        repeat(3) {
            state = PaginationFSM.transition(state, PaginationFSM.Event.LoadFailed(now))
        }
        assertEquals(3, state.autoLoadFailures)
        assertTrue(state.autoLoadPaused)
        assertEquals(now + 2000L, state.autoLoadPausedUntil)
    }

    @Test
    fun `backoff caps at 8s shift limit`() {
        // 超过 MAX_SHIFT 后退避不再增长（2^4=16 倍 = 8s）
        val longFailures = PaginationFSM.State(autoLoadFailures = 6, autoLoadPaused = true)
        val after = PaginationFSM.transition(longFailures, PaginationFSM.Event.LoadFailed(now))
        assertEquals(7, after.autoLoadFailures)
        assertEquals(now + 8000L, after.autoLoadPausedUntil)
    }

    // ============ AroundLoaded（loadAround 双向定位加载） ============

    @Test
    fun `aroundLoaded sets both older and newer cursors`() {
        val before = PaginationFSM.State()
        val olderCursor = PaginationCursor.Network(serverCursor = "older-next", id = "m-old", created = 10L)
        val newerCursor = PaginationCursor.Network(serverCursor = "newer-prev", id = "m-new", created = 90L)
        val after = PaginationFSM.transition(
            before,
            PaginationFSM.Event.AroundLoaded(
                olderCursor = olderCursor,
                hasOlderMessages = true,
                newerCursor = newerCursor,
                hasNewerMessages = true,
            ),
        )
        assertEquals(olderCursor, after.cursor)
        assertTrue(after.hasOlderMessages)
        assertEquals(newerCursor, after.newerCursor)
        assertTrue(after.hasNewerMessages)
    }

    @Test
    fun `aroundLoaded with null newer cursor disables newer direction`() {
        // V1 降级：newer 不可用
        val after = PaginationFSM.transition(
            PaginationFSM.State(),
            PaginationFSM.Event.AroundLoaded(
                olderCursor = PaginationCursor.Network(id = "m-old", created = 10L),
                hasOlderMessages = true,
                newerCursor = null,
                hasNewerMessages = false,
            ),
        )
        assertTrue(after.hasOlderMessages)
        assertNull(after.newerCursor)
        assertFalse(after.hasNewerMessages)
    }

    @Test
    fun `aroundLoaded resets backoff and unpauses`() {
        val before = PaginationFSM.State(
            autoLoadFailures = 3,
            autoLoadPaused = true,
            autoLoadPausedUntil = now + 8000L,
        )
        val after = PaginationFSM.transition(
            before,
            PaginationFSM.Event.AroundLoaded(
                olderCursor = null,
                hasOlderMessages = false,
                newerCursor = null,
                hasNewerMessages = false,
            ),
        )
        assertEquals(0, after.autoLoadFailures)
        assertFalse(after.autoLoadPaused)
        // olderCursor=null → 回落 HotStart
        assertEquals(PaginationCursor.HotStart, after.cursor)
    }

    // ============ LoadNewerSucceeded（更新方向游标推进） ============

    @Test
    fun `loadNewerSucceeded advances newer cursor with server previousCursor`() {
        val before = PaginationFSM.State(
            newerCursor = PaginationCursor.Network(serverCursor = "prev-cursor-1", id = "m-50", created = 50L),
            hasNewerMessages = true,
        )
        val after = PaginationFSM.transition(
            before,
            PaginationFSM.Event.LoadNewerSucceeded(
                newestId = "m-80",
                newestCreated = 80L,
                previousCursor = "prev-cursor-2",
                pageSize = 30,
                limit = 30,
            ),
        )
        val cursor = after.newerCursor as PaginationCursor.Network
        assertEquals("prev-cursor-2", cursor.serverCursor)
        assertEquals("m-80", cursor.id)
        assertEquals(80L, cursor.created)
        assertTrue("满页 + 有游标 → 还有更新", after.hasNewerMessages)
    }

    @Test
    fun `loadNewerSucceeded without previousCursor falls back to id plus created`() {
        // V1 路径：无服务器游标 → Network(id, created)
        val before = PaginationFSM.State(
            newerCursor = PaginationCursor.Network(id = "m-50", created = 50L),
            hasNewerMessages = true,
        )
        val after = PaginationFSM.transition(
            before,
            PaginationFSM.Event.LoadNewerSucceeded(
                newestId = "m-80",
                newestCreated = 80L,
                previousCursor = null,
                pageSize = 30,
                limit = 30,
            ),
        )
        val cursor = after.newerCursor as PaginationCursor.Network
        assertNull(cursor.serverCursor)
        assertEquals("m-80", cursor.id)
        assertEquals(80L, cursor.created)
        // 满页但无游标 → 可能还有（保守 true）
        assertTrue(after.hasNewerMessages)
    }

    @Test
    fun `loadNewerSucceeded empty page clears newer direction`() {
        // 读尽：空页 + 无游标 → hasNewer=false（UI 停止触发）
        val before = PaginationFSM.State(
            newerCursor = PaginationCursor.Network(serverCursor = "prev-1", id = "m-90", created = 90L),
            hasNewerMessages = true,
        )
        val after = PaginationFSM.transition(
            before,
            PaginationFSM.Event.LoadNewerSucceeded(
                newestId = null,
                newestCreated = null,
                previousCursor = null,
                pageSize = 0,
                limit = 30,
            ),
        )
        // 空页无 id/created → newerCursor=null
        assertNull(after.newerCursor)
        assertFalse(after.hasNewerMessages)
    }

    @Test
    fun `loadNewerSucceeded partial page with no cursor sets hasNewer false`() {
        val after = PaginationFSM.transition(
            PaginationFSM.State(hasNewerMessages = true),
            PaginationFSM.Event.LoadNewerSucceeded(
                newestId = "m-95",
                newestCreated = 95L,
                previousCursor = null,
                pageSize = 10,
                limit = 30,
            ),
        )
        // 不足一页 + 无游标 → 已到最新
        assertFalse(after.hasNewerMessages)
    }

    // ============ SessionReloaded 重置 newer 方向 ============

    @Test
    fun `sessionReloaded resets newer direction`() {
        val before = PaginationFSM.State(
            cursor = PaginationCursor.Network(serverCursor = "old", id = "m-1", created = 1L),
            hasOlderMessages = true,
            newerCursor = PaginationCursor.Network(serverCursor = "new", id = "m-99", created = 99L),
            hasNewerMessages = true,
        )
        val after = PaginationFSM.transition(before, PaginationFSM.Event.SessionReloaded(true))
        // older 方向重置
        assertEquals(PaginationCursor.HotStart, after.cursor)
        assertTrue(after.hasOlderMessages)
        // newer 方向重置（回到最新边界）
        assertNull(after.newerCursor)
        assertFalse(after.hasNewerMessages)
    }
}
