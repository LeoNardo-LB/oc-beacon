package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #395：steer 插话标记是纯发送路径字段（@Transient），不落序列化/缓存。 */
class MessageViaSteerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `viaSteer 不进入序列化产物`() {
        val user = Message.User(id = "u1", sessionId = "s", time = TimeInfo(0L), viaSteer = true)
        val encoded = json.encodeToString(Message.User.serializer(), user)
        assertFalse(encoded.contains("viaSteer"))
        assertFalse(json.decodeFromString(Message.User.serializer(), encoded).viaSteer)
    }

    @Test
    fun `copy 保留 viaSteer`() {
        val user = Message.User(id = "u1", sessionId = "s", time = TimeInfo(0L), viaSteer = true)
        assertTrue(user.copy(summary = null).viaSteer)
    }
}
