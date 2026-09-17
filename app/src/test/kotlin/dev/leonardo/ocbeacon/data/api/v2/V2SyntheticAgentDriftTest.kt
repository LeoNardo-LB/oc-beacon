package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (2026-09-12 消息层扁平化 (f))：清理 Message.User.agent 漂移。
 *
 * 服务器 synthetic 载荷的 metadata.agent 是「子智能体类型」，此前被灌进
 * Message.User.agent；该字段全链零消费者（通知卡来源由文本标签解析），
 * 且会污染 ModelConfigDelegate 的 agent 回填。本测试锁定不再写入。
 */
class V2SyntheticAgentDriftTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `synthetic message does not carry subagent type in user agent`() {
        val obj = json.parseToJsonElement(
            """{"type":"synthetic","id":"msg_syn1","time":{"created":1000},
               "text":"Generated text",
               "metadata":{"source":"subagent","childID":"ses_x","agent":"Explore","state":"completed"}}""",
        ).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val user = result.info as Message.User
        assertNull(user.agent)
    }
}
