package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DshWireAdapter #324 四域新端点注册测试（provider/设置面深对齐）。
 *
 * 契约锚点：服务器 typert 注册表（@deepseek-ai/dsh-llm / dsh-api-settings-controller /
 * dsh-agent-presets / dsh-host-plugin-inventory / dsh-api-session-controller 的
 * typert.host.js）——wire 键 = typert 参数名；多参端点 FLAT 平铺、单 request 参端点
 * WRAPPED、无参端点 EMPTY_ARGS。
 */
class DshWireAdapter324Test {

    // ---- #324① llm/credentials 域 ----

    @Test
    fun `v012 llm listConfigurableProviders renames and drops payload`() {
        assertEquals(
            "llm/listConfigurableProviders",
            DshWireAdapter.method("llm.listConfigurableProviders", DshWireProtocol.V012),
        )
        assertEquals(
            """{"args":{}}""",
            DshWireAdapter.payload("llm.listConfigurableProviders", DshWireProtocol.V012, buildJsonObject { put("x", 1) }).toString(),
        )
    }

    @Test
    fun `v012 llm discoverModels flat-wraps settingsNs and request`() {
        val payload = buildJsonObject {
            put("settingsNs", "llm-pi-ai")
            put("request", buildJsonObject { put("baseURL", "https://api.acme.dev") })
        }
        assertEquals("llm/discoverModels", DshWireAdapter.method("llm.discoverModels", DshWireProtocol.V012))
        assertEquals(
            """{"args":{"settingsNs":"llm-pi-ai","request":{"baseURL":"https://api.acme.dev"}}}""",
            DshWireAdapter.payload("llm.discoverModels", DshWireProtocol.V012, payload).toString(),
        )
    }

    @Test
    fun `v012 credentials describe set unset flat-wrap by param name`() {
        assertEquals("credentials/describe", DshWireAdapter.method("credentials.describe", DshWireProtocol.V012))
        assertEquals(
            """{"args":{"keys":["DEEPSEEK_API_KEY"]}}""",
            DshWireAdapter.payload("credentials.describe", DshWireProtocol.V012, buildJsonObject {
                put("keys", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("DEEPSEEK_API_KEY"))))
            }).toString(),
        )
        assertEquals(
            """{"args":{"key":"K","value":"v"}}""",
            DshWireAdapter.payload("credentials.set", DshWireProtocol.V012, buildJsonObject { put("key", "K"); put("value", "v") }).toString(),
        )
        assertEquals(
            """{"args":{"key":"K"}}""",
            DshWireAdapter.payload("credentials.unset", DshWireProtocol.V012, buildJsonObject { put("key", "K") }).toString(),
        )
    }

    // ---- #324② agentPresets 域 ----

    @Test
    fun `v012 agentPresets read copy deletePreset rename and flat-wrap`() {
        assertEquals("agentPresets/read", DshWireAdapter.method("agentPreset.read", DshWireProtocol.V012))
        assertEquals(
            """{"args":{"agentPreset":"code"}}""",
            DshWireAdapter.payload("agentPreset.read", DshWireProtocol.V012, buildJsonObject { put("agentPreset", "code") }).toString(),
        )
        assertEquals("agentPresets/copy", DshWireAdapter.method("agentPreset.copy", DshWireProtocol.V012))
        // name 缺席（JsonNull 丢弃）→ from/id 两键
        assertEquals(
            """{"args":{"from":"code","id":"code-copy"}}""",
            DshWireAdapter.payload("agentPreset.copy", DshWireProtocol.V012, buildJsonObject {
                put("from", "code"); put("id", "code-copy"); put("name", kotlinx.serialization.json.JsonNull)
            }).toString(),
        )
        assertEquals(
            """{"args":{"id":"code-copy"}}""",
            DshWireAdapter.payload("agentPreset.deletePreset", DshWireProtocol.V012, buildJsonObject { put("id", "code-copy") }).toString(),
        )
    }

    // ---- #324③ pluginInventory 域 ----

    @Test
    fun `v012 pluginInventory list renames and drops payload`() {
        assertEquals(
            "pluginInventory/list",
            DshWireAdapter.method("pluginInventory.list", DshWireProtocol.V012),
        )
        assertEquals(
            """{"args":{}}""",
            DshWireAdapter.payload("pluginInventory.list", DshWireProtocol.V012, buildJsonObject { put("x", 1) }).toString(),
        )
    }

    // ---- #324④ skills 域 ----

    @Test
    fun `v012 skills list mechanical slash name and request wrapping`() {
        assertEquals("skills/list", DshWireAdapter.method("skills.list", DshWireProtocol.V012))
        assertEquals(
            """{"args":{"request":{"sessionId":"s-1"}}}""",
            DshWireAdapter.payload("skills.list", DshWireProtocol.V012, buildJsonObject { put("sessionId", "s-1") }).toString(),
        )
    }

    // ---- V011 零回归：新端点名恒等（0.1.1 无此端点，调用方自行降级） ----

    @Test
    fun `v011 new endpoint names are identity`() {
        for (m in listOf(
            "llm.listConfigurableProviders", "llm.discoverModels",
            "credentials.describe", "credentials.set", "credentials.unset",
            "agentPreset.read", "agentPreset.copy", "agentPreset.deletePreset",
            "pluginInventory.list", "skills.list",
        )) {
            assertEquals(m, DshWireAdapter.method(m, DshWireProtocol.V011))
        }
    }
}
