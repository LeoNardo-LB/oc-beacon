package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageTerminalUseCase
import dev.leonardo.ocbeacon.domain.usecase.ShareExportUseCase
import dev.leonardo.ocbeacon.domain.usecase.UndoRedoUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #250 回归：新会话首发 shell 时 sessionId 尚未就位（空串）——
 * runShellCommand 必须先走 ensureSession（与 ChatSendDelegate/executeCommand
 * 同款模式），以就位 id 发送，而非直读当前值。
 * 真机取证：POST /api/session//shell 空 id →「Shell 命令运行失败」。
 */
class SessionActionsDelegateShellTest {

    private fun buildDelegate(
        testScope: TestScope,
        currentSessionId: String,
        ensuredSessionId: String,
        terminal: ManageTerminalUseCase,
        shellCommandSupported: Boolean = true,
    ): SessionActionsDelegate = SessionActionsDelegate(
        shareExportUseCase = mockk(relaxed = true),
        undoRedoUseCase = mockk(relaxed = true),
        manageSessionUseCase = mockk(relaxed = true),
        managePermissionUseCase = mockk(relaxed = true),
        manageTerminalUseCase = terminal,
        sessionRepository = mockk(relaxed = true),
        chatRepository = mockk(relaxed = true),
        sessionStateRepository = mockk(relaxed = true),
        serverId = "srv-1",
        scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler)),
        sessionIdProvider = { currentSessionId },
        sessionDirectoryProvider = { "/home/x" },
        modelConfigProvider = { ModelConfigState() },
        messageListProvider = { emptyList() },
        ensureSession = { ensuredSessionId },
        loadSessionInfo = {},
        awaitSessionLoaded = {},
        refreshMessages = {},
        loadPendingQuestions = {},
        loadPendingPermissions = {},
        restoreRevertedDraft = { },
        compactionAsyncProvider = { false },
        shellCommandSupportedProvider = { shellCommandSupported },
    )

    /** 回归 #250：空 sessionId（新会话未就位）时必须以 ensureSession 的就位 id 发送。 */
    @Test
    fun `runShellCommand ensures session when id blank`() = runTest {
        val terminal: ManageTerminalUseCase = mockk(relaxed = true)
        val idSlot = slot<String>()
        coEvery { terminal.runShellCommand(any(), capture(idSlot), any(), any(), any(), any()) } returns true
        val delegate = buildDelegate(this, currentSessionId = "", ensuredSessionId = "sess-new", terminal = terminal)
        var ok = false
        delegate.runShellCommand("pwd") { ok = it }
        advanceUntilIdle()
        assertTrue(ok)
        assertEquals("sess-new", idSlot.captured)
        coVerify(exactly = 1) { terminal.runShellCommand(any(), any(), any(), any(), any(), any()) }
    }

    /** 已有会话：ensureSession 幂等返回现 id，shell 仍以其发送。 */
    @Test
    fun `runShellCommand uses existing session id`() = runTest {
        val terminal: ManageTerminalUseCase = mockk(relaxed = true)
        val idSlot = slot<String>()
        coEvery { terminal.runShellCommand(any(), capture(idSlot), any(), any(), any(), any()) } returns true
        val delegate = buildDelegate(this, currentSessionId = "sess-cur", ensuredSessionId = "sess-cur", terminal = terminal)
        var ok = false
        delegate.runShellCommand("pwd") { ok = it }
        advanceUntilIdle()
        assertTrue(ok)
        assertEquals("sess-cur", idSlot.captured)
    }

    /** 空白命令直接拒绝，不触发 ensureSession/网络。 */
    @Test
    fun `runShellCommand rejects blank command without network`() = runTest {
        val terminal: ManageTerminalUseCase = mockk(relaxed = true)
        val delegate = buildDelegate(this, currentSessionId = "", ensuredSessionId = "sess-new", terminal = terminal)
        var ok = true
        delegate.runShellCommand("   ") { ok = it }
        advanceUntilIdle()
        assertTrue(!ok)
        coVerify(exactly = 0) { terminal.runShellCommand(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * #276 后端接口补全：能力位短路——DSH 无 shell 域（shellCommandSupported=
     * false）时 runShellCommand 直接 onResult(false)，不 ensureSession、不发
     * 网络请求（DshApiClient 该方法抛 UnsupportedServerCapability，UI 入口已
     * 按能力位隐藏，此处为残留路径兜底——如 isShellMode 态残留时的发送）。
     */
    @Test
    fun `runShellCommand short-circuits when capability off`() = runTest {
        val terminal: ManageTerminalUseCase = mockk(relaxed = true)
        val delegate = buildDelegate(
            this, currentSessionId = "sess-cur", ensuredSessionId = "sess-cur",
            terminal = terminal, shellCommandSupported = false,
        )
        var ok = true
        delegate.runShellCommand("pwd") { ok = it }
        advanceUntilIdle()
        assertTrue(!ok)
        coVerify(exactly = 0) { terminal.runShellCommand(any(), any(), any(), any(), any(), any()) }
    }
}