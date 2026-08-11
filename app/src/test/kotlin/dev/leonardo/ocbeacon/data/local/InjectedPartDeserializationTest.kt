package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证注入的 tool part payload 能被项目 Json 配置反序列化。
 * （定位按钮测试的假数据——之前注入失败疑为反序列化问题）
 */
class InjectedPartDeserializationTest {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `injected subagent tool part deserializes`() {
        val payload = """
            {"id":"part_fake_host3","type":"tool","sessionID":"ses_0115b9cc8ffe9uQYuP9oUGhagI","messageID":"msg_fake_host3","callID":"part_fake_host3","tool":"subagent","state":{"status":"completed","input":{"description":"jobId 匹配测试","subagent_type":"explore"},"metadata":{"jobId":"ses_real_test_1"},"content":[]}}
        """.trimIndent()
        val part = json.decodeFromString<Part>(payload)
        assertTrue(part is Part.Tool)
        val tool = part as Part.Tool
        assertNotNull(tool.state)
        println("tool=${tool.tool} state=${tool.state::class.simpleName}")
    }
}
