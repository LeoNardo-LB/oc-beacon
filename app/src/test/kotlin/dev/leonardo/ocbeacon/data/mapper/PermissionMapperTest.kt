package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.PermissionRequest
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PermissionMapperTest {

    @Test
    fun `toDomain maps all fields correctly`() {
        val dto = PermissionRequest(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home"),
            metadata = mapOf("key" to JsonPrimitive("value")),
            always = JsonArray(listOf(JsonPrimitive("*"))),
            tool = ToolRef(messageId = "m1", callId = "c1")
        )

        val domain = PermissionMapper.toDomain(dto)

        assertEquals("p1", domain.id)
        assertEquals("s1", domain.sessionId)
        assertEquals("bash", domain.permission)
        assertEquals(listOf("/home"), domain.patterns)
        assertEquals(mapOf("key" to "value"), domain.metadata)
        assertTrue(domain.always)
        assertEquals(ToolRef("m1", "c1"), domain.tool)
    }

    @Test
    fun `toDomain maps empty always to false`() {
        val dto = PermissionRequest(id = "p1", sessionId = "s1", permission = "read")

        val domain = PermissionMapper.toDomain(dto)

        assertFalse(domain.always)
    }

    @Test
    fun `toDto maps all fields correctly`() {
        val domain = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home"),
            metadata = mapOf("key" to "value"),
            always = true,
            tool = ToolRef(messageId = "m1", callId = "c1")
        )

        val dto = PermissionMapper.toDto(domain)

        assertEquals("p1", dto.id)
        assertEquals("s1", dto.sessionId)
        assertEquals("bash", dto.permission)
        assertEquals(listOf("/home"), dto.patterns)
        assertNotNull(dto.metadata)
        assertEquals("value", dto.metadata!!["key"]!!.jsonPrimitive.content)
        assertTrue(dto.always != null)
        assertEquals(ToolRef("m1", "c1"), dto.tool)
    }

    @Test
    fun `round-trip toDomain then toDto preserves semantic meaning`() {
        val original = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "write",
            patterns = listOf("/home/user/project"),
            metadata = mapOf("file" to "test.kt"),
            always = false,
            tool = null
        )

        val dto = PermissionMapper.toDto(original)
        val roundTripped = PermissionMapper.toDomain(dto)

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.sessionId, roundTripped.sessionId)
        assertEquals(original.permission, roundTripped.permission)
        assertEquals(original.patterns, roundTripped.patterns)
        assertEquals(original.metadata, roundTripped.metadata)
        assertEquals(original.always, roundTripped.always)
        assertEquals(original.tool, roundTripped.tool)
    }
}
