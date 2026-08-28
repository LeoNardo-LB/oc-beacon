package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import javax.inject.Provider

/**
 * #122（2026-08-18 接线）：PermissionAutoApprover 此前全库零调用——用户保存的
 * 自动批准规则从未生效。验证 EventDispatcher 的 PermissionAsked 分发路径正确
 * 消费规则（匹配 → respondPermission；无规则 → 不回复；目录不匹配 → 不回复）。
 */
class PermissionAutoApproveWiringTest {

    private val sessionHandler = SessionEventHandler()
    private val messageHandler = MessageEventHandler()
    private val chatRepo = mockk<ChatRepository>(relaxed = true)

    private fun newDispatcher(rules: Set<AutoApproveRule>): EventDispatcher {
        val tmpFile = File.createTempFile("autoapprove", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
        ) { tmpFile }
        // C7：自动批准编排（目录解析 + respondPermission）迁入 PermissionAutoApprover——
        // 真 sessionHandler + 真 chatRepo provider 驱动完整链路；scope 用真 IO（生产语义）
        val approver = PermissionAutoApprover(
            dataStore = dataStore,
            appScope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob()),
            sessionHandler = sessionHandler,
            chatRepoProvider = Provider { chatRepo },
        )
        kotlinx.coroutines.runBlocking { rules.forEach { approver.addRule(it) } }
        val unreadStateStore = mockk<UnreadStateStore>(relaxed = true)
        return EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageHandler),
            sessionStateRepository = mockk(relaxed = true),
            unreadBadgeService = UnreadBadgeService(unreadStateStore, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
            ownershipRegistry = StreamingOwnershipRegistry(),
            permissionAutoApprover = approver,
            // 堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessagePipelineProvider = Provider { mockk<PendingMessagePipeline>(relaxed = true) },
        )
    }

    private val asked = SseEvent.PermissionAsked(
        id = "perm-1",
        sessionId = "ses-1",
        permission = "bash",
    )

    /** 预置会话（directory 供规则匹配）。 */
    private fun seedSession(directory: String) {
        sessionHandler.handle(
            SseEvent.SessionCreated(
                Session(id = "ses-1", directory = directory, time = Session.Time(created = 0L, updated = 0L))
            ),
            "srv",
        )
    }

    @Test
    fun `matched rule auto-approves permission`() = runTest(StandardTestDispatcher()) {
        seedSession("/home/proj")
        val dispatcher = newDispatcher(setOf(AutoApproveRule(toolName = "bash")))
        coEvery { chatRepo.respondPermission(any(), any(), any(), any(), any()) } returns kotlin.Result.success(true)

        dispatcher.processEvent(asked, "srv")
        advanceUntilIdle()

        // approver appScope 用真 Dispatchers.IO（生产语义，C7 前的 autoApproveScope
        // 同款）——虚拟时钟等不到，coVerify timeout 真实等待异步回复落地
        coVerify(timeout = 5_000L, exactly = 1) {
            chatRepo.respondPermission("srv", "ses-1", "perm-1", "once", "/home/proj")
        }
    }

    @Test
    fun `no rules means no auto reply`() = runTest(StandardTestDispatcher()) {
        seedSession("/home/proj")
        val dispatcher = newDispatcher(emptySet())

        dispatcher.processEvent(asked, "srv")
        advanceUntilIdle()
        // 负向断言给真实等待窗口（否则异步分支未跑完就验证 = 假阳性）
        Thread.sleep(300)

        coVerify(exactly = 0) { chatRepo.respondPermission(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `directory-scoped rule does not match other directory`() = runTest(StandardTestDispatcher()) {
        seedSession("/other")
        val dispatcher = newDispatcher(setOf(AutoApproveRule(toolName = "bash", directoryPattern = "/home/proj")))

        dispatcher.processEvent(asked, "srv")
        advanceUntilIdle()
        Thread.sleep(300)

        coVerify(exactly = 0) { chatRepo.respondPermission(any(), any(), any(), any(), any()) }
    }
}
