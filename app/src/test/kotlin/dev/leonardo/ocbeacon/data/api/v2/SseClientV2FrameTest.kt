package dev.leonardo.ocbeacon.data.api.v2

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-18 回归（SSE 空闲 40s 断连循环）：readSseFrame 对纯注释帧
 * （服务器心跳 ": heartbeat" + 空行边界）必须返回空帧标记 ""——
 * 原实现在函数内 continue 吞掉永不返回，外层 withTimeoutOrNull(40s)
 * 看不到进展 → 空闲期每 40s 必超时断连（beta-17595 实测每 15s 一条心跳，
 * 断连→重连→recover 全量会话循环开销，且连续 5 次后进 5min 冷却）。
 */
class SseClientV2FrameTest {

    private val client = SseClientV2(
        json = kotlinx.serialization.json.Json,
        httpClient = io.ktor.client.HttpClient(),
    )

    @Test
    fun `comment heartbeat frame returns empty marker`() = runTest {
        // beta-17595 实测线格式：": heartbeat" + 空行
        val frame = client.readSseFrame(ByteReadChannel(": heartbeat\n\n"))
        assertEquals("纯注释帧返回空标记（外层刷新存活计时）", "", frame)
    }

    @Test
    fun `data frame returns payload normally`() = runTest {
        val frame = client.readSseFrame(
            ByteReadChannel("data: {\"type\":\"server.connected\"}\n\n")
        )
        assertTrue(frame!!.contains("server.connected"))
    }

    @Test
    fun `comment line inside data frame does not corrupt payload`() = runTest {
        // 注释行混入数据帧（非标但防御）：data 帧正常返回
        val frame = client.readSseFrame(
            ByteReadChannel(": keep-alive\ndata: {\"type\":\"x\"}\n\n")
        )
        assertTrue(frame!!.contains("\"type\":\"x\""))
    }

    @Test
    fun `event and data frame uses v1 compatible separator`() = runTest {
        val frame = client.readSseFrame(
            ByteReadChannel("event: foo\ndata: {}\n\n")
        )
        // event 类型 + \u0000 + data（parseV2Event 兼容路径）
        assertTrue(frame!!.startsWith("foo\u0000"))
    }

    @Test
    fun `stream end without content returns empty marker`() = runTest {
        // EOF 无帧内容 → ""（既有语义：外层 while(!isClosedForRead) 循环条件兜底退出，
        // 与 null 等效；null 保留给「帧中途流断」场景——readRawLineBytes 返回 null）
        val frame = client.readSseFrame(ByteReadChannel(""))
        assertEquals("", frame)
    }
}
