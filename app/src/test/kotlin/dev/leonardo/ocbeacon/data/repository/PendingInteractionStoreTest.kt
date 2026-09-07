package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
            mapOf(
                "s1" to PendingInteractionEntry(PendingInteractionKind.APPROVAL),
                "s2" to PendingInteractionEntry(PendingInteractionKind.QUESTION),
            ),
            store.pendingBySession.value,
        )
        store.record("s1", PendingInteractionKind.QUESTION)
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)
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
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"]?.kind)
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
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"]?.kind)
    }

    // ============ #339：伪 Idle 边沿延后复核 ============

    @Test
    fun `transient replay idle edge followed by busy keeps entry`() = runTest(testDispatcher) {
        // 真机实证形态（W5 21:06:59.723）：回放交错产生瞬态 Idle 边沿，
        // 后续事件又回 Busy（终态仍 Busy）——不得误清仍挂起的 pending
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceTimeBy(100)  // 延后复核窗口内（<2s）
        statuses.value = mapOf("s1" to SessionStatus.Busy)  // 回放继续，边沿被覆盖
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)
    }

    @Test
    fun `idle persisting beyond settle window still clears entry`() = runTest(testDispatcher) {
        // 真实轮末 Idle 持续在场 → 延后复核照常清除（兜底语义不变）
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceTimeBy(IDLE_CLEAR_SETTLE_MS + 100)
        assertNull(store.pendingBySession.value["s1"])
    }

        // ============ 清除② 轮次结束兜底（状态流订阅）============

    @Test
    fun `busy to idle transition clears entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"]?.kind)

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
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)
    }

    @Test
    fun `asking to idle transition clears entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Asking)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)

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
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"]?.kind)
    }

    @Test
    fun `other session going idle does not clear this entry`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        statuses.value = mapOf("s1" to SessionStatus.Busy, "s2" to SessionStatus.Busy)
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Busy, "s2" to SessionStatus.Idle)
        advanceUntilIdle()
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["s1"]?.kind)
    }

    // ============ 清除③ 会话删除级联 ============

    @Test
    fun `clearForSession removes entry`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.record("s2", PendingInteractionKind.QUESTION)
        store.clearForSession("s1")
        assertNull(store.pendingBySession.value["s1"])
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s2"]?.kind)
    }

    @Test
    fun `clearAll empties map`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearAll()
        assertEquals(emptyMap<String, PendingInteractionEntry>(), store.pendingBySession.value)
    }

    // ============ #336：已通知槽（补发去重——「已发过的不重发」）============

    @Test
    fun `markNotified records slot and isNotified reflects it`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        org.junit.Assert.assertFalse(store.isNotified("s1"))
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        org.junit.Assert.assertTrue(store.isNotified("s1"))
    }

    @Test
    fun `clearIfKind also clears notified slot for next round`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
        org.junit.Assert.assertFalse(store.isNotified("s1"))
    }

    @Test
    fun `clearForSession and clearAll clear notified slots`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.record("s2", PendingInteractionKind.QUESTION)
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        store.markNotified("s2", PendingInteractionKind.QUESTION)
        store.clearForSession("s1")
        org.junit.Assert.assertFalse(store.isNotified("s1"))
        org.junit.Assert.assertTrue(store.isNotified("s2"))
        store.clearAll()
        org.junit.Assert.assertFalse(store.isNotified("s2"))
    }

    @Test
    fun `busy to idle fallback clears notified slot`() = runTest(testDispatcher) {
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION)
        store.markNotified("s1", PendingInteractionKind.QUESTION)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        assertNull(store.pendingBySession.value["s1"])
        org.junit.Assert.assertFalse(store.isNotified("s1"))
    }

    @Test
    fun `record with different kind resets notified slot`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        // kind 切换 = 新等待态 → 通知机会重置
        store.record("s1", PendingInteractionKind.QUESTION)
        org.junit.Assert.assertFalse(store.isNotified("s1"))
    }

    @Test
    fun `record with same kind keeps notified slot`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        // 同 kind 再记录（重放/追加同类请求）——单值域等待态延续，不重置
        //（防 SSE 冷启重放清槽 → 退后台重复补发）
        store.record("s1", PendingInteractionKind.APPROVAL)
        org.junit.Assert.assertTrue(store.isNotified("s1"))
    }
    // ============ #344：条目载荷文本（补发通知的正文源）============

    @Test
    fun `record carries payload text for catch-up notification`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION, "For the instrument test, choose A or B.")
        assertEquals("For the instrument test, choose A or B.", store.pendingBySession.value["s1"]?.text)
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)
    }

    @Test
    fun `same kind re-record keeps existing text when new text null`() {
        // 重放（SSE 冷启）不带载荷——不得抹掉既有补发正文源
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION, "Q1")
        store.record("s1", PendingInteractionKind.QUESTION, null)
        assertEquals("Q1", store.pendingBySession.value["s1"]?.text)
    }

    @Test
    fun `same kind re-record with non-null text overwrites`() {
        // 追加同类请求 last-wins——新问题正文可补发
        val store = newStore()
        store.record("s1", PendingInteractionKind.QUESTION, "Q1")
        store.record("s1", PendingInteractionKind.QUESTION, "Q2")
        assertEquals("Q2", store.pendingBySession.value["s1"]?.text)
    }

    @Test
    fun `kind switch drops old payload text`() {
        val store = newStore()
        store.record("s1", PendingInteractionKind.APPROVAL, "bash touch")
        store.record("s1", PendingInteractionKind.QUESTION, "New question")
        assertEquals("New question", store.pendingBySession.value["s1"]?.text)
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["s1"]?.kind)
    }
}