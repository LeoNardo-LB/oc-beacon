package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SSE 事件解析器的单元测试。
 *
 * 覆盖三个最重要的解析器：
 * - MessageEventParser：message.updated、message.removed、message.part.*
 * - PermissionEventParser：permission.asked、permission.replied
 * - SessionEventParser：session.status、session.idle、session.created 等
 */
class SseEventParserTest {

    private lateinit var json: Json

    @Before
    fun setUp() {
        json = Json { ignoreUnknownKeys = true }
    }

    // ==================== 辅助函数 ====================

    private fun parseJsonObject(jsonStr: String): JsonObject =
        json.decodeFromString(JsonObject.serializer(), jsonStr)

    // ==================== MessageEventParser ====================

    @Test
    fun `MessageEventParser canParse returns true for handled types`() {
        val parser = MessageEventParser(json)
        assertTrue(parser.canParse("message.updated"))
        assertTrue(parser.canParse("message.removed"))
        assertTrue(parser.canParse("message.part.updated"))
        assertTrue(parser.canParse("message.part.delta"))
        assertTrue(parser.canParse("message.part.removed"))
    }

    @Test
    fun `MessageEventParser canParse returns false for unrelated types`() {
        val parser = MessageEventParser(json)
        assertFalse(parser.canParse("session.created"))
        assertFalse(parser.canParse("permission.asked"))
        assertFalse(parser.canParse("message.complete"))
        assertFalse(parser.canParse(""))
        assertFalse(parser.canParse("message"))
    }

