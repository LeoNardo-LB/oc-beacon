package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageTerminalUseCase
import dev.leonardo.ocbeacon.domain.usecase.ShareExportUseCase
import dev.leonardo.ocbeacon.domain.usecase.UndoRedoUseCase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #331：fork 回执即插行——forkSession 成功后把回执 Session 行注入仓库
 *（setSessions 合并语义），目录/标题继承父会话。根因：DSH 服务器虽广播
 * api-session/added（API_REMOTE_FORWARDED_EVENTS emit 白名单），但 app 旧
 * 实现 added 帧 time.updated=epoch0 → 行沉列表底部；fork 回执则从未插行——
 * 列表只能等下一次 session.list 基线（观测 ~3min）才见新行。
 */
class SessionActionsDelegateFork331Test {

    private val parentRow = Session(
        id = "s-parent",
        directory = "/home/x/proj",
        title = "parent title",
        time = Session.Time(created = 1L, updated = 2L),
    )

    private fun buildDelegate(
        testScope: TestScope,
        manageSessionUseCase: ManageSessionUseCase,
        sessionRepository: SessionRepository,
        chatRepository: ChatRepository,
    ): SessionActionsDelegate = SessionActionsDelegate(
        shareExportUseCase = mockk(relaxed = true),
        undoRedoUseCase = mockk(relaxed = true),
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = mockk(relaxed = true),
        manageTerminalUseCase = mockk(relaxed = true),
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
        sessionStateRepository = mockk(relaxed = true),
        serverId = "srv-1",
        scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler)),
        sessionIdProvider = { "s-parent" },
        sessionDirectoryProvider = { "/home/x/proj" },
        modelConfigProvider = { ModelConfigState() },
        messageListProvider = { emptyList() },
        ensureSession = { "s-parent" },
        loadSessionInfo = {},
        awaitSessionLoaded = {},
        refreshMessages = {},
        loadPendingQuestions = {},
        loadPendingPermissions = {},
        restoreRevertedDraft = { },
        compactionAsyncProvider = { true },
    )

    /** fork 成功 → 回执行注入仓库（目录/标题继承父行，updated>0 不沉底）。 */
    @Test
    fun `forkSession inserts receipt row into repository`() = runTest {
        val childReceipt = Session(
            id = "s-child",
            time = Session.Time(created = 0L, updated = 42L),
        )
        val manageSession = mockk<ManageSessionUseCase>()
        coEvery { manageSession.forkSession("srv-1", "s-parent", null) } returns childReceipt
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        coEvery { chatRepository.getSessionsSnapshot() } returns listOf(parentRow)
        val delegate = buildDelegate(this, manageSession, sessionRepository, chatRepository)

        var result: Session? = null
        delegate.forkSession(null) { result = it }
        advanceUntilIdle()

        assertEquals("s-child", result?.id)
        val inserted = slot<List<Session>>()
        io.mockk.verify { sessionRepository.setSessions("srv-1", capture(inserted)) }
        val row = inserted.captured.single()
        assertEquals("s-child", row.id)
        assertEquals("/home/x/proj", row.directory)
        assertEquals("parent title", row.title)
        assertTrue(row.time.updated > 0L)
    }

    /** fork 失败 → 不注入，onResult(null) 照常。 */
    @Test
    fun `forkSession failure inserts nothing`() = runTest {
        val manageSession = mockk<ManageSessionUseCase>()
        coEvery { manageSession.forkSession("srv-1", "s-parent", any()) } throws IllegalStateException("boom")
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        coEvery { chatRepository.getSessionsSnapshot() } returns listOf(parentRow)
        val delegate = buildDelegate(this, manageSession, sessionRepository, chatRepository)

        var result: Session? = childPlaceholder()
        delegate.forkSession(null) { result = it }
        advanceUntilIdle()

        assertEquals(null, result)
        io.mockk.verify(exactly = 0) { sessionRepository.setSessions(any(), any()) }
    }

    private fun childPlaceholder(): Session? = null
}