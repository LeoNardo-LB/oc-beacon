package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #395：steer 插话标记随消息序列化/持久化（REST 重建时由合并保留）。 */
class MessageViaSteerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `viaSteer 随消息序列化往返`() {
        val user = Message.User(id = "u1", sessionId = "s", time = TimeInfo(0L), viaSteer = true)
        val encoded = json.encodeToString(Message.User.serializer(), user)
        assertTrue(encoded.contains("viaSteer"))
        assertTrue(json.decodeFromString(Message.User.serializer(), encoded).viaSteer)
    }

    @Test
    fun `服务端载荷缺席 viaSteer 时默认 false`() {
        val raw = """{"id":"u2","sessionID":"s","role":"user","time":{"created":0}}"""
        assertFalse(json.decodeFromString(Message.User.serializer(), raw).viaSteer)
    }

    @Test
    fun `copy 保留 viaSteer`() {
        val user = Message.User(id = "u1", sessionId = "s", time = TimeInfo(0L), viaSteer = true)
        assertTrue(user.copy(summary = null).viaSteer)
    }
}
