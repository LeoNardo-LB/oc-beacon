package dev.leonardo.ocbeacon.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * #267（spec §3.3 检测滞后补刀）：共享 HttpClient Send 管线拦截——传输层
 * IOException 抛出时上拍请求 origin（scheme://host:port）；正常响应不上拍。
 */
class TransportFailureTapTest {

    @Test
    fun `reports origin when engine throws IOException`() = runTest {
        val tap = TransportFailureTap()
        val reported = mutableListOf<String>()
        tap.reportFailure = { reported += it }

        val client = HttpClient(MockEngine { _ ->
            // handler 内抛 IOException——传输层失败形态（按原样传播至 Send 管线）
            throw IOException("connect refused (simulated)")
        }).also { it.installTransportFailureTap(tap) }

        val outcome = runCatching { client.post("http://192.0.2.10:4199/api/session") { setBody("{}") } }

        // 诊断辅助：MockEngine handler 异常的实际传播形态（若非 IOException 直接失败可见）
        assertTrue(
            "expected IOException propagation but was: ${outcome.exceptionOrNull()?.javaClass}" +
                " reported=$reported",
            outcome.exceptionOrNull() is IOException,
        )
        assertEquals(listOf("http://192.0.2.10:4199"), reported)
    }

    @Test
    fun `no report on successful response`() = runTest {
        val tap = TransportFailureTap()
        val reported = mutableListOf<String>()
        tap.reportFailure = { reported += it }

        val client = HttpClient(MockEngine { _ ->
            respond("ok")
        }).also { it.installTransportFailureTap(tap) }

        client.get("http://192.0.2.10:4199/api/session")

        assertTrue(reported.isEmpty())
    }
}
