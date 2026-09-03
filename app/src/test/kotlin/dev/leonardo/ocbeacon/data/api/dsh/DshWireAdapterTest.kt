package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DshWireAdapter 0.1.2 payload 风格表测试（#318；journal §2.3 端点注册表实测）。
 *
 * 覆盖四种风格各至少一测：
 * - SELF（恒等）：commands 两方法 + goals 六方法（调用点全量构造含 args 包装）；
 * - EMPTY_ARGS：payload 丢弃 → {args:{}}；
 * - FLAT：args=裸 payload 平铺 + sessionId→agentId 改名 + 丢弃 JsonNull 值；
 * - WRAPPED：{args:{key:payload}}（默认 request；session/list=_request）；
 * - V011 全量恒等（0.1.1 零回归基线）。
 */
class DshWireAdapterTest {

    // ---- SELF ----

    @Test
    fun `v012 commands execute is identity self`() {
        val payload = buildJsonObject {
            put("args", buildJsonObject { put("line", "/compact") })
        }
        assertEquals(payload, DshWireAdapter.payload("commands/execute", DshWireProtocol.V012, payload))
        assertEquals("commands/execute", DshWireAdapter.method("commands/execute", DshWireProtocol.V012))
    }

    @Test
    fun `v012 goals create is self with full args envelope`() {
        val payload = buildJsonObject {
            put("args", buildJsonObject {
                put("agentId", "s-1")
                put("request", buildJsonObject { put("objective", "ship") })
            })
        }
        // 方法名恒传 0.1.1 规范名——SELF 判定按翻译后的 wire 名（goals/create）
        assertEquals("goals/create", DshWireAdapter.method("goal.create", DshWireProtocol.V012))
        assertEquals(payload, DshWireAdapter.payload("goal.create", DshWireProtocol.V012, payload))
        // 斜杠名直传同样恒等（无点号名字不二次变换）
        assertEquals(payload, DshWireAdapter.payload("goals/create", DshWireProtocol.V012, payload))
    }

    @Test
    fun `v012 goals edit pause resume complete clear are self`() {
        val payload = buildJsonObject { put("args", buildJsonObject { put("agentId", "s-1") }) }
        for (m in listOf("goal.edit", "goal.pause", "goal.resume", "goal.complete", "goal.clear")) {
            assertEquals(payload, DshWireAdapter.payload(m, DshWireProtocol.V012, payload))
        }
    }

    // ---- EMPTY_ARGS ----

    @Test
    fun `v012 settings describe drops payload to empty args`() {
        val payload = buildJsonObject { put("whatever", 1) }
        assertEquals(
            """{"args":{}}""",
            DshWireAdapter.payload("settings.describe", DshWireProtocol.V012, payload).toString(),
        )
    }

    @Test
    fun `v012 modelCatalog listProviders agentPresetsList drop payload to empty args`() {
        for (m in listOf("llm.models", "llm.providers", "agentPreset.list")) {
            assertEquals(
                """{"args":{}}""",
                DshWireAdapter.payload(m, DshWireProtocol.V012, buildJsonObject { put("x", "y") }).toString(),
            )
        }
    }

    // ---- FLAT（平铺 + 改名 + 丢 JsonNull） ----

    @Test
    fun `v012 agentPresets select flat-wraps and renames sessionId to agentId`() {
        val payload = buildJsonObject {
            put("sessionId", "s-1")
            put("agentPreset", "code")
        }
        assertEquals(
            """{"args":{"agentId":"s-1","agentPreset":"code"}}""",
            DshWireAdapter.payload("agentPreset.select", DshWireProtocol.V012, payload).toString(),
        )
    }

    @Test
    fun `v012 subagents list flat-wraps parentSessionId without rename`() {
        val payload = buildJsonObject { put("parentSessionId", "root-1") }
        assertEquals(
            """{"args":{"parentSessionId":"root-1"}}""",
            DshWireAdapter.payload("subagent.list", DshWireProtocol.V012, payload).toString(),
        )
    }

    @Test
    fun `v012 settings mutate and directoryPicker list flat-wrap bare payload`() {
        assertEquals(
            """{"args":{"ns":"permission","expectedRevision":42}}""",
            DshWireAdapter.payload(
                "settings.mutate",
                DshWireProtocol.V012,
                buildJsonObject {
                    put("ns", "permission")
                    put("expectedRevision", 42)
                },
            ).toString(),
        )
        assertEquals(
            """{"args":{"path":"/w"}}""",
            DshWireAdapter.payload("host.listDirectory", DshWireProtocol.V012, buildJsonObject { put("path", "/w") }).toString(),
        )
    }

    @Test
    fun `v012 flat wrap drops JsonNull values`() {
        // expectedRevision 为 JsonNull 时被丢弃（#318 双保险——调用点本就不放 null）
        val payload = buildJsonObject {
            put("sessionId", "s-1")
            put("expectedRevision", JsonNull)
        }
        assertEquals(
            """{"args":{"sessionId":"s-1"}}""",
            DshWireAdapter.payload("settings.mutate", DshWireProtocol.V012, payload).toString(),
        )
    }

    // ---- WRAPPED（默认 request 键；session/list 专用 _request） ----

    @Test
    fun `v012 session create wraps under request key`() {
        val payload = buildJsonObject { put("cwd", "/tmp") }
        assertEquals(
            """{"args":{"request":{"cwd":"/tmp"}}}""",
            DshWireAdapter.payload("session.create", DshWireProtocol.V012, payload).toString(),
        )
    }

    @Test
    fun `v012 session list wraps under underscore request key`() {
        assertEquals(
            """{"args":{"_request":{}}}""",
            DshWireAdapter.payload("session.list", DshWireProtocol.V012, buildJsonObject {}).toString(),
        )
    }

    @Test
    fun `v012 session history renamed to page and wraps under request`() {
        assertEquals("session/page", DshWireAdapter.method("session.history", DshWireProtocol.V012))
        val payload = buildJsonObject { put("address", buildJsonObject { put("kind", "session") }) }
        assertEquals(
            """{"args":{"request":{"address":{"kind":"session"}}}}""",
            DshWireAdapter.payload("session.history", DshWireProtocol.V012, payload).toString(),
        )
    }

    // ---- V011 恒等（0.1.1 零回归基线） ----

    @Test
    fun `v011 payload and method are always identity`() {
        val payload = buildJsonObject { put("sessionId", "s-1") }
        for (m in listOf("session.list", "goal.create", "session.history", "settings.mutate", "agentPreset.select")) {
            assertEquals(payload, DshWireAdapter.payload(m, DshWireProtocol.V011, payload))
            assertEquals(m, DshWireAdapter.method(m, DshWireProtocol.V011))
        }
    }
}
