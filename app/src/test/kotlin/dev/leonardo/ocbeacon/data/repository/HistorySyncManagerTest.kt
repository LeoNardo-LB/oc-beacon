package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.local.SessionSyncDao
import dev.leonardo.ocbeacon.data.local.SessionSyncEntity
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * #271：HistorySyncManager 状态机单测——synced 幂等/正常 drain/失败标记/取消回退。
 * 真实调度器 + 有界轮询（manager 自持 IO scope，避免 runTest 虚拟时钟与真实 IO 死锁）。
 */
class HistorySyncManagerTest {

    private val serverId = "srv_1"
    private val sessionId = "ses_test"

    private fun message(id: String) = MessageWithParts(
        info = Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = 1_000L), parentId = "p0"),
        parts = emptyList(),
    )

    private fun page(messages: List<MessageWithParts>, nextCursor: String?) =
        MessagePage(messages = messages, nextCursor = nextCursor)

    private fun syncEntity(state: String, lastSyncAt: Long? = null) =
        SessionSyncEntity(sessionId = sessionId, state = state, lastSyncAt = lastSyncAt)

    /** 有界轮询：条件成立或超时（毫秒）。 */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    private class FakeDao(initial: List<SessionSyncEntity> = emptyList()) : SessionSyncDao {
        val rows = LinkedHashMap<String, SessionSyncEntity>(initial.associateBy { it.sessionId })
        private val flow = MutableStateFlow(initial)
        override suspend fun upsert(state: SessionSyncEntity) {
            rows[state.sessionId] = state
            flow.value = rows.values.toList()
        }
        override suspend fun get(sessionId: String): SessionSyncEntity? = rows[sessionId]
        override fun observeAll() = flow
        override suspend fun clearSession(sessionId: String) {
            rows.remove(sessionId)
            flow.value = rows.values.toList()
        }
        override suspend fun clearAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }

    private fun newManager(
        dao: FakeDao,
        sessionRepository: SessionRepository,
        statuses: Map<String, SessionStatus> = emptyMap(),
    ): HistorySyncManager {
        val sessionStateRepository = mockk<SessionStateRepository>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(statuses)
        val messageStore = mockk<MessageStore>(relaxed = true)
        return HistorySyncManager(sessionRepository, messageStore, dao, sessionStateRepository)
    }

    @Test
    fun `已 synced 的会话 requestSync 不再触发 drain`() = runBlocking(Dispatchers.IO) {
        val dao = FakeDao(listOf(syncEntity(SessionSyncEntity.STATE_SYNCED, lastSyncAt = 123L)))
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        val manager = newManager(dao, sessionRepository)
        awaitUntil { manager.syncStates.value[sessionId]?.state == SessionSyncEntity.STATE_SYNCED }

        manager.requestSync(serverId, sessionId)
        delay(200) // 若误触发 drain，会进入 syncing

        assertTrue(manager.syncStates.value[sessionId]?.state == SessionSyncEntity.STATE_SYNCED)
        coVerify(exactly = 0) { sessionRepository.listMessages(any(), any(), any(), any()) }
        Unit
    }

    @Test
    fun `正常 drain 后状态为 synced 且消息已入库`() = runBlocking(Dispatchers.IO) {
        val dao = FakeDao()
        var call = 0
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.listMessages(serverId, sessionId, any(), any()) } answers {
            call++
            if (call == 1) Result.success(page(listOf(message("m1")), "c1")) else Result.success(page(listOf(message("m2")), null))
        }
        val messageStore = mockk<MessageStore>(relaxed = true)
        val sessionStateRepository = mockk<SessionStateRepository>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        val manager = HistorySyncManager(sessionRepository, messageStore, dao, sessionStateRepository)

        manager.requestSync(serverId, sessionId)
        awaitUntil { dao.rows[sessionId]?.state == SessionSyncEntity.STATE_SYNCED }

        assertEquals(SessionSyncEntity.STATE_SYNCED, dao.rows[sessionId]?.state)
        assertTrue((dao.rows[sessionId]?.lastSyncAt ?: 0L) > 0)
        coVerify(exactly = 2) { messageStore.upsertMessages(sessionId, any(), persistOldBeyondWindow = true) }
        Unit
    }

    @Test
    fun `listMessages 失败时状态为 failed`() = runBlocking(Dispatchers.IO) {
        val dao = FakeDao()
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.listMessages(serverId, sessionId, any(), any()) } throws IOException("boom")
        val sessionStateRepository = mockk<SessionStateRepository>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        val manager = HistorySyncManager(sessionRepository, mockk<MessageStore>(relaxed = true), dao, sessionStateRepository)

        manager.requestSync(serverId, sessionId)
        awaitUntil { dao.rows[sessionId]?.state == SessionSyncEntity.STATE_FAILED }

        assertEquals(SessionSyncEntity.STATE_FAILED, dao.rows[sessionId]?.state)
        assertEquals("boom", dao.rows[sessionId]?.errorMessage)
        Unit
    }

    @Test
    fun `取消后状态回未同步`() = runBlocking(Dispatchers.IO) {
        val dao = FakeDao()
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.listMessages(serverId, sessionId, any(), any()) } coAnswers {
            delay(60_000) // 模拟慢分页
            Result.success(page(emptyList(), null))
        }
        val sessionStateRepository = mockk<SessionStateRepository>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        val manager = HistorySyncManager(sessionRepository, mockk<MessageStore>(relaxed = true), dao, sessionStateRepository)

        manager.requestSync(serverId, sessionId)
        awaitUntil { dao.rows[sessionId]?.state == SessionSyncEntity.STATE_SYNCING }
        manager.cancel(sessionId)
        awaitUntil { dao.rows[sessionId]?.state == SessionSyncEntity.STATE_NONE }

        assertEquals(SessionSyncEntity.STATE_NONE, dao.rows[sessionId]?.state)
        Unit
    }
}
