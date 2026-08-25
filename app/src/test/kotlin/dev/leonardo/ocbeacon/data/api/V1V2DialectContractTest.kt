package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.session.SessionApiImpl
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1/V2 方言路由契约（2026-08-26 架构走查 C1 批次）：同 fixture 参数下，
 * 域 Impl 必须按 conn.apiVersion 把调用单点路由到对应 client（pick），
 * 不允许串台（V1 连接的调用落 V2 或反之）。
 *
 * 背景：SessionApiImpl/MessageApiImpl 原 35 处逐方法 if (isV2) 分发收缩为
 * pick(conn) + 委托后，路由正确性由此测试守护。
 */
class V1V2DialectContractTest {

    private val v1 = mockk<V1ApiClient>()
    private val v2 = mockk<V2ApiClient>()

    private val connV1 = ServerConnection("http://srv", null, ApiVersion.V1)
    private val connV2 = ServerConnection("http://srv", null, ApiVersion.V2)

    // ---------- Session ----------

    @Test
    fun `session - V2 conn routes listSessions to v2 only`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v2.listSessions(connV2, null, null, null, 50) } returns emptyList()

        api.listSessions(connV2)

        coVerify(exactly = 1) { v2.listSessions(connV2, null, null, null, 50) }
        coVerify(exactly = 0) { v1.listSessions(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn routes listSessions to v1 only`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v1.listSessions(connV1, null, null, null, 50) } returns emptyList()

        api.listSessions(connV1)

        coVerify(exactly = 1) { v1.listSessions(connV1, null, null, null, 50) }
        coVerify(exactly = 0) { v2.listSessions(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn routes interruptSession to v1 only`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v1.interruptSession(connV1, "ses_1", null) } returns true

        assertTrue(api.interruptSession(connV1, "ses_1"))

        coVerify(exactly = 1) { v1.interruptSession(connV1, "ses_1", null) }
        coVerify(exactly = 0) { v2.interruptSession(any(), any(), any()) }
    }

    @Test
    fun `session - V1 conn degrades backgroundSession inside v1 client`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v1.backgroundSession(connV1, "ses_1") } returns false

        assertFalse(api.backgroundSession(connV1, "ses_1"))

        coVerify(exactly = 1) { v1.backgroundSession(connV1, "ses_1") }
        coVerify(exactly = 0) { v2.backgroundSession(any(), any()) }
    }

    @Test
    fun `session - V2 conn routes backgroundSession and activeSessions to v2`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v2.backgroundSession(connV2, "ses_1") } returns true
        coEvery { v2.activeSessions(connV2) } returns emptyMap()

        assertTrue(api.backgroundSession(connV2, "ses_1"))
        assertTrue(api.activeSessions(connV2).isEmpty())

        coVerify(exactly = 1) { v2.backgroundSession(connV2, "ses_1") }
        coVerify(exactly = 1) { v2.activeSessions(connV2) }
        coVerify(exactly = 0) { v1.backgroundSession(any(), any()) }
        coVerify(exactly = 0) { v1.activeSessions(any()) }
    }

    @Test
    fun `session - fetchSessionStatus routes by conn and keeps C8 error taxonomy`() = runTest {
        val api = SessionApiImpl(v1, v2)
        coEvery { v1.fetchSessionStatus(connV1, null) } returns Result.success(emptyMap())
        coEvery { v2.fetchSessionStatus(connV2, null) } returns Result.success(emptyMap())

        api.fetchSessionStatus(connV1)
        api.fetchSessionStatus(connV2)

        coVerify(exactly = 1) { v1.fetchSessionStatus(connV1, null) }
        coVerify(exactly = 1) { v2.fetchSessionStatus(connV2, null) }
        coVerify(exactly = 0) { v1.fetchSessionStatus(connV2, any()) }
        coVerify(exactly = 0) { v2.fetchSessionStatus(connV1, any()) }
    }
}
