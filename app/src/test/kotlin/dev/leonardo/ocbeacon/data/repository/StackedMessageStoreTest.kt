package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * #348 堆积消息仓库单测——入队/编辑/移除/清除 + drain 触发门槛
 * （Idle 转移、待处理跳过、归属未知跳过、发送失败保留）。
 */
class StackedMessageStoreTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope

    private val statuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val repo = mockk<SessionStateRepository>(relaxed = true)
    private val dataStore = mockk<DataStore<Preferences>>(relaxed = true)
    private val sendUseCase = mockk<SendMessageUseCase>(relaxed = true)
    private val provider = Provider { sendUseCase }

    private lateinit var store: StackedMessageStore

    @Before
    fun setup() {
        scope = CoroutineScope(testDispatcher + SupervisorJob())
        every { repo.statusFlow } returns statuses
        every { repo.serverIdFor(any()) } returns "server-1"
        every { repo.hasPendingUserInput(any()) } returns false
        every { dataStore.data } returns MutableStateFlow(emptyPreferences())
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        store = StackedMessageStore(scope, dataStore, repo, provider)
        store.disableCompensationHeartbeat()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `enqueue appends in order per session`() {
        store.enqueue("server-1", "s1", "first")
        store.enqueue("server-1", "s1", "second")
        val list = store.stackedBySession.value["s1"].orEmpty()
        assertEquals(listOf("first", "second"), list.map { it.text })
    }

    @Test
    fun `enqueue blank text is no-op`() {
        store.enqueue("server-1", "s1", "   ")
        assertTrue(store.stackedBySession.value["s1"].isNullOrEmpty())
    }

    @Test
    fun `remove and updateText target correct entry`() {
        store.enqueue("server-1", "s1", "a")
        store.enqueue("server-1", "s1", "b")
        val idA = store.stackedBySession.value["s1"]!!.first().id
        store.updateText("s1", idA, "a2")
        assertEquals(listOf("a2", "b"), store.stackedBySession.value["s1"]!!.map { it.text })
        store.remove("s1", idA)
        assertEquals(listOf("b"), store.stackedBySession.value["s1"]!!.map { it.text })
    }

    @Test
    fun `clear removes session bucket`() {
        store.enqueue("server-1", "s1", "x")
        store.clear("s1")
        assertTrue(store.stackedBySession.value["s1"].isNullOrEmpty())
    }

    @Test
    fun `idle transition drains head via sendPrompt and removes it`() = runTest(testDispatcher) {
        store.enqueue("server-1", "s1", "hello")
        advanceUntilIdle()
        coVerify(exactly = 0) { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any(), any()) }

        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sendUseCase.sendPrompt(
                serverId = "server-1",
                sessionId = "s1",
                parts = any(),
                model = any(),
                agent = any(),
                variant = any(),
                directory = any(),
                steer = any(),
            )
        }
        assertTrue(store.stackedBySession.value["s1"].isNullOrEmpty())
    }

    @Test
    fun `pending user input blocks drain`() = runTest(testDispatcher) {
        every { repo.hasPendingUserInput("s1") } returns true
        store.enqueue("server-1", "s1", "q")
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(1, store.stackedBySession.value["s1"]!!.size)
    }

    @Test
    fun `unknown server ownership blocks drain`() = runTest(testDispatcher) {
        every { repo.serverIdFor("s1") } returns null
        store.enqueue("server-1", "s1", "q")
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(1, store.stackedBySession.value["s1"]!!.size)
    }

    @Test
    fun `send failure keeps message for heartbeat retry`() = runTest(testDispatcher) {
        coEvery { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        store.enqueue("server-1", "s1", "keepme")
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(listOf("keepme"), store.stackedBySession.value["s1"]!!.map { it.text })
    }

    @Test
    fun `sendOneNow drains head without idle gate`() = runTest(testDispatcher) {
        store.enqueue("server-1", "s1", "manual")
        advanceUntilIdle()
        store.sendOneNow("s1")
        advanceUntilIdle()
        assertTrue(store.stackedBySession.value["s1"].isNullOrEmpty())
    }
}