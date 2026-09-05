package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task4：PendingInteractionStore 单测——记录（last-wins）+ 三清除路径
 * （①本地应答 clearIfKind / ②轮次结束状态转移兜底 / ③级联 clearForSession）。
 */
class PendingInteractionStoreTest {

    private val statuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val repo = mockk<SessionStateRepository> { every { statusFlow } returns statuses }
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun newStore() =
        PendingInteractionStore(repo, CoroutineScope(testDispatcher + SupervisorJob()))

    // ============ 记录 ============

    @Test
    fun `record stores kind per session and last wins`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.record("s2", PendingInteractionKind.QUESTION)
        assertEquals(
            mapOf("s1" to PendingInteractionKind.APPROVAL, "s2" to PendingInteractionKind.QUESTION),
            store.pendingBySession.value,
        )
        store.record("s1", PendingInteractionKind.QUESTION)
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"])
    }

    // ============ 清除① 本地应答成功（clearIfKind）============

    @Test
    fun `clearIfKind removes entry of same kind`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
        assertNull(store.pendingBySession.value["s1"])
    }

    @Test
    fun `clearIfKind keeps entry of different kind`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.QUESTION)
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"])
    }

    @Test
    fun `question family cross clears plan-review`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.PLAN_REVIEW)
        store.clearIfKind("s1", PendingInteractionKind.QUESTION)
        assertNull(store.pendingBySession.value["s1"])
    }

    @Test
    fun `clearIfKind on unknown session is no-op`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("other", PendingInteractionKind.APPROVAL)
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"])
    }

    // ============ 清除② 轮次结束兜底（状态流订阅）============

    @Test
    fun `busy to idle transition clears entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"])

        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertNull(store.pendingBySession.value["s1"])
    }

    @Test
    fun `busy without idle keeps entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"])
    }

    @Test
    fun `asking to idle transition clears entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Asking)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"])

        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertNull(store.pendingBySession.value["s1"])
    }

    @Test
    fun `idle without prior active status keeps entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        // 首见即 Idle（无先前态）——非转移，不清
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"])
    }

    @Test
    fun `other session going idle does not clear this entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        statuses.value = mapOf("s1" to SessionStatus.Busy, "s2" to SessionStatus.Busy)
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Busy, "s2" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"])
    }

    // ============ 清除③ 会话删除级联 ============

    @Test
    fun `clearForSession removes entry`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.record("s2", PendingInteractionKind.QUESTION)
        store.clearForSession("s1")
        assertNull(store.pendingBySession.value["s1"])
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s2"])
    }

    @Test
    fun `clearAll empties map`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearAll()
        assertEquals(emptyMap<String, PendingInteractionKind>(), store.pendingBySession.value)
    }
}
