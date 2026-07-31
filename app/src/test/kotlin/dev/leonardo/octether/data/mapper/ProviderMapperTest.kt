package dev.leonardo.octether.data.mapper

import dev.leonardo.octether.data.dto.response.*
import org.junit.Assert.*
import org.junit.Test

class ProviderMapperTest {

    @Test
    fun `toProviderNameMap creates id-name mapping`() {
        val response = ProvidersResponse(
            providers = listOf(
                ProviderInfo(id = "openai", name = "OpenAI"),
                ProviderInfo(id = "anthropic", name = "Anthropic")
            )
        )
        val map = ProviderMapper.toProviderNameMap(response)
        assertEquals(mapOf("openai" to "OpenAI", "anthropic" to "Anthropic"), map)
    }

    @Test
    fun `toConnectedProviderIds extracts connected set`() {
        val response = ProviderCatalogResponse(
            all = emptyList(),
            connected = listOf("openai", "anthropic")
        )
        val ids = ProviderMapper.toConnectedProviderIds(response)
        assertEquals(setOf("openai", "anthropic"), ids)
    }

    @Test
    fun `toModelSelection creates correct selection`() {
        val sel = ProviderMapper.toModelSelection("openai", "gpt-4")
        assertEquals("openai", sel.providerId)
        assertEquals("gpt-4", sel.modelId)
    }
}
