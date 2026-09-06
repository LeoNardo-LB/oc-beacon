package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshSessionMapper 字段逐一断言测试（backlog #276 步骤③；设计 §5 session.list 形状）。
 *
 * P-4 实测载荷：{sessionId, updatedAt(epoch-ms), running, blank, parentSessionId?,
 * origin?, cwd?, agentPreset?, projections?{asOfSeq, values{title{title},...}}}。
 */
class DshSessionMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `maps full item field by field`() {
        // origin 唯一 wire 值是 "subagent"（服务器 listFields 仅 header.origin 在场时摊出，
        // session 包 origin?: "subagent"）；#331 起 parentId 仅 subagent 行映射。
        val item = obj("""{
            "sessionId":"sess-0001","updatedAt":1788109000023,"running":true,"blank":false,
            "parentSessionId":"sess-0000","origin":"subagent","cwd":"/home/user/project",
            "agentPreset":"code",
            "projections":{"asOfSeq":17,"values":{
                "title":{"title":"fixture session"},
                "sessionListMetadata":{"blank":false,"lastPromptAt":1788109000011}
            }}
        }""".trimIndent())
        val session = DshSessionMapper.toSession(item)
        assertEquals("sess-0001", session.id)
        assertEquals(1788109000023L, session.time.updated)
        assertEquals("/home/user/project", session.directory)
        assertEquals("sess-0000", session.parentId)
        assertEquals("fixture session", session.title)
        assertFalse(session.blank)
        assertEquals("code", session.agentPreset)
    }

    @Test
    fun `blank and agentPreset parse from top-level fields`() {
        // 活体（ap-3/ap-4）：blank/agentPreset 是 session.list 条目顶层字段
        val blankItem = obj("""{"sessionId":"s-blank","updatedAt":9,"running":false,
            "blank":true,"agentPreset":"minimal"}""".trimIndent())
        val session = DshSessionMapper.toSession(blankItem)
        assertTrue(session.blank)
        assertEquals("minimal", session.agentPreset)

        val missingItem = obj("""{"sessionId":"s-none","updatedAt":1,"running":false}""")
        assertFalse(DshSessionMapper.toSession(missingItem).blank)
        assertNull(DshSessionMapper.toSession(missingItem).agentPreset)
    }

    @Test
    fun `missing optional fields degrade without crash`() {
        val item = obj("""{"sessionId":"s-2","updatedAt":42,"running":false,"blank":false}""")
        val session = DshSessionMapper.toSession(item)
        assertEquals("s-2", session.id)
        assertEquals(42L, session.time.updated)
        assertEquals("", session.directory)
        assertNull(session.parentId)
        assertNull(session.title)
    }

    @Test
    fun `title projection as bare string maps to title`() {
        // E2E 实证（2026-08-31）：活体投影值是裸字符串（364/378 str）——曾因 fixture 误设
        // 对象形态致列表全无标题
        val item = obj("""{"sessionId":"s-4","updatedAt":7,"running":false,"blank":false,
            "projections":{"asOfSeq":9,"values":{"title":"真实标题"}}}""")
        assertEquals("真实标题", DshSessionMapper.toSession(item).title)
    }

    @Test
    fun `title projection as object still maps via dual read`() {
        val item = obj("""{"sessionId":"s-5","updatedAt":7,"running":false,"blank":false,
            "projections":{"asOfSeq":9,"values":{"title":{"title":"对象形态"}}}}""")
        assertEquals("对象形态", DshSessionMapper.toSession(item).title)
    }

    @Test
    fun `title projection absent leaves title null`() {
        val item = obj("""{"sessionId":"s-3","updatedAt":1,"running":false,"blank":false,
            "projections":{"asOfSeq":3,"values":{"sessionStats":{"turns":2}}}}""")
        assertNull(DshSessionMapper.toSession(item).title)
    }

    @Test
    fun `permissions projection maps options and current value`() {
        // 活体投影（perm-6c）：permissions = {options:[{value,name,description?}],currentValue}
        val item = obj("""{"sessionId":"s-6","updatedAt":7,"running":false,"blank":false,
            "projections":{"asOfSeq":9,"values":{"permissions":{
                "options":[
                    {"value":"read-only","name":"Read only","description":"No writes"},
                    {"value":"workspace-write","name":"Workspace write"},
                    {"value":"danger-full-access","name":"Full access"}
                ],
                "currentValue":"workspace-write"
            }}}}""".trimIndent())
        val permissions = DshSessionMapper.toSession(item).permissions
        assertNotNull(permissions)
        assertEquals(3, permissions!!.options.size)
        assertEquals("read-only", permissions.options[0].value)
        assertEquals("Read only", permissions.options[0].name)
        assertEquals("No writes", permissions.options[0].description)
        assertEquals("workspace-write", permissions.options[1].value)
        assertNull(permissions.options[1].description) // description 可选
        assertEquals("workspace-write", permissions.currentValue)
        assertFalse(permissions.isCustom)
    }

    @Test
    fun `permissions projection absent leaves permissions null`() {
        val item = obj("""{"sessionId":"s-7","updatedAt":1,"running":false,"blank":false,
            "projections":{"asOfSeq":3,"values":{"title":"T"}}}""".trimIndent())
        assertNull(DshSessionMapper.toSession(item).permissions)
    }

    @Test
    fun `tokenUsage projection maps four buckets and total`() {
        val item = obj("""{"sessionId":"s-8","updatedAt":7,"running":false,"blank":false,
            "projections":{"asOfSeq":9,"values":{"tokenUsage":{
                "uncachedInputTokens":100,"outputTokens":50,
                "cacheReadTokens":20,"cacheWriteTokens":0}}}}""".trimIndent())
        val usage = DshSessionMapper.toSession(item).tokenUsage
        assertNotNull(usage)
        assertEquals(100L, usage!!.uncachedInputTokens)
        assertEquals(50L, usage.outputTokens)
        assertEquals(20L, usage.cacheReadTokens)
        assertEquals(0L, usage.cacheWriteTokens)
        assertEquals(170L, usage.total)
    }

    @Test
    fun `subagentTiming projection maps settled and active bounds`() {
        val item = obj("""{"sessionId":"s-9","updatedAt":7,"running":false,"blank":false,
            "projections":{"asOfSeq":9,"values":{"subagentTiming":{
                "settledMs":1500,"active":{"since":1000,"through":2500}}}}}""".trimIndent())
        val timing = DshSessionMapper.toSession(item).subagentTiming
        assertNotNull(timing)
        assertEquals(1500L, timing!!.settledMs)
        assertEquals(1000L, timing.activeSince)
        assertEquals(2500L, timing.activeThrough)
        assertEquals(3000L, timing.activeDurationMs)
    }

    @Test
    fun `tokenUsage and subagentTiming absent leave fields null`() {
        val item = obj("""{"sessionId":"s-10","updatedAt":1,"running":false,"blank":false,
            "projections":{"asOfSeq":3,"values":{"title":"T"}}}""".trimIndent())
        val session = DshSessionMapper.toSession(item)
        assertNull(session.tokenUsage)
        assertNull(session.subagentTiming)
    }

    @Test
    fun `directory filter matches cwd and blank tolerance`() {
        val items = listOf(
            obj("""{"sessionId":"a","updatedAt":2,"running":false,"blank":false,"cwd":"/w/one"}"""),
            obj("""{"sessionId":"b","updatedAt":3,"running":false,"blank":false,"cwd":"/w/two"}"""),
            obj("""{"sessionId":"c","updatedAt":4,"running":false,"blank":true,"cwd":"/w/one"}"""),
        )
        val filtered = DshSessionMapper.filterByDirectory(items, "/w/one")
        assertEquals(listOf("a"), filtered.map { DshSessionMapper.toSession(it).id })
        // directory=null：不做目录过滤，但 blank 空壳恒滤除（与 V1 headerless 语义对齐）
        assertEquals(2, DshSessionMapper.filterByDirectory(items, null).size)
    }

    // ============ backlog #285：goal/contextPressure/contextBreakdown/sessionStats 投影基线 ============

    @Test
    fun `session row with goal projection seeds session goal`() {
        val item = Json.parseToJsonElement(
            """{"sessionId":"s-1","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":5,"values":{
                "goal":{"goal":{"id":"goal-1","revision":2,"objective":"build","phase":"blocked","blockedReason":{"code":"goal-blocked-rounds","message":"exhausted"},"maxGoalRounds":3},"roundsStarted":2,"createdAt":1,"updatedAt":2},
                "contextPressure":{"pressureTokens":124658,"projectedTokens":125148,"contextWindow":1000000},
                "contextBreakdown":{"systemTokens":9408,"toolsTokens":240,"messageTokens":99722},
                "sessionStats":{"turns":1,"steps":60,"llmMs":304019,"toolMs":3514,"ttftMs":194905,"ttftSteps":61,"decodeMs":109114,"decodeTokens":17112}
            }}}"""
        ).jsonObject
        val session = DshSessionMapper.toSession(item)
        assertEquals("goal-1", session.goal!!.goal.id)
        assertEquals(2L, session.goal!!.goal.revision)
        assertEquals("blocked", session.goal!!.goal.phase)
        assertEquals("exhausted", session.goal!!.goal.blockedReason!!.message)
        assertEquals(3L, session.goal!!.goal.maxGoalRounds)
        assertEquals(2L, session.goal!!.roundsStarted)
        assertEquals(125148L, session.contextPressure!!.projectedTokens)
        assertEquals(1000000L, session.contextPressure!!.contextWindow)
        assertEquals(99722L, session.contextBreakdown!!.messageTokens)
        assertEquals(60L, session.sessionStats!!.steps)
        assertEquals(304019L, session.sessionStats!!.llmMs)
    }

    @Test
    fun `session row without projections keeps new DSH fields null`() {
        val item = Json.parseToJsonElement(
            """{"sessionId":"s-2","updatedAt":1,"running":true,"blank":false}"""
        ).jsonObject
        val session = DshSessionMapper.toSession(item)
        assertNull(session.goal)
        assertNull(session.contextPressure)
        assertNull(session.contextBreakdown)
        assertNull(session.sessionStats)
    }

    // ---- toSession：parentId origin 判别（#331）---------------------------
    // Session.parentId 在 app 侧的语义 = durable subagent 父（ChatSendDelegate
    // subagents/prompt 分流 / interruptByParent / 列表 parentId==null 过滤均按此
    // 解释）；fork 子会话（parentSessionId 在、origin 缺席）是普通会话——不为它
    // 置 parentId，否则行被列表滤除 + 发送误路由 subagents/prompt 被服务器拒
    // （validateAddress: origin!=subagent 给 subagent 地址 → subagent/unauthorized）。

    @Test
    fun `toSession fork child without origin keeps parentId null`() {
        val session = DshSessionMapper.toSession(
            obj(FORK_CHILD_JSON),
        )
        assertEquals("s-fork", session.id)
        assertNull(session.parentId)
    }

    @Test
    fun `toSession subagent child maps parentId`() {
        val session = DshSessionMapper.toSession(
            obj(SUBAGENT_CHILD_JSON),
        )
        assertEquals("s-child", session.id)
        assertEquals("s-parent", session.parentId)
    }

    // ---- DshSessionAddress.fromListItem origin 判别 #333 --------------------
    // 服务器 validateAddress(dsh-api-session-controller index.js:1374-1392):
    // kind:session 地址对 origin!=subagent 的会话恒合法; durable subagent 地址仅对
    // origin==subagent 合法。fork 子会话(parentSession 在 header, origin 缺席——
    // session.fork meta 无 origin 字段, 服务器源码 fork() 实证)按普通 session 地址
    // 寻址, 不因 parentSessionId 被误判为 subagent。

    /** fork 子会话(parentSessionId 在, origin 缺席) -> 普通 session 地址。 */
    @Test
    fun `fromListItem fork child without origin maps to session address`() {
        val item = obj(FORK_CHILD_JSON)
        val address = DshSessionAddress.fromListItem(item)
        assertEquals("session", address.strKey("kind"))
        assertEquals("s-fork", address.strKey("sessionId"))
    }

    /** origin=subagent 子会话(含 mode 投影) -> durable subagent 地址(#310① 既有语义)。 */
    @Test
    fun `fromListItem subagent child with mode maps to durable address`() {
        val item = obj(SUBAGENT_CHILD_JSON)
        val address = DshSessionAddress.fromListItem(item)
        assertEquals("subagent", address.strKey("kind"))
        assertEquals("s-parent", address.strKey("parentSessionId"))
        assertEquals("continuable", address.strKey("mode"))
    }

    /** origin=subagent 无 mode 投影 -> null(validateAddress 强校验 identity.mode, 保守跳过不变)。 */
    @Test
    fun `fromListItem subagent child without mode projection stays null`() {
        val item = obj(SUBAGENT_CHILD_NO_MODE_JSON)
        assertNull(DshSessionAddress.fromListItem(item))
    }

    /** 普通会话 -> kind:session(既有行为回归钉)。 */
    @Test
    fun `fromListItem ordinary session maps to session address`() {
        val item = obj(ORDINARY_JSON)
        val address = DshSessionAddress.fromListItem(item)
        assertEquals("session", address.strKey("kind"))
        assertEquals("s-1", address.strKey("sessionId"))
    }

    private fun JsonObject?.strKey(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.content

    private companion object {
        private const val FORK_CHILD_JSON =
            "{" +
                "\"sessionId\":\"s-fork\",\"updatedAt\":1,\"running\":false," +
                "\"parentSessionId\":\"s-parent\"" +
                "}"
        private const val SUBAGENT_CHILD_JSON =
            "{" +
                "\"sessionId\":\"s-child\",\"updatedAt\":1,\"running\":false," +
                "\"parentSessionId\":\"s-parent\",\"origin\":\"subagent\"," +
                "\"projections\":{\"asOfSeq\":5,\"values\":{\"subagent\":{\"mode\":\"continuable\",\"label\":\"x\",\"seq\":5}}}" +
                "}"
        private const val SUBAGENT_CHILD_NO_MODE_JSON =
            "{" +
                "\"sessionId\":\"s-child\",\"updatedAt\":1,\"running\":false," +
                "\"parentSessionId\":\"s-parent\",\"origin\":\"subagent\"" +
                "}"
        private const val ORDINARY_JSON =
            "{" +
                "\"sessionId\":\"s-1\",\"updatedAt\":1,\"running\":false" +
                "}"
    }
}
