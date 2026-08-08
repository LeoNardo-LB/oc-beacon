package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
 * 注：lastCompletedReplyTimes 是 SettingsDataStore 的顶层扩展函数，
 * MockK 需 mockkStatic 才能 stub（参考 EventDispatcherUnreadTest 模式）。
 * saveLastCompletedReplyTimes 在 UnconfinedTestDispatcher scope 下经 relaxed mock
 * 链式调用静默返回，不需 stub。
 */
class UnreadBadgeServiceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val scope = CoroutineScope(testDispatcher + SupervisorJob())
    private val service = UnreadBadgeService(settingsDataStore, scope)

    @Test
    fun onMessageCompleted_keepsMax() {
        service.onMessageCompleted("ses_1", 100)
        service.onMessageCompleted("ses_1", 50)   // 更小 → 不回退
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

    @Test
    fun seedFromStorage_mergesMax() = runTest {
        // 扩展函数 lastCompletedReplyTimes 非成员，需 mockkStatic 才能 stub
        mockkStatic(SettingsDataStore::lastCompletedReplyTimes)
        every { settingsDataStore.lastCompletedReplyTimes() } returns
            flowOf(mapOf("ses_1" to 700L, "ses_2" to 100L))
        try {
            service.onMessageCompleted("ses_1", 500)  // 内存已有较小值

            service.seedFromStorage()

            assertEquals(700L, service.lastCompletedReplyTime.value["ses_1"])  // seed 更大 → 覆盖
            assertEquals(100L, service.lastCompletedReplyTime.value["ses_2"])  // 新增
        } finally {
            unmockkStatic(SettingsDataStore::lastCompletedReplyTimes)
        }
    }

    private fun assistant(id: String, completed: Long?): Message =
        Message.Assistant(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = 0, completed = completed),
            parentId = "p",
        )
}
