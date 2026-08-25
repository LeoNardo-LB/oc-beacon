package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UnreadBadgeService（红点时间源）单元测试。
 *
 * 红点语义不变量（2026-08-07 历史决策）：
 * - maxCompleted 只增不减（REST 快照滞后 completed=null 不移除）
 * - 只有 removeSession 移除；seed 合并取 max
 * - 判定只用服务器 completed
 *
 * 注：lastCompletedReplyTimes 是 UnreadStateStore 成员方法，relaxed mock 直接 every stub。
 * saveLastCompletedReplyTimes 在 UnconfinedTestDispatcher scope 下经 relaxed mock
 * 链式调用静默返回，不需 stub。
 */
class UnreadBadgeServiceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val unreadStateStore = mockk<UnreadStateStore>(relaxed = true)
    private val scope = CoroutineScope(testDispatcher + SupervisorJob())
    private val service = UnreadBadgeService(unreadStateStore, scope)

    @Test
    fun onMessageCompleted_keepsMax() {
        service.onMessageCompleted("ses_1", 100)
        service.onMessageCompleted("ses_1", 50)   // 更小 → 不倒退
        service.onMessageCompleted("ses_1", 200)

        assertEquals(200L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun recomputeMaxCompleted_onlyIncreases() {
        service.onMessageCompleted("ses_1", 300)
        // REST 快照滞后：completed=null → 不移除已记录的 max
        service.recomputeMaxCompleted("ses_1", listOf(assistant("msg_1", null)))

        assertEquals(300L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun recomputeMaxCompleted_updatesWhenLarger() {
        service.recomputeMaxCompleted(
            "ses_1",
            listOf(assistant("msg_1", 500), assistant("msg_2", 400)),
        )

        assertEquals(500L, service.lastCompletedReplyTime.value["ses_1"])
    }

    @Test
    fun removeSession_deletesEntry() {
        service.onMessageCompleted("ses_1", 100)
        service.removeSession("ses_1")

        assertEquals(null, service.lastCompletedReplyTime.value["ses_1"])
    }

    // ---- #184：markAllSessionsRead 作用域化（跨服务器时钟不混合）----

    @Test
    fun `markAllSessionsRead scopes max and broadcast to server session set`() = runTest {
        // 双服务器水位线共存：A（快钟）10_000 / B（慢钟）4_000
        service.onMessageCompleted("a1", 10_000L)
        service.onMessageCompleted("b1", 4_000L)

        // 停在 B 列表一键已读：只作用域 B 的会话集
        service.markAllSessionsRead("srvB", setOf("b1"))

        // 广播不溢出到 a1；b1 已读位 = B 域内 max（不是全局 10_000）
        assertEquals(mapOf("b1" to 4_000L), service.justRead.value)
        // 持久化收到本服务器域内值（allReadAt 不被 A 快钟污染）
        coVerify(exactly = 1) { unreadStateStore.markAllSessionsRead("srvB", 4_000L) }
        coVerify(exactly = 0) { unreadStateStore.markAllSessionsRead(any(), 10_000L) }
    }

    @Test
    fun `markAllSessionsRead no-op on empty session set or empty watermark`() = runTest {
        service.onMessageCompleted("a1", 10_000L)

        // 空会话集：不广播、不持久化（防止全局跨服务器 max 写入）
        service.markAllSessionsRead("srvB", emptySet())
        assertEquals(emptyMap<String, Long>(), service.justRead.value)
        coVerify(exactly = 0) { unreadStateStore.markAllSessionsRead(any(), any()) }

        // 集内无水位线记录：同样 no-op
        service.markAllSessionsRead("srvB", setOf("no_watermark"))
        assertEquals(emptyMap<String, Long>(), service.justRead.value)
        coVerify(exactly = 0) { unreadStateStore.markAllSessionsRead(any(), any()) }
    }

    @Test
    fun seedFromStorage_mergesMax() = runTest {
        // lastCompletedReplyTimes 是 UnreadStateStore 成员方法，relaxed mock 直接 every stub
        every { unreadStateStore.lastCompletedReplyTimes() } returns
            flowOf(mapOf("ses_1" to 700L, "ses_2" to 100L))

        service.onMessageCompleted("ses_1", 500)  // 内存已有较小值

        service.seedFromStorage()

        assertEquals(700L, service.lastCompletedReplyTime.value["ses_1"])  // seed 更大 → 覆盖
        assertEquals(100L, service.lastCompletedReplyTime.value["ses_2"])  // 新增
    }

    private fun assistant(id: String, completed: Long?): Message =
        Message.Assistant(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = 0, completed = completed),
            parentId = "p",
        )
}
