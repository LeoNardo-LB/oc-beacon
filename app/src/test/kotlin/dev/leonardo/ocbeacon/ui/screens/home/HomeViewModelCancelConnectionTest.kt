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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归测试：连接进行中（健康检查阶段）点击取消必须立即生效。
 *
 * 旧行为：connectToServer 乐观添加 connectingServerIds 后，在
 * testConnection 期间点取消只移除 connectedServerIds（本就不包含），
 * connectingServerIds 残留 → UI 一直显示 Connecting，直到健康检查
 * 超时失败才复位（"Server is not responding"）。且取消后健康检查
 * 协程仍会继续，若检查通过会再次启动服务连接。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCancelConnectionTest {

    private val application: Application = mockk(relaxed = true)
    private val serverRepository: ServerRepository = mockk()
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase = mockk()
    private val updateSettingsUseCase: UpdateSettingsUseCase = mockk(relaxed = true)
    private val manageServerProvidersUseCase: ManageServerProvidersUseCase = mockk(relaxed = true)

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
        )
    }

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
    fun cancelDuringHealthCheck_removesConnectingStateImmediately() = runTest {
        val healthCheck = CompletableDeferred<Result<Boolean>>()
        coEvery { serverRepository.testConnection(any()) } coAnswers { healthCheck.await() }
        val vm = createViewModel()

        vm.connectToServer("s1")
        assertTrue("s1 should be connecting after connect", "s1" in vm.uiState.value.connectingServerIds)

        // 健康检查尚未完成时点击取消 —— 状态必须立即复位
        vm.disconnectFromServer("s1")
        assertFalse("connecting state must clear immediately on cancel", "s1" in vm.uiState.value.connectingServerIds)

        // 健康检查最终失败 —— 不得再产生任何状态变化（无错误提示）
        healthCheck.complete(Result.success(false))
        advanceUntilIdle()
        assertFalse("s1" in vm.uiState.value.connectingServerIds)
        assertTrue("cancelled connection must not surface an error", vm.uiState.value.connectionErrors.isEmpty())
    }

    @Test
    fun cancelDuringHealthCheck_doesNotConnectEvenIfHealthPasses() = runTest {
        val healthCheck = CompletableDeferred<Result<Boolean>>()
        coEvery { serverRepository.testConnection(any()) } coAnswers { healthCheck.await() }
        val vm = createViewModel()

        vm.connectToServer("s1")
        vm.disconnectFromServer("s1")

        // 健康检查通过 —— 取消后也必须保持未连接、无错误
        healthCheck.complete(Result.success(true))
        advanceUntilIdle()
        assertFalse("s1" in vm.uiState.value.connectingServerIds)
        assertFalse("s1" in vm.uiState.value.connectedServerIds)
        assertTrue(vm.uiState.value.connectionErrors.isEmpty())
    }
}
