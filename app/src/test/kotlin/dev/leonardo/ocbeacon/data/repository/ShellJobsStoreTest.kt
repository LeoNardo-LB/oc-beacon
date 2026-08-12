package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.ShellJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellJobsStoreTest {

    private fun runningJob(id: String, sessionId: String?) = ShellJob(
        id = id,
        status = "running",
        command = "echo hi",
        sessionId = sessionId
    )

    private fun exitedJob(id: String, sessionId: String?, exit: Int = 0) = ShellJob(
        id = id,
        status = "exited",
        command = "echo hi",
        exit = exit,
        sessionId = sessionId
    )

    @Test
    fun `ended with missing sessionId still updates job started in session group`() {
        // 2026-08-12 修复：V2 shell.exited 事件 payload 无 metadata.sessionID
        // （ShellJob.sessionId=null），旧实现按 "" 组更新找不到 job → 卡 Running。
        val store = ShellJobsStore()
        store.onShellStarted(runningJob("sh_1", "ses_test"))
        assertEquals("running", store.jobsFor("ses_test").single().status)

        // ended 事件无 sessionId（V2 服务器实际格式）
        store.onShellEnded(exitedJob("sh_1", null, exit = 0), output = "done")

        val job = store.jobsFor("ses_test").single()
        assertEquals("exited", job.status)
        assertEquals(0, job.exit)
        assertEquals("done", job.output)
    }

    @Test
    fun `ended with sessionId updates normally`() {
        val store = ShellJobsStore()
        store.onShellStarted(runningJob("sh_2", "ses_a"))
        store.onShellEnded(exitedJob("sh_2", "ses_a", exit = 1), output = "err")

        val job = store.jobsFor("ses_a").single()
        assertEquals("exited", job.status)
        assertEquals(1, job.exit)
    }

    @Test
    fun `ended with missing sessionId and unknown id does not add phantom job`() {
        val store = ShellJobsStore()
        store.onShellStarted(runningJob("sh_3", "ses_a"))

        // 未知 id + 无 sessionId：不应补录到 "" 组（避免脏数据）
        store.onShellEnded(exitedJob("sh_unknown", null), output = "x")

        assertTrue(store.jobsFor("").isEmpty())
        assertEquals(1, store.jobsFor("ses_a").size)
        assertEquals("running", store.jobsFor("ses_a").single().status)
    }

    @Test
    fun `multiple sessions isolated`() {
        val store = ShellJobsStore()
        store.onShellStarted(runningJob("sh_a", "ses_a"))
        store.onShellStarted(runningJob("sh_b", "ses_b"))
        store.onShellEnded(exitedJob("sh_a", null), output = "done")

        assertEquals("exited", store.jobsFor("ses_a").single().status)
        assertEquals("running", store.jobsFor("ses_b").single().status)
        assertNull(store.jobsFor("ses_b").single().exit)
    }
}
