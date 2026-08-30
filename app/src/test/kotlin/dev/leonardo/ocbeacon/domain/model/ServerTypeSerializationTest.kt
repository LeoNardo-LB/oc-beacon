package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ServerType 维度序列化兼容测试（backlog #276 步骤①；设计文档 §2.1）。
 *
 * DataStore 旧 JSON（无 serverType 字段）必须零迁移反序列化为 OpenCode，
 * 往返（序列化→反序列化）保持稳定——@Serializable 全默认值是兼容前提。
 */
class ServerTypeSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy json without serverType deserializes to OpenCode`() {
        // #276 前的旧形态：无 serverType 字段（真实 DataStore 存量形态）
        val legacy = """
            [{"id":"s-1","url":"http://192.168.1.100:4096","username":"opencode",
              "apiVersion":"V1","fromDebugChannel":false}]
        """.trimIndent()
        val servers = json.decodeFromString<List<ServerConfig>>(legacy)
        assertEquals(1, servers.size)
        assertEquals(ServerType.OpenCode, servers[0].serverType)
    }

    @Test
    fun `dsh serverType roundtrips stably`() {
        val config = ServerConfig(
            id = "s-2",
            url = "http://127.0.0.1:3080",
            serverType = ServerType.Dsh,
            apiVersion = ApiVersion.V1,
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<ServerConfig>(encoded)
        assertEquals(ServerType.Dsh, decoded.serverType)
        assertEquals(config, decoded)
    }

    @Test
    fun `default construction is OpenCode`() {
        val config = ServerConfig(id = "s-3", url = "http://x")
        assertEquals(ServerType.OpenCode, config.serverType)
        val conn = ServerConnection(baseUrl = "http://x", authHeader = null)
        assertEquals(ServerType.OpenCode, conn.serverType)
    }

    @Test
    fun `ServerConnection from carries serverType with OpenCode default`() {
        val dsh = ServerConnection.from("http://127.0.0.1:3080/", "opencode", null, serverType = ServerType.Dsh)
        assertEquals(ServerType.Dsh, dsh.serverType)
        assertEquals("http://127.0.0.1:3080", dsh.baseUrl)
        // 缺省分支：既有调用方（UI/旧路径）不传 serverType → OpenCode 语义不变
        assertEquals(ServerType.OpenCode, ServerConnection.from("http://x").serverType)
    }

    @Test
    fun `ServerConnection from ServerConfig propagates serverType`() {
        val config = ServerConfig(id = "s-4", url = "http://127.0.0.1:3080", serverType = ServerType.Dsh)
        assertEquals(ServerType.Dsh, ServerConnection.from(config).serverType)
        assertEquals(
            ServerType.OpenCode,
            ServerConnection.from(ServerConfig(id = "s-5", url = "http://x")).serverType,
        )
    }
}
