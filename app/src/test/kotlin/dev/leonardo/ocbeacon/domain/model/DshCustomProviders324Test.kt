package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324① 自定义 provider 纯逻辑测试：route 校验 / 凭据 ref 派生 / profile JSON
 * 形状 / 模型条目映射 / 目录合流。
 *
 * 契约锚点：web dsh-client-ui-settings-models（mod18）CustomProviderCard——
 * NS="llm-pi-ai"、path=["providers",route]、profile={displayName?,apiKeyEnv?,api,baseURL,models}、
 * keyRef=route 大写非字母数字段→"_"+_API_KEY、ROUTE_PATTERN=^[a-z][a-z0-9]*(-[a-z0-9]+)*$；
 * 协议表（dsh-llm-pi-ai PROTOCOLS 序）：openai-completions/openai-responses/anthropic-messages。
 */
class DshCustomProviders324Test {

    // ---- route 校验 ----

    @Test
    fun `route pattern accepts lowercase stem with hyphen groups`() {
        assertTrue(DshCustomProviders.isValidRoute("acme"))
        assertTrue(DshCustomProviders.isValidRoute("acme-gateway"))
        assertTrue(DshCustomProviders.isValidRoute("a1-b2"))
        assertFalse(DshCustomProviders.isValidRoute(""))
        assertFalse(DshCustomProviders.isValidRoute("Acme"))
        assertFalse(DshCustomProviders.isValidRoute("1acme"))
        assertFalse(DshCustomProviders.isValidRoute("acme--gw"))
        assertFalse(DshCustomProviders.isValidRoute("acme-"))
        assertFalse(DshCustomProviders.isValidRoute("acme_gw"))
    }

    // ---- 凭据 ref 派生 ----

    @Test
    fun `credential ref uppercases and joins non-alphanumerics then appends api key suffix`() {
        assertEquals("ACME_API_KEY", DshCustomProviders.deriveCredentialRef("acme"))
        assertEquals("ACME_GATEWAY_API_KEY", DshCustomProviders.deriveCredentialRef("acme-gateway"))
        assertEquals("A1_B2_API_KEY", DshCustomProviders.deriveCredentialRef("a1-b2"))
    }

    // ---- 协议表 ----

    @Test
    fun `protocol order is openai completions first as default`() {
        assertEquals(
            listOf("openai-completions", "openai-responses", "anthropic-messages"),
            DshCustomProviders.PROTOCOLS,
        )
    }

    // ---- profile JSON ----

    @Test
    fun `profile json includes api baseURL models and optional displayName apiKeyEnv`() {
        val draft = DshCustomProviderDraft(
            route = "acme-gateway",
            displayName = "Acme",
            baseURL = "https://api.acme.dev/v1",
            api = "openai-completions",
            apiKey = "sk-secret",
            models = listOf(
                DshDiscoveredModel(id = "acme-large", name = "Acme Large", contextWindow = 128000, maxTokens = 8192),
                DshDiscoveredModel(id = "acme-small"),
            ),
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(DshCustomProviders.profileJson(draft).toString()).jsonObject
        assertEquals("Acme", (obj["displayName"] as JsonPrimitive).content)
        assertEquals("ACME_GATEWAY_API_KEY", (obj["apiKeyEnv"] as JsonPrimitive).content)
        assertEquals("openai-completions", (obj["api"] as JsonPrimitive).content)
        assertEquals("https://api.acme.dev/v1", (obj["baseURL"] as JsonPrimitive).content)
        val models = obj["models"]!! as kotlinx.serialization.json.JsonArray
        assertEquals(2, models.size)
        val first = models[0].jsonObject
        assertEquals("acme-large", (first["id"] as JsonPrimitive).content)
        assertEquals("Acme Large", (first["name"] as JsonPrimitive).content)
        assertEquals(128000.0, (first["contextWindow"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(8192.0, (first["maxTokens"] as JsonPrimitive).content.toDouble(), 0.0)
        // 未提供的可选字段缺席（服务端 schema 按缺省继承）
        val second = models[1].jsonObject
        assertNull(second["name"])
        assertNull(second["contextWindow"])
        assertNull(second["maxTokens"])
    }

    @Test
    fun `profile json omits displayName when blank and apiKeyEnv when key blank`() {
        val draft = DshCustomProviderDraft(
            route = "acme",
            displayName = "",
            baseURL = "https://api.acme.dev",
            api = "anthropic-messages",
            apiKey = "",
            models = listOf(DshDiscoveredModel(id = "m1")),
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(DshCustomProviders.profileJson(draft).toString()).jsonObject
        assertNull(obj["displayName"])
        assertNull(obj["apiKeyEnv"])
    }

    // ---- settings 路径 ----

    @Test
    fun `provider settings path is providers slash route under llm-pi-ai ns`() {
        assertEquals("llm-pi-ai", DshCustomProviders.SETTINGS_NS)
        assertEquals(listOf("providers", "acme-gateway"), DshCustomProviders.providerPath("acme-gateway"))
    }

    // ---- 目录合流（registered × configurable） ----

    @Test
    fun `joinDirectory marks active flags and appends undeclared registered providers`() {
        val registered = listOf("deepseek" to "DeepSeek", "mystery" to "Mystery Route")
        val configurable = listOf(
            DshConfigurableProvider(
                provider = "deepseek",
                displayName = "DeepSeek",
                settingsNs = "llm-deepseek",
                settingsPath = listOf("apiKey"),
                declared = true,
            ),
        )
        val rows = DshCustomProviders.joinDirectory(registered, configurable)
        assertEquals(2, rows.size)
        assertEquals("deepseek", rows[0].provider)
        assertTrue(rows[0].active)
        assertEquals(true, rows[0].declared)
        assertEquals("mystery", rows[1].provider)
        assertFalse(rows[1].active)
        assertEquals("", rows[1].settingsNs)
        assertTrue(rows[1].settingsPath.isEmpty())
    }
}
