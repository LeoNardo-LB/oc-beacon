package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.DshJobsStore
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.domain.model.JobView
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskAggregator Shell 面板数据源分流测试（#391 切片9：能力位门控）。
 *
 * JOBS_PUSH → dshJobs 走 DshJobsStore、shells 恒空；SHELL → shells 走 ShellJobsStore、
 * dshJobs 恒空（V2 会话行为零改动）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskAggregatorJobsBranchingTest {

    private fun job(id: String, status: String) =
        JobView(id = id, kind = "bash", label = id, status = status, startedAt = 0L)

    private fun shell(id: String) = ShellJob(id = id, status = "running", command = id)

    private fun caps(features: Set<ServerFeature>) =
        ServerCapabilities(CoreFlags(false, false, false, false), features)

    private fun buildAggregator(
        caps: ServerCapabilities,
        shellStore: ShellJobsStore,
        dshStore: DshJobsStore,
    ): TaskAggregator {
        val sessionRepo = mockk<SessionRepository>(relaxed = true)
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { sessionRepo.getSessionsFlow(any()) } returns flowOf(emptyList())
        every { sessionRepo.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
        every { chatRepo.getAllPartsMap() } returns flowOf(emptyMap())
        return TaskAggregator(
            sessionRepository = sessionRepo,
            chatRepository = chatRepo,
            shellJobsStore = shellStore,
            dshJobsStore = dshStore,
            capabilitiesFlow = flowOf(caps),
            serverId = "server1",
            sessionIdFlow = flowOf("s1"),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
    }

    @Test
    fun `dsh server branches to dshJobs source with shells empty`() = runTest {
        val dshStore = DshJobsStore()
        dshStore.applySnapshot("s1", listOf(job("a", "running")))
        val agg = buildAggregator(caps(setOf(ServerFeatures.JOBS_PUSH)), ShellJobsStore(), dshStore)
        advanceUntilIdle()
        val state = agg.uiState.value
        assertEquals(listOf("a"), state.dshJobs.map { it.id })
        assertTrue(state.shells.isEmpty())
    }

    @Test
    fun `opencode server branches to shells source with dshJobs empty`() = runTest {
        val shellStore = ShellJobsStore()
        shellStore.onShellStarted(shell("sh-1").copy(sessionId = "s1"))
        val agg = buildAggregator(caps(setOf(ServerFeatures.SHELL)), shellStore, DshJobsStore())
        advanceUntilIdle()
        val state = agg.uiState.value
        assertEquals(listOf("sh-1"), state.shells.map { it.id })
        assertTrue(state.dshJobs.isEmpty())
    }
}
