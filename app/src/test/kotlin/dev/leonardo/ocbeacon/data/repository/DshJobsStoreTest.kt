package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.JobView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshJobsStore 状态机测试（A：session/jobs 整快照 last-wins）。
 *
 * 对齐官方 dsh-client-runtime/lib/client.js:8307-8316：
 * - 整快照 last-wins 整替换（非合并）；
 * - 空集删键；subscribed 清空由空快照 applySnapshot(emptyList) 承载。
 */
class DshJobsStoreTest {

    private fun job(id: String, status: String) =
        JobView(id = id, kind = "bash", label = id, status = status, startedAt = 0L)

    @Test
    fun `applySnapshot is last-wins whole replacement not merge`() {
        val store = DshJobsStore()
        store.applySnapshot("s1", listOf(job("a", "running"), job("b", "completed")))
        // 第二帧整替换：不含 a——last-wins 不合并旧集
        store.applySnapshot("s1", listOf(job("c", "running")))
        assertEquals(listOf("c"), store.jobsFor("s1").map { it.id })
    }

    @Test
    fun `empty snapshot deletes session key`() {
        val store = DshJobsStore()
        store.applySnapshot("s1", listOf(job("a", "running")))
        store.applySnapshot("s1", emptyList())
        assertTrue(store.jobsBySession.value["s1"] == null)
        assertTrue(store.jobsFor("s1").isEmpty())
    }

    @Test
    fun `clearForSession and clear release state`() {
        val store = DshJobsStore()
        store.applySnapshot("s1", listOf(job("a", "running")))
        store.applySnapshot("s2", listOf(job("b", "completed")))
        store.clearForSession("s1")
        assertEquals(setOf("s2"), store.jobsBySession.value.keys)
        store.clear()
        assertTrue(store.jobsBySession.value.isEmpty())
    }
}
