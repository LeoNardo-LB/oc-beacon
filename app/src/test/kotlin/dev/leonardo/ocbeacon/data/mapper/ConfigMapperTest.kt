package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
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
    fun `toDtoPatch builds correct patch`() {
        val patch = ConfigMapper.toDtoPatch(
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
    fun `toDtoPatch with nulls preserves defaults`() {
        val patch = ConfigMapper.toDtoPatch()
        assertNull(patch.disabledProviders)
        assertNull(patch.model)
    }

    @Test
    fun `toDomain converts ServerConfigResponse to GlobalConfig`() {
        val response = ServerConfigResponse(
            disabledProviders = listOf("a", "b"),
            model = "gpt-4",
            smallModel = "gpt-3.5",
            defaultAgent = "code"
        )
        val config = ConfigMapper.toDomain(response)
        assertEquals(listOf("a", "b"), config.disabledProviders)
        assertEquals("gpt-4", config.model)
        assertEquals("gpt-3.5", config.smallModel)
        assertEquals("code", config.defaultAgent)
    }

    @Test
    fun `toDto converts GlobalConfigPatch to ServerConfigPatch`() {
        val patch = GlobalConfigPatch(
            disabledProviders = listOf("x"),
            model = "claude",
            smallModel = null,
            defaultAgent = "build"
        )
        val dto = ConfigMapper.toDto(patch)
        assertEquals(listOf("x"), dto.disabledProviders)
        assertEquals("claude", dto.model)
        assertNull(dto.smallModel)
        assertEquals("build", dto.defaultAgent)
    }
}
