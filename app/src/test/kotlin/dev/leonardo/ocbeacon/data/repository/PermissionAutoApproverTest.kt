package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PermissionAutoApproverTest {

    @Test
    fun `AutoApproveRule serialization round-trip`() {
        // 固定 createdAt：System.currentTimeMillis() 默认值非确定性，
        // 配合默认 encodeDefaults=false 会偶发省略该字段导致 round-trip 失败。
        val rule = AutoApproveRule(
            toolName = "bash",
            sessionId = "s1",
            directoryPattern = "/home/user",
            createdAt = 1_700_000_000_000L
        )
        val json = Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString<AutoApproveRule>(serialized)
        assertEquals(rule, deserialized)
    }

    @Test
    fun `AutoApproveRule with defaults serialization`() {
        val rule = AutoApproveRule(toolName = "*", createdAt = 1_700_000_000_000L)
        val json = Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString<AutoApproveRule>(serialized)
        assertEquals(rule, deserialized)
        assertNull(deserialized.sessionId)
        assertEquals("*", deserialized.directoryPattern)
    }
}
