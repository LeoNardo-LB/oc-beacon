package dev.leonardo.octether.data.repository

import dev.leonardo.octether.domain.model.AutoApproveRule
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PermissionAutoApproverTest {

    @Test
    fun `AutoApproveRule serialization round-trip`() {
        val rule = AutoApproveRule(
            toolName = "bash",
            sessionId = "s1",
            directoryPattern = "/home/user"
        )
        val json = Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString<AutoApproveRule>(serialized)
        assertEquals(rule, deserialized)
    }

    @Test
    fun `AutoApproveRule with defaults serialization`() {
        val rule = AutoApproveRule(toolName = "*")
        val json = Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString<AutoApproveRule>(serialized)
        assertEquals(rule, deserialized)
        assertNull(deserialized.sessionId)
        assertEquals("*", deserialized.directoryPattern)
    }
}
