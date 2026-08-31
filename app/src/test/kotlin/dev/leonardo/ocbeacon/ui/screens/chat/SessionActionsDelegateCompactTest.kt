package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageTerminalUseCase
import dev.leonardo.ocbeacon.domain.usecase.ShareExportUseCase
import dev.leonardo.ocbeacon.domain.usecase.UndoRedoUseCase
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #276 终验 V5：压缩「no model selected」护栏按能力位旁路。
 *
 * DSH /compact 走斜杠命令通道与模型无关（compactionModelIndependent=true）——
 * 无模型选择也必须发出 RPC（此前零 RPC 被客户端护栏拦截）。OpenCode V1/V2
 * 的 summarize/compact 端点带 providerID/modelID——护栏维持原拦截。
 */
class SessionActionsDelegateCompactTest {

    private fun buildDelegate(
        testScope: TestScope,
        shareExport: ShareExportUseCase,
        compactionModelIndependent: Boolean,
    ): SessionActionsDelegate = SessionActionsDelegate(
        shareExportUseCase = shareExport,
        undoRedoUseCase = mockk(relaxed = true),
        manageSessionUseCase = mockk(relaxed = true),
        managePermissionUseCase = mockk(relaxed = true),
        manageTerminalUseCase = mockk(relaxed = true),
        sessionRepository = mockk(relaxed = true),
        chatRepository = mockk(relaxed = true),
        sessionStateRepository = mockk(relaxed = true),
        serverId = "srv-1",
        scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler)),
        sessionIdProvider = { "sess-1" },
        sessionDirectoryProvider = { "/home/x" },
        modelConfigProvider = { ModelConfigState() }, // 无模型选择
        messageListProvider = { emptyList() },
        ensureSession = { "sess-1" },
        loadSessionInfo = {},
        awaitSessionLoaded = {},
        refreshMessages = {},
        loadPendingQuestions = {},
        loadPendingPermissions = {},
        restoreRevertedDraft = { },
        compactionAsyncProvider = { true },
        compactionModelIndependentProvider = { compactionModelIndependent },
    )

    /** DSH：无模型选择仍发出压缩调用（护栏旁路），onResult(true)。 */
    @Test
    fun `compactSession bypasses model guard when model independent`() = runTest {
        val shareExport = mockk<ShareExportUseCase>(relaxed = true)
        val delegate = buildDelegate(this, shareExport, compactionModelIndependent = true)
        var ok = false
        delegate.compactSession { ok = it }
        advanceUntilIdle()
        assertTrue(ok)
        coVerify(exactly = 1) { shareExport.compactSession("srv-1", "sess-1", any(), any()) }
    }

    /** OpenCode：无模型选择维持原护栏——零 RPC，onResult(false)。 */
    @Test
    fun `compactSession blocks without model when model dependent`() = runTest {
        val shareExport = mockk<ShareExportUseCase>(relaxed = true)
        val delegate = buildDelegate(this, shareExport, compactionModelIndependent = false)
        var ok = true
        delegate.compactSession { ok = it }
        advanceUntilIdle()
        assertFalse(ok)
        coVerify(exactly = 0) { shareExport.compactSession(any(), any(), any(), any()) }
    }
}
