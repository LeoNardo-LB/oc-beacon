package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #91（2026-08-18）：listMessages 在途去重——同 (serverId, sessionId, limit, before)
 * 的并发调用共享同一在途请求（实测会话进入 22ms 内同 cursor 成对重复 8 次）。
 */
class SessionRepositoryImplDedupTest {

    private val messageApi = mockk<MessageApi>()
    private val serverStore = mockk<ServerDataStore>()
    private val repo = SessionRepositoryImpl(
        sessionApi = mockk<SessionApi>(relaxed = true),
        messageApi = messageApi,
        eventDispatcher = mockk(relaxed = true),
        serverRepo = serverStore,
    )

    private fun stubConfig() {
        coEvery { serverStore.getServer(any()) } returns ServerConfig(
            id = "srv", url = "http://localhost:4199", username = "u",
            password = "p", apiVersion = ApiVersion.V2,
        )
    }

    private fun pageOfSize(n: Int) = MessagePage(
        messages = List(n) {
            MessageWithParts(
                Message.User("m$it", "sid", time = dev.leonardo.ocbeacon.domain.model.TimeInfo(created = it.toLong())),
                emptyList(),
            )
        },
        nextCursor = null,
        previousCursor = null,
    )

    @Test
    fun `concurrent identical calls share single in-flight request`() = runTest {
        stubConfig()
        var calls = 0
        coEvery { messageApi.listMessages(any(), any(), any(), any()) } coAnswers {
            calls++
            delay(200)  // 模拟网络延迟，保证并发窗口重叠
            pageOfSize(30)
        }

        val results = (1..6).map {
            async { repo.listMessages("srv", "sid", 50, "cursor-A") }
        }.awaitAll()

        assertEquals("6 个并发同参调用只有 1 次实际请求", 1, calls)
        assertEquals("全部调用方拿到相同结果（30 条）", 6, results.count { it.getOrThrow().messages.size == 30 })
    }

    @Test
    fun `sequential calls after completion are not cached`() = runTest {
        stubConfig()
        coEvery { messageApi.listMessages(any(), any(), any(), any()) } returns pageOfSize(10)

        repo.listMessages("srv", "sid", 50, null).getOrThrow()
        repo.listMessages("srv", "sid", 50, null).getOrThrow()

        coVerify(exactly = 2) { messageApi.listMessages(any(), any(), 50, null) }
    }

    @Test
    fun `different cursors issue separate requests`() = runTest {
        stubConfig()
        coEvery { messageApi.listMessages(any(), any(), any(), any()) } coAnswers {
            delay(100)
            pageOfSize(5)
        }

        val r1 = async { repo.listMessages("srv", "sid", 30, "cursor-1") }
        val r2 = async { repo.listMessages("srv", "sid", 30, "cursor-2") }
        awaitAll(r1, r2)

        coVerify(exactly = 1) { messageApi.listMessages(any(), any(), 30, "cursor-1") }
        coVerify(exactly = 1) { messageApi.listMessages(any(), any(), 30, "cursor-2") }
    }
}
