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
    fun `network partial page sets hasOlder false`() {
        val before = PaginationFSM.State(cursor = PaginationCursor.Archive(30L))
        val after = PaginationFSM.transition(
            before,
            succeeded(
                source = LoadOlderSource.NETWORK,
                oldestId = "m-90",
                oldestCreated = 90L,
                nextCursor = "server-cursor-90",
                pageSize = 10,
                limit = 30,
            ),
        )
        val cursor = after.cursor as PaginationCursor.Network
        assertEquals("server-cursor-90", cursor.serverCursor)
        assertFalse("不足一页 → 已读尽", after.hasOlderMessages)
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
}
