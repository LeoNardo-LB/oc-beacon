package dev.leonardo.ocbeacon.ui.screens.home

import android.app.Application
import android.util.Log
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageServerProvidersUseCase
import dev.leonardo.ocbeacon.domain.usecase.UpdateSettingsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #339（2026-09-07 用户裁决）：通知深链重连腿 [HomeViewModel.awaitServerReachable]。
 *
 * 裁决语义：挂起通知点击后尝试重连服务器——连得上进会话、连不上退回服务器
 * 选择页（通知本体保持滞留）。本测试钉死三态：健康检查失败=快速 false；
 * 未连接时确实触发 connectToServer（connecting 进入场）；未知服务器=水化等满
 * false。连接成功态由 service 观察流驱动（设备腿验证）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelAwaitReachableTest {

    private val application: Application = mockk(relaxed = true)
    private val serverRepository: ServerRepository = mockk()
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase = mockk()
    private val updateSettingsUseCase: UpdateSettingsUseCase = mockk(relaxed = true)
    private val manageServerProvidersUseCase: ManageServerProvidersUseCase = mockk(relaxed = true)
    private val diagnosticLogRepository: dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository = mockk(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun server(id: String = "s1") = ServerConfig(
        id = id,
        url = "http://10.255.255.1:9",
        name = "Test",
    )

    private fun createViewModel(): HomeViewModel {
        every { serverRepository.getServersFlow() } returns flowOf(listOf(server()))
        every { getSettingsFlowUseCase() } returns flowOf(AppSettings())
        return HomeViewModel(
            application,
            serverRepository,
            getSettingsFlowUseCase,
            updateSettingsUseCase,
            manageServerProvidersUseCase,
            diagnosticLogRepository,
            dev.leonardo.ocbeacon.testing.FakeServerAdapterResolver(),
        )    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun healthCheckFailureReturnsFalseFast() = runTest {
        coEvery { serverRepository.testConnection(any()) } returns Result.failure(IllegalStateException("down"))
        val vm = createViewModel()

        val reachable = vm.awaitServerReachable("s1", timeoutMs = 5_000)

        assertFalse(reachable)
        assertFalse("s1" in vm.uiState.value.connectingServerIds)
        assertTrue("s1" in vm.uiState.value.connectionErrors.keys)
    }

    @Test
    fun disconnectedServerTriggersConnectDuringAwait() = runTest {
        val healthCheck = CompletableDeferred<Result<Boolean>>()
        coEvery { serverRepository.testConnection(any()) } coAnswers { healthCheck.await() }
        val vm = createViewModel()

        // 悬挂健康检查下等待超时——但 connectToServer 必须已被触发（connecting 在场）
        val reachable = vm.awaitServerReachable("s1", timeoutMs = 120)
        assertFalse(reachable)
        assertTrue(
            "await must have triggered connectToServer (connecting entered)",
            "s1" in vm.uiState.value.connectingServerIds,
        )
        healthCheck.complete(Result.success(false))
    }

    @Test
    fun unknownServerIdWaitsOutHydrationThenFalse() = runTest {
        coEvery { serverRepository.testConnection(any()) } returns Result.success(true)
        val vm = createViewModel()

        val reachable = vm.awaitServerReachable("missing", timeoutMs = 80)

        assertFalse(reachable)
        assertFalse("missing" in vm.uiState.value.connectingServerIds)
    }
}
