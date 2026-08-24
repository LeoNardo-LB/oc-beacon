package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.domain.usecase.ManageAgentUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import dev.leonardo.ocbeacon.domain.usecase.SelectModelUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #209 回归：contextWindow 唯一生产解析路径 = session.model → provider catalog 查表。
 *
 * tracker.contextWindow 死字段（生产恒 0，无写点）与 ModelConfigDelegate 的
 * tokenStats 优先分支已删除——本测试钉住删除后的真实路径：
 * - session.model 在 catalog 内 → contextWindow = 模型 limit.context
 * - session 无 model / catalog 查不到 → 0（UI 隐藏指示器，不崩溃）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelConfigContextWindowTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(testDispatcher + SupervisorJob())
    private val selectModelUseCase = mockk<SelectModelUseCase>()
    private val manageAgentUseCase = mockk<ManageAgentUseCase>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>()
    private val messagePaging = mockk<MessagePaginationUseCase>()
    private val tracker = TokenStatsTracker()
    private val sessionIdFlow = MutableStateFlow("s1")
    private val serverId = "svr"

    private val catalog = ProviderCatalog(
        id = "p",
        name = "Provider",
        models = mapOf(
            "m" to ModelCatalog(id = "m", name = "Model", contextWindow = 128000),
        ),
    )

    private fun sessionWith(model: Session.SessionModel?) = Session(
        id = "s1",
        title = "T",
        directory = "/t",
        time = Session.Time(created = 0, updated = 0),
        model = model,
    )

    private fun delegate() = ModelConfigDelegate(
        selectModelUseCase = selectModelUseCase,
        manageAgentUseCase = manageAgentUseCase,
        settingsRepository = settingsRepository,
        sessionRepository = sessionRepository,
        messagePaging = messagePaging,
        tokenStatsTracker = tracker,
        serverId = serverId,
        sessionIdFlow = sessionIdFlow,
        scope = scope,
    )

    private suspend fun stubAndLoad(session: Session, delegate: ModelConfigDelegate) {
        coEvery { selectModelUseCase.loadProviders(serverId) } returns
            ProvidersResponse(providers = listOf(catalog), default = mapOf("p" to "m"))
        every { sessionRepository.getSessionsFlow(serverId) } returns flowOf(listOf(session))
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())
        delegate.loadProviders()
    }

    @Test
    fun `session model in catalog resolves contextWindow from catalog`() = runTest(testDispatcher) {
        val d = delegate()
        stubAndLoad(sessionWith(Session.SessionModel(id = "m", providerId = "p")), d)
        assertEquals(128000, d.modelConfigState.first().contextWindow)
    }

    @Test
    fun `session without model yields zero (indicator hidden)`() = runTest(testDispatcher) {
        val d = delegate()
        stubAndLoad(sessionWith(model = null), d)
        assertEquals(0, d.modelConfigState.first().contextWindow)
    }

    @Test
    fun `session model missing from catalog yields zero`() = runTest(testDispatcher) {
        val d = delegate()
        stubAndLoad(sessionWith(Session.SessionModel(id = "unknown", providerId = "ghost")), d)
        assertEquals(0, d.modelConfigState.first().contextWindow)
    }

    @Test
    fun `tracker lastContextTokens alone does not fabricate denominator`() = runTest(testDispatcher) {
        // 分子存在、分母查不到 → 0（修复前 tracker 死字段也恒 0，但此测试钉住
        // 「不得从分子或别处伪造分母」的语义）
        val d = delegate()
        stubAndLoad(sessionWith(model = null), d)
        tracker.update { copy(lastContextTokens = 64000) }
        assertEquals(0, d.modelConfigState.first().contextWindow)
    }
}