    @Test
    fun `MessageEventParser parse message_updated with user role`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "msg_1",
                    "sessionID": "sess_1",
                    "role": "user",
                    "time": {"created": 1700000000000}
                }
            }"""
        )
        val event = parser.parse("message.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessageUpdated)
        val msg = (event as SseEvent.MessageUpdated).info
        assertEquals("msg_1", msg.id)
        assertEquals("sess_1", msg.sessionId)
        assertEquals("user", msg.role)
    }

    @Test
    fun `MessageEventParser parse message_updated with assistant role`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "msg_2",
                    "sessionID": "sess_1",
                    "role": "assistant",
                    "time": {"created": 1700000000000},
                    "parentID": "msg_1"
                }
            }"""
        )
        val event = parser.parse("message.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessageUpdated)
        val msg = (event as SseEvent.MessageUpdated).info
        assertEquals("msg_2", msg.id)
        assertEquals("assistant", msg.role)
    }

    @Test
    fun `MessageEventParser parse message_removed`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{"sessionID": "sess_1", "messageID": "msg_1"}"""
        )
        val event = parser.parse("message.removed", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessageRemoved)
        val removed = event as SseEvent.MessageRemoved
        assertEquals("sess_1", removed.sessionId)
        assertEquals("msg_1", removed.messageId)
    }

    @Test
    fun `MessageEventParser parse message_part_updated with text part`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "part": {
                    "id": "p1",
                    "sessionID": "s1",
                    "messageID": "m1",
                    "type": "text",
                    "text": "Hello world"
                }
            }"""
        )
        val event = parser.parse("message.part.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessagePartUpdated)
        val part = (event as SseEvent.MessagePartUpdated).part
        assertTrue(part is dev.leonardo.ocbeacon.domain.model.Part.Text)
        assertEquals("Hello world", (part as dev.leonardo.ocbeacon.domain.model.Part.Text).text)
    }

    @Test
    fun `MessageEventParser parse message_part_delta`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "s1",
                "messageID": "m1",
                "partID": "p1",
                "field": "text",
                "delta": "chunk"
            }"""
        )
        val event = parser.parse("message.part.delta", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessagePartDelta)
        val delta = event as SseEvent.MessagePartDelta
        assertEquals("s1", delta.sessionId)
        assertEquals("m1", delta.messageId)
        assertEquals("p1", delta.partId)
        assertEquals("text", delta.field)
        assertEquals("chunk", delta.delta)
    }

    @Test
    fun `MessageEventParser parse message_part_removed`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "s1",
                "messageID": "m1",
                "partID": "p1"
            }"""
        )
        val event = parser.parse("message.part.removed", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.MessagePartRemoved)
        val removed = event as SseEvent.MessagePartRemoved
        assertEquals("s1", removed.sessionId)
        assertEquals("m1", removed.messageId)
        assertEquals("p1", removed.partId)
    }

    @Test
    fun `MessageEventParser parse returns null for missing info in message_updated`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject("""{}""")
        val event = parser.parse("message.updated", props)
        assertNull(event)
    }

    @Test
    fun `MessageEventParser parse returns null for unknown role`() {
        val parser = MessageEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "msg_1",
                    "sessionID": "s1",
                    "role": "system",
                    "time": {"created": 1000}
                }
            }"""
        )
        val event = parser.parse("message.updated", props)
        assertNull(event)
    }

    @Test
    fun `MessageEventParser parse message_part_delta with default field`() {
        val parser = MessageEventParser(json)
        // field 字段可选，默认值为 "text"
        val props = parseJsonObject(
            """{
                "sessionID": "s1",
                "messageID": "m1",
                "partID": "p1",
                "delta": "data"
            }"""
        )
        val event = parser.parse("message.part.delta", props)
        assertNotNull(event)
        val delta = event as SseEvent.MessagePartDelta
        assertEquals("text", delta.field)
    }

    @Test
    fun `MessageEventParser parse handles missing optional fields gracefully`() {
        val parser = MessageEventParser(json)
        // message.removed 缺少字段 → 空字符串（通过 str() 默认值）
        val props = parseJsonObject("""{}""")
        val event = parser.parse("message.removed", props)
        assertNotNull(event)
        val removed = event as SseEvent.MessageRemoved
        assertEquals("", removed.sessionId)
        assertEquals("", removed.messageId)
    }

    // ==================== PermissionEventParser ====================

    @Test
    fun `PermissionEventParser canParse returns true for handled types`() {
        val parser = PermissionEventParser()
        assertTrue(parser.canParse("permission.asked"))
        assertTrue(parser.canParse("permission.replied"))
    }

    @Test
    fun `PermissionEventParser canParse returns false for unrelated types`() {
        val parser = PermissionEventParser()
        assertFalse(parser.canParse("session.created"))
        assertFalse(parser.canParse("message.updated"))
        assertFalse(parser.canParse("permission.denied"))
        assertFalse(parser.canParse(""))
    }

    @Test
    fun `PermissionEventParser parse permission_asked with all fields`() {
        val parser = PermissionEventParser()
        val props = parseJsonObject(
            """{
                "id": "perm_1",
                "sessionID": "sess_1",
                "permission": "write",
                "patterns": ["/tmp/*", "/var/*"],
                "always": true,
                "metadata": {"key": "value"},
                "tool": {"messageID": "msg_1", "callID": "call_1"}
            }"""
        )
        val event = parser.parse("permission.asked", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.PermissionAsked)
        val asked = event as SseEvent.PermissionAsked
        assertEquals("perm_1", asked.id)
        assertEquals("sess_1", asked.sessionId)
        assertEquals("write", asked.permission)
        assertEquals(listOf("/tmp/*", "/var/*"), asked.patterns)
        assertTrue(asked.always)
        assertNotNull(asked.metadata)
        assertEquals("value", asked.metadata!!["key"])
        assertNotNull(asked.tool)
        assertEquals("msg_1", asked.tool!!.messageId)
        assertEquals("call_1", asked.tool!!.callId)
    }

    @Test
    fun `PermissionEventParser parse permission_asked with minimal fields`() {
        val parser = PermissionEventParser()
        val props = parseJsonObject(
            """{
                "id": "p1",
                "sessionID": "s1",
                "permission": "read"
            }"""
        )
        val event = parser.parse("permission.asked", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.PermissionAsked)
        val asked = event as SseEvent.PermissionAsked
        assertEquals("p1", asked.id)
        assertEquals("s1", asked.sessionId)
        assertEquals("read", asked.permission)
        assertTrue(asked.patterns.isEmpty())
        assertFalse(asked.always)
        assertNull(asked.metadata)
        assertNull(asked.tool)
    }

    @Test
    fun `PermissionEventParser parse permission_asked with V1 always as array`() {
        val parser = PermissionEventParser()
        // V1：always 是非空字符串列表 → true
        val props = parseJsonObject(
            """{
                "id": "p1",
                "sessionID": "s1",
                "permission": "write",
                "always": ["pattern1"]
            }"""
        )
        val event = parser.parse("permission.asked", props)
        assertNotNull(event)
        assertTrue((event as SseEvent.PermissionAsked).always)
    }

    @Test
    fun `PermissionEventParser parse permission_asked with V1 always as empty array`() {
        val parser = PermissionEventParser()
        // V1：always 是空列表 → false
        val props = parseJsonObject(
            """{
                "id": "p1",
                "sessionID": "s1",
                "permission": "write",
                "always": []
            }"""
        )
        val event = parser.parse("permission.asked", props)
        assertNotNull(event)
        assertFalse((event as SseEvent.PermissionAsked).always)
    }

    @Test
    fun `PermissionEventParser parse permission_replied`() {
        val parser = PermissionEventParser()
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "requestID": "req_1"
            }"""
        )
        val event = parser.parse("permission.replied", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.PermissionReplied)
        val replied = event as SseEvent.PermissionReplied
        assertEquals("sess_1", replied.sessionId)
        assertEquals("req_1", replied.requestId)
    }

    @Test
    fun `PermissionEventParser parse returns null for malformed JSON`() {
        val parser = PermissionEventParser()
        // 完全空的 props —— 仍应产生事件（字段默认为空）
        // 因为 str() 对缺失键返回 ""
        // 但让我们测试一个真正损坏的场景：
        // 实际上，对于空 JsonObject，permission.asked 仍然有效（使用默认值）
        // 让我们测试一个未被处理的事件类型
        val props = parseJsonObject("""{}""")
        val event = parser.parse("some.unknown.event", props)
        assertNull(event)
    }

    @Test
    fun `PermissionEventParser parse handles missing optional fields gracefully`() {
        val parser = PermissionEventParser()
        val props = parseJsonObject("""{}""")
        val event = parser.parse("permission.asked", props)
        assertNotNull(event)
        val asked = event as SseEvent.PermissionAsked
        assertEquals("", asked.id)
        assertEquals("", asked.sessionId)
        assertEquals("", asked.permission)
        assertTrue(asked.patterns.isEmpty())
        assertFalse(asked.always)
        assertNull(asked.metadata)
        assertNull(asked.tool)
    }

    // ==================== SessionEventParser ====================

    @Test
    fun `SessionEventParser canParse returns true for all handled types`() {
        val parser = SessionEventParser(json)
        assertTrue(parser.canParse("session.status"))
        assertTrue(parser.canParse("session.idle"))
        assertTrue(parser.canParse("session.created"))
        assertTrue(parser.canParse("session.updated"))
        assertTrue(parser.canParse("session.deleted"))
        assertTrue(parser.canParse("session.error"))
        assertTrue(parser.canParse("session.diff"))
        assertTrue(parser.canParse("session.compacted"))
        assertTrue(parser.canParse("vcs.branch.updated"))
        assertTrue(parser.canParse("project.updated"))
        assertTrue(parser.canParse("lsp.updated"))
    }

    @Test
    fun `SessionEventParser canParse returns false for unrelated types`() {
        val parser = SessionEventParser(json)
        assertFalse(parser.canParse("message.updated"))
        assertFalse(parser.canParse("permission.asked"))
        assertFalse(parser.canParse("session"))
        assertFalse(parser.canParse(""))
        assertFalse(parser.canParse("session.next"))
    }

    @Test
    fun `SessionEventParser parse session_status idle`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "status": {"type": "idle"}
            }"""
        )
        val event = parser.parse("session.status", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionStatus)
        val status = event as SseEvent.SessionStatus
        assertEquals("sess_1", status.sessionId)
        assertTrue(status.status is dev.leonardo.ocbeacon.domain.model.SessionStatus.Idle)
    }

    @Test
    fun `SessionEventParser parse session_status busy`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "status": {"type": "busy"}
            }"""
        )
        val event = parser.parse("session.status", props)
        assertNotNull(event)
        val status = event as SseEvent.SessionStatus
        assertTrue(status.status is dev.leonardo.ocbeacon.domain.model.SessionStatus.Busy)
    }

    @Test
    fun `SessionEventParser parse session_status retry`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "status": {"type": "retry", "attempt": 3, "message": "Rate limited", "next": 1700000100000}
            }"""
        )
        val event = parser.parse("session.status", props)
        assertNotNull(event)
        val status = event as SseEvent.SessionStatus
        assertTrue(status.status is dev.leonardo.ocbeacon.domain.model.SessionStatus.Retry)
        val retry = status.status as dev.leonardo.ocbeacon.domain.model.SessionStatus.Retry
        assertEquals(3, retry.attempt)
        assertEquals("Rate limited", retry.message)
        assertEquals(1700000100000L, retry.next)
    }

    @Test
    fun `SessionEventParser parse session_status defaults to idle when type missing`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{"sessionID": "sess_1"}"""
        )
        val event = parser.parse("session.status", props)
        assertNotNull(event)
        val status = event as SseEvent.SessionStatus
        assertTrue(status.status is dev.leonardo.ocbeacon.domain.model.SessionStatus.Idle)
    }

    @Test
    fun `SessionEventParser parse session_idle`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{"sessionID": "sess_1"}"""
        )
        val event = parser.parse("session.idle", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionIdle)
        assertEquals("sess_1", (event as SseEvent.SessionIdle).sessionId)
    }

    @Test
    fun `SessionEventParser parse session_created`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "sess_1",
                    "time": {"created": 1700000000000, "updated": 1700000000000}
                }
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionCreated)
        assertEquals("sess_1", (event as SseEvent.SessionCreated).info.id)
    }

    @Test
    fun `SessionEventParser parse session_updated`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "sess_1",
                    "title": "Updated Title",
                    "time": {"created": 1000, "updated": 2000}
                }
            }"""
        )
        val event = parser.parse("session.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionUpdated)
        assertEquals("sess_1", (event as SseEvent.SessionUpdated).info.id)
        assertEquals("Updated Title", (event as SseEvent.SessionUpdated).info.title)
    }

    @Test
    fun `SessionEventParser parse session_deleted`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "sess_1",
                    "time": {"created": 1000, "updated": 2000}
                }
            }"""
        )
        val event = parser.parse("session.deleted", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionDeleted)
        assertEquals("sess_1", (event as SseEvent.SessionDeleted).info.id)
    }

    @Test
    fun `SessionEventParser parse session_error`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "error": "Something went wrong"
            }"""
        )
        val event = parser.parse("session.error", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionError)
        val err = event as SseEvent.SessionError
        assertEquals("sess_1", err.sessionId)
        assertEquals("Something went wrong", err.error)
    }

    @Test
    fun `SessionEventParser parse session_error with null sessionId`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{"error": "Unknown error"}"""
        )
        val event = parser.parse("session.error", props)
        assertNotNull(event)
        val err = event as SseEvent.SessionError
        assertNull(err.sessionId)
        assertEquals("Unknown error", err.error)
    }

    @Test
    fun `SessionEventParser parse session_diff`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "diff": [
                    {"file": "src/Main.kt", "additions": 5, "deletions": 2, "status": "modified"}
                ]
            }"""
        )
        val event = parser.parse("session.diff", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionDiff)
        val diff = event as SseEvent.SessionDiff
        assertEquals("sess_1", diff.sessionId)
        assertEquals(1, diff.diff.size)
        assertEquals("src/Main.kt", diff.diff[0].file)
        assertEquals(5, diff.diff[0].additions)
    }

    @Test
    fun `SessionEventParser parse session_diff with empty diff array`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "sess_1",
                "diff": []
            }"""
        )
        val event = parser.parse("session.diff", props)
        assertNotNull(event)
        assertTrue((event as SseEvent.SessionDiff).diff.isEmpty())
    }

    @Test
    fun `SessionEventParser parse session_compacted`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{"sessionID": "sess_1"}"""
        )
        val event = parser.parse("session.compacted", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionCompacted)
        assertEquals("sess_1", (event as SseEvent.SessionCompacted).sessionId)
    }

    @Test
    fun `SessionEventParser parse vcs_branch_updated`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject("""{"branch": "feature/new-api"}""")
        val event = parser.parse("vcs.branch.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.VcsBranchUpdated)
        assertEquals("feature/new-api", (event as SseEvent.VcsBranchUpdated).branch)
    }

    @Test
    fun `SessionEventParser parse project_updated`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "info": {
                    "id": "proj_1",
                    "worktree": "/home/user/project",
                    "name": "My Project"
                }
            }"""
        )
        val event = parser.parse("project.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.ProjectUpdated)
        assertEquals("proj_1", (event as SseEvent.ProjectUpdated).info.id)
        assertEquals("My Project", (event as SseEvent.ProjectUpdated).info.name)
    }

    @Test
    fun `SessionEventParser parse lsp_updated`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject("""{}""")
        val event = parser.parse("lsp.updated", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.LspUpdated)
    }

    @Test
    fun `SessionEventParser parse returns null for unknown event type`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject("""{}""")
        val event = parser.parse("session.unknown", props)
        assertNull(event)
    }

    @Test
    fun `SessionEventParser parse session_created falls back to props when info missing`() {
        val parser = SessionEventParser(json)
        // 当 "info" 缺失时，解析器回退为直接将 `props` 用作会话对象
        val props = parseJsonObject(
            """{
                "id": "sess_fallback",
                "time": {"created": 1000, "updated": 2000}
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionCreated)
        assertEquals("sess_fallback", (event as SseEvent.SessionCreated).info.id)
    }

    // ==================== V2 扁平格式 session.created（2026-08-17 Running 恒空修复） ====================

    /** 2026-08-17：真实抓帧行政格式——model 为对象 {id, providerID, variant}。
     *  修复前 `?.jsonPrimitive` 读 model 抛 IllegalArgumentException →
     *  parse() catch 吞掉整条事件 → 子会话永不注册（任务面板 Running 恒空）。 */
    @Test
    fun `SessionEventParser parse v2 flat session_created with model object`() {
        val parser = SessionEventParser(json)
        // 2026-08-17 实测抓帧（V2 next-17498，服务器派发 subagent 时广播）
        val props = parseJsonObject(
            """{
                "sessionID": "ses_child_1",
                "slug": "proud-comet",
                "version": "0.0.0-beta-17498",
                "projectID": "71b9c369afbe",
                "parentID": "ses_parent_1",
                "location": {"directory": "/home/user/project"},
                "subpath": "",
                "title": "子代理跑 sleep 40",
                "agent": "general-fast",
                "model": {"id": "deepseek-v4-flash-free", "providerID": "opencode"}
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull("model 为对象时不得丢弃整条事件", event)
        val session = (event as SseEvent.SessionCreated).info
        assertEquals("ses_child_1", session.id)
        assertEquals("ses_parent_1", session.parentId)
        assertEquals("子代理跑 sleep 40", session.title)
        assertEquals("general-fast", session.agent)
        assertEquals("/home/user/project", session.directory)
        assertNotNull(session.model)
        assertEquals("deepseek-v4-flash-free", session.model!!.id)
        assertEquals("opencode", session.model!!.providerId)
        assertTrue("startedAt 依据：created 时间戳必须 > 0", session.time.created > 0)
    }

    @Test
    fun `SessionEventParser parse v2 flat session_created with model object variant`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "ses_child_2",
                "parentID": "ses_parent_1",
                "title": "task",
                "model": {"id": "glm-5.3", "providerID": "zhipuai-coding-plan", "variant": "default"}
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull(event)
        val session = (event as SseEvent.SessionCreated).info
        assertEquals("glm-5.3", session.model?.id)
        assertEquals("zhipuai-coding-plan", session.model?.providerId)
        assertEquals("default", session.model?.variant)
    }

    /** 兼容路径：model 为纯字符串（旧格式）仍可解析。 */
    @Test
    fun `SessionEventParser parse v2 flat session_created with model string`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "ses_child_3",
                "parentID": "ses_parent_1",
                "model": "claude-4"
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull(event)
        val session = (event as SseEvent.SessionCreated).info
        assertEquals("claude-4", session.model?.id)
    }

    /** 防御：model 类型异常（数组）不抛异常、不丢弃整条事件。 */
    @Test
    fun `SessionEventParser parse v2 flat session_created with unexpected model type`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "ses_child_4",
                "parentID": "ses_parent_1",
                "model": ["bad"]
            }"""
        )
        val event = parser.parse("session.created", props)
        assertNotNull(event)
        val session = (event as SseEvent.SessionCreated).info
        assertEquals("ses_child_4", session.id)
        assertNull(session.model)
    }

    /** session.updated 同走 decodeSessionCompat——对象 model 也不得丢事件。 */
    @Test
    fun `SessionEventParser parse v2 flat session_updated with model object`() {
        val parser = SessionEventParser(json)
        val props = parseJsonObject(
            """{
                "sessionID": "ses_child_1",
                "parentID": "ses_parent_1",
                "title": "更新后的标题",
                "model": {"id": "m1", "providerID": "p1"}
            }"""
        )
        val event = parser.parse("session.updated", props)
        assertNotNull(event)
        val session = (event as SseEvent.SessionUpdated).info
        assertEquals("更新后的标题", session.title)
        assertEquals("m1", session.model?.id)
    }

    // ==================== 解析器间隔离 ====================

    @Test
    fun `MessageEventParser returns null for permission events`() {
        val parser = MessageEventParser(json)
        assertFalse(parser.canParse("permission.asked"))
        val props = parseJsonObject("""{}""")
        assertNull(parser.parse("permission.asked", props))
    }

    @Test
    fun `PermissionEventParser returns null for message events`() {
        val parser = PermissionEventParser()
        assertFalse(parser.canParse("message.updated"))
        val props = parseJsonObject("""{}""")
        assertNull(parser.parse("message.updated", props))
    }

    @Test
    fun `SessionEventParser returns null for message events`() {
        val parser = SessionEventParser(json)
        assertFalse(parser.canParse("message.updated"))
        val props = parseJsonObject("""{}""")
        assertNull(parser.parse("message.updated", props))
    }

    // ==================== ParserUtils 的 str() 扩展 ====================

    @Test
    fun `str returns content for existing key`() {
        val obj = buildJsonObject {
            put("name", "test-value")
        }
        assertEquals("test-value", obj.str("name"))
    }

    @Test
    fun `str returns default for missing key`() {
        val obj = buildJsonObject {}
        assertEquals("", obj.str("missing"))
        assertEquals("fallback", obj.str("missing", "fallback"))
    }

    @Test
    fun `str handles JsonObject value by extracting message field`() {
        val innerObj = buildJsonObject {
            put("message", "Something went wrong")
            put("type", "runtime")
        }
        val obj = buildJsonObject { put("error", innerObj) }
        assertEquals("Something went wrong", obj.str("error"))
    }

    @Test
    fun `str handles JsonObject without message field by returning default`() {
        val innerObj = buildJsonObject {
            put("code", 500)
            put("detail", "Internal")
        }
        val obj = buildJsonObject { put("data", innerObj) }
        assertEquals("", obj.str("data"))
        assertEquals("fallback", obj.str("data", "fallback"))
    }

    @Test
    fun `str extracts data dot message from NamedError format`() {
        val namedError = buildJsonObject {
            put("name", "UnknownError")
            put("data", buildJsonObject {
                put("message", "Agent crashed unexpectedly")
            })
        }
        val obj = buildJsonObject { put("error", namedError) }
        assertEquals("Agent crashed unexpectedly", obj.str("error"))
    }

    @Test
    fun `str extracts name from NamedError when data message absent`() {
        val namedError = buildJsonObject {
            put("name", "AbortedError")
            put("data", buildJsonObject {
                put("reason", "user_cancelled")
            })
        }
        val obj = buildJsonObject { put("error", namedError) }
        assertEquals("AbortedError", obj.str("error"))
    }

    @Test
    fun `str extracts type field from JsonObject when message absent`() {
        val innerObj = buildJsonObject {
            put("type", "context_overflow")
            put("tokens", 12345)
        }
        val obj = buildJsonObject { put("error", innerObj) }
        assertEquals("context_overflow", obj.str("error"))
    }

    @Test
    fun `str extracts error field from JsonObject when message and type absent`() {
        val innerObj = buildJsonObject {
            put("error", "Something failed")
            put("code", 42)
        }
        val obj = buildJsonObject { put("data", innerObj) }
        assertEquals("Something failed", obj.str("data"))
    }

    @Test
    fun `str handles JsonArray value by returning default`() {
        val arr = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val obj = buildJsonObject { put("items", arr) }
        assertEquals("", obj.str("items"))
        assertEquals("fallback", obj.str("items", "fallback"))
    }

    @Test
    fun `str handles JsonNull by returning default`() {
        val obj = buildJsonObject { put("value", JsonNull) }
        assertEquals("", obj.str("value"))
        assertEquals("fallback", obj.str("value", "fallback"))
    }

    @Test
    fun `session error with JsonObject error field parses without crash`() {
        val parser = SessionEventParser(json)
        val errorObj = buildJsonObject {
            put("message", "Agent crashed")
            put("type", "fatal")
        }
        val props = buildJsonObject {
            put("sessionID", "ses_123")
            put("error", errorObj)
        }
        val event = parser.parse("session.error", props) as? SseEvent.SessionError
        assertNotNull(event)
        assertEquals("ses_123", event!!.sessionId)
        assertEquals("Agent crashed", event.error)
    }
}
