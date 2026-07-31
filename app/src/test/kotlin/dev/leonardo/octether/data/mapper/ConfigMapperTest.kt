package dev.leonardo.octether.data.mapper

import dev.leonardo.octether.data.dto.response.ServerConfigResponse
import org.junit.Assert.*
import org.junit.Test

class ConfigMapperTest {

    @Test
    fun `toDisabledProviders extracts list`() {
        val response = ServerConfigResponse(
            disabledProviders = listOf("provider-a", "provider-b"),
            model = "gpt-4"
        )
        val result = ConfigMapper.toDisabledProviders(response)
        assertEquals(listOf("provider-a", "provider-b"), result)
    }

    @Test
    fun `toPatch builds correct patch`() {
        val patch = ConfigMapper.toPatch(
            disabledProviders = listOf("x"),
            model = "gpt-4",
            smallModel = "gpt-3.5",
            defaultAgent = "code"
        )
        assertEquals(listOf("x"), patch.disabledProviders)
        assertEquals("gpt-4", patch.model)
        assertEquals("gpt-3.5", patch.smallModel)
        assertEquals("code", patch.defaultAgent)
    }

    @Test
    fun `toPatch with nulls preserves defaults`() {
        val patch = ConfigMapper.toPatch()
        assertNull(patch.disabledProviders)
        assertNull(patch.model)
    }
}
