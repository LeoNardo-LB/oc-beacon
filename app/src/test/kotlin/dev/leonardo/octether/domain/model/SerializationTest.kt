package dev.leonardo.octether.domain.model

import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.data.dto.common.ModelSelection as DtoModelSelection
import dev.leonardo.octether.data.dto.request.PromptPart as DtoPromptPart
import dev.leonardo.octether.data.dto.request.PromptRequest
import dev.leonardo.octether.data.dto.request.QuestionReplyBody
import dev.leonardo.octether.data.dto.request.ServerConfigPatch
import dev.leonardo.octether.data.dto.response.ModelCapabilities
import dev.leonardo.octether.data.dto.response.ModelCost
import dev.leonardo.octether.data.dto.response.ModelLimit
import dev.leonardo.octether.data.dto.response.PermissionRequest
import dev.leonardo.octether.data.dto.response.ProviderInfo as DtoProviderInfo
import dev.leonardo.octether.data.dto.response.ProviderModel
import dev.leonardo.octether.data.dto.response.ProvidersResponse as DtoProvidersResponse
import dev.leonardo.octether.data.dto.response.PtyInfo
import dev.leonardo.octether.data.dto.response.QuestionInfo
import dev.leonardo.octether.data.dto.response.QuestionOption
import dev.leonardo.octether.data.dto.response.QuestionRequest
import dev.leonardo.octether.data.dto.response.ServerConfigResponse
import dev.leonardo.octether.data.dto.response.FileContentDto
import dev.leonardo.octether.data.dto.response.FileNodeDto
import dev.leonardo.octether.data.dto.response.SkillInfo
import dev.leonardo.octether.data.dto.response.TodoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for domain model JSON serialization/deserialization.
 *
 * These tests lock in the EXISTING serialization contract so that refactoring
 * cannot accidentally change JSON field names, order, or structure.
 *
 * Phase 0 safety net: if any of these break, the refactoring broke the API contract.
 */
class SerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ============ Session ============

    @Test
    fun `Session round-trip with SerialName fields`() {
        val session = Session(
            id = "sess_abc123",
            slug = "my-session",
            projectId = "proj_001",
            directory = "/home/user/project",
            parentId = "sess_parent",
            title = "Test Session",
            version = "1",
            time = Session.Time(created = 1700000000000, updated = 1700000100000),
            workspaceId = "ws_001",
            path = "/home/user/project",
            cost = 0.05,
            agent = "coder",
            model = Session.SessionModel(id = "gpt-4o", providerId = "openai", variant = "chat")
        )

        val encoded = json.encodeToString(Session.serializer(), session)
        val decoded = json.decodeFromString(Session.serializer(), encoded)

        assertEquals(session.id, decoded.id)
        assertEquals(session.slug, decoded.slug)
        assertEquals(session.projectId, decoded.projectId)
        assertEquals(session.directory, decoded.directory)
        assertEquals(session.parentId, decoded.parentId)
        assertEquals(session.title, decoded.title)
        assertEquals(session.version, decoded.version)
        assertEquals(session.time.created, decoded.time.created)
        assertEquals(session.time.updated, decoded.time.updated)
        assertEquals(session.workspaceId, decoded.workspaceId)
        assertEquals(session.path, decoded.path)
        assertEquals(session.cost, decoded.cost)
        assertEquals(session.agent, decoded.agent)
        assertEquals(session.model?.id, decoded.model?.id)
        assertEquals(session.model?.providerId, decoded.model?.providerId)
        assertEquals(session.model?.variant, decoded.model?.variant)
    }

    @Test
    fun `Session serializes projectID not projectId`() {
        val session = Session(
            id = "s1",
            time = Session.Time(created = 1000, updated = 2000),
            projectId = "proj_x"
        )
        val encoded = json.encodeToString(Session.serializer(), session)
        assertTrue("Should contain projectID (uppercase D)", encoded.contains("projectID"))
        assertFalse("Should NOT contain projectId (lowercase d)", encoded.contains("projectId"))
    }

    @Test
    fun `Session serializes workspaceID not workspaceId`() {
        val session = Session(
            id = "s1",
            time = Session.Time(created = 1000, updated = 2000),
            workspaceId = "ws_123"
        )
        val encoded = json.encodeToString(Session.serializer(), session)
        assertTrue(encoded.contains("workspaceID"))
        assertFalse(encoded.contains("workspaceId"))
    }

    @Test
    fun `Session serializes parentID not parentId`() {
        val session = Session(
            id = "s1",
            time = Session.Time(created = 1000, updated = 2000),
            parentId = "parent_1"
        )
        val encoded = json.encodeToString(Session.serializer(), session)
        assertTrue(encoded.contains("parentID"))
        assertFalse(encoded.contains("parentId"))
    }

    @Test
    fun `Session with Revert deserializes correctly`() {
        val jsonStr = """{
            "id": "s1",
            "time": {"created": 1000, "updated": 2000},
            "revert": {
                "messageID": "msg_1",
                "partID": "part_1",
                "snapshot": "snap_abc"
            }
        }"""
        val session = json.decodeFromString(Session.serializer(), jsonStr)
        assertNotNull(session.revert)
        assertEquals("msg_1", session.revert!!.messageId)
        assertEquals("part_1", session.revert!!.partId)
        assertEquals("snap_abc", session.revert!!.snapshot)
    }

    @Test
    fun `Session with summary deserializes`() {
        val jsonStr = """{
            "id": "s1",
            "time": {"created": 1000, "updated": 2000},
            "summary": {
                "additions": 10,
                "deletions": 3,
                "files": 5
            }
        }"""
        val session = json.decodeFromString(Session.serializer(), jsonStr)
        assertNotNull(session.summary)
        assertEquals(10, session.summary!!.additions)
        assertEquals(3, session.summary!!.deletions)
        assertEquals(5, session.summary!!.files)
    }

    // ============ Message ============

    @Test
    fun `Message User deserializes via role field`() {
        val jsonStr = """{
            "id": "msg_1",
            "sessionID": "sess_1",
            "role": "user",
            "time": {"created": 1700000000000},
            "model": {
                "providerID": "openai",
                "modelID": "gpt-4o"
            }
        }"""
        val message = json.decodeFromString(Message.serializer(), jsonStr)
        assertTrue("Should be User message", message is Message.User)
        assertEquals("msg_1", message.id)
        assertEquals("sess_1", message.sessionId)
        assertEquals("user", message.role)
        assertEquals("openai", (message as Message.User).model?.providerId)
        assertEquals("gpt-4o", message.model?.modelId)
    }

    @Test
    fun `Message User serializes sessionID not sessionId`() {
        val user = Message.User(
            id = "msg_1",
            sessionId = "sess_1",
            time = TimeInfo(created = 1000)
        )
        val encoded = json.encodeToString(Message.serializer(), user)
        assertTrue("Should contain sessionID (uppercase D)", encoded.contains("sessionID"))
        assertFalse("Should NOT contain sessionId", encoded.contains("\"sessionId\""))
    }

    @Test
    fun `Message User with format deserializes`() {
        val jsonStr = """{
            "id": "msg_1",
            "sessionID": "sess_1",
            "role": "user",
            "time": {"created": 1700000000000},
            "format": {
                "type": "json_schema",
                "retryCount": 3
            }
        }"""
        val message = json.decodeFromString(Message.serializer(), jsonStr) as Message.User
        assertNotNull(message.format)
        assertEquals("json_schema", message.format!!.type)
        assertEquals(3, message.format!!.retryCount)
    }

    @Test
    fun `Message Assistant deserializes via role field`() {
        val jsonStr = """{
            "id": "msg_2",
            "sessionID": "sess_1",
            "role": "assistant",
            "time": {"created": 1700000000000, "completed": 1700000060000},
            "parentID": "msg_1",
            "modelID": "gpt-4o",
            "providerID": "openai",
            "agent": "coder",
            "mode": "code",
            "cost": 0.05,
            "tokens": {
                "input": 100,
                "output": 50,
                "total": 150,
                "reasoning": 30,
                "cache": {"read": 20, "write": 10}
            },
            "finish": "stop"
        }"""
        val message = json.decodeFromString(Message.serializer(), jsonStr)
        assertTrue("Should be Assistant message", message is Message.Assistant)
        val assistant = message as Message.Assistant
        assertEquals("msg_2", assistant.id)
        assertEquals("sess_1", assistant.sessionId)
        assertEquals("msg_1", assistant.parentId)
        assertEquals("gpt-4o", assistant.modelId)
        assertEquals("openai", assistant.providerId)
        assertEquals("coder", assistant.agent)
        assertEquals("code", assistant.mode)
        assertEquals(0.05, assistant.cost!!, 0.001)
        assertEquals("stop", assistant.finish)
        assertNotNull(assistant.tokens)
        assertEquals(100, assistant.tokens!!.input)
        assertEquals(50, assistant.tokens!!.output)
        assertEquals(150, assistant.tokens!!.total)
        assertEquals(30, assistant.tokens!!.reasoning)
        assertEquals(20, assistant.tokens!!.cache.read)
        assertEquals(10, assistant.tokens!!.cache.write)
    }

    @Test
    fun `Message Assistant serializes parentID, modelID, providerID`() {
        val assistant = Message.Assistant(
            id = "msg_2",
            sessionId = "sess_1",
            time = TimeInfo(created = 1000),
            parentId = "msg_1",
            modelId = "gpt-4o",
            providerId = "openai"
        )
        val encoded = json.encodeToString(Message.serializer(), assistant)
        assertTrue(encoded.contains("parentID"))
        assertTrue(encoded.contains("modelID"))
        assertTrue(encoded.contains("providerID"))
        assertFalse(encoded.contains("parentId"))
        assertFalse(encoded.contains("modelId"))
        assertFalse(encoded.contains("providerId"))
    }

    @Test
    fun `Message Assistant with error deserializes`() {
        val jsonStr = """{
            "id": "msg_err",
            "sessionID": "sess_1",
            "role": "assistant",
            "time": {"created": 1700000000000},
            "parentID": "msg_1",
            "error": {
                "name": "RateLimitError",
                "data": {"message": "Too many requests"}
            }
        }"""
        val assistant = json.decodeFromString(Message.serializer(), jsonStr) as Message.Assistant
        assertNotNull(assistant.error)
        assertEquals("RateLimitError", assistant.error!!.name)
        assertEquals("Too many requests", assistant.error!!.message)
    }

    @Test
    fun `Message Assistant round-trip`() {
        val assistant = Message.Assistant(
            id = "msg_round",
            sessionId = "sess_1",
            time = TimeInfo(created = 1000, completed = 2000),
            parentId = "msg_1",
            modelId = "claude-3",
            providerId = "anthropic",
            path = Message.Assistant.PathInfo(cwd = "/home", root = "/"),
            cost = 0.01,
            tokens = Message.Assistant.Tokens(input = 10, output = 5, total = 15, reasoning = 2),
            finish = "end_turn",
            variant = "v2",
            summary = true
        )
        val encoded = json.encodeToString(Message.serializer(), assistant)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertTrue(decoded is Message.Assistant)
        val d = decoded as Message.Assistant
        assertEquals(assistant.id, d.id)
        assertEquals(assistant.parentId, d.parentId)
        assertEquals(assistant.path?.cwd, d.path?.cwd)
        assertEquals(assistant.variant, d.variant)
        assertEquals(assistant.summary, d.summary)
    }

    // ============ Part ============

    @Test
    fun `Part Text deserializes via type field`() {
        val jsonStr = """{
            "id": "part_1",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "text",
            "text": "Hello world",
            "synthetic": false,
            "ignored": true
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Text)
        assertEquals("Hello world", (part as Part.Text).text)
        assertEquals(false, part.synthetic)
        assertEquals(true, part.ignored)
    }

    @Test
    fun `Part Text serializes sessionID and messageID`() {
        val text = Part.Text(
            id = "p1",
            sessionId = "s1",
            messageId = "m1",
            text = "hi"
        )
        val encoded = json.encodeToString(Part.serializer(), text)
        assertTrue(encoded.contains("sessionID"))
        assertTrue(encoded.contains("messageID"))
        assertFalse(encoded.contains("\"sessionId\""))
        assertFalse(encoded.contains("\"messageId\""))
    }

    @Test
    fun `Part Tool deserializes with ToolState`() {
        val jsonStr = """{
            "id": "part_tool",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "tool",
            "callID": "call_001",
            "tool": "Read",
            "state": {
                "status": "completed",
                "input": {"path": "/tmp/file.txt"},
                "output": "file contents here",
                "title": "Read /tmp/file.txt"
            }
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Tool)
        val tool = part as Part.Tool
        assertEquals("call_001", tool.callId)
        assertEquals("Read", tool.tool)
        assertTrue(tool.state is ToolState.Completed)
        val completed = tool.state as ToolState.Completed
        assertEquals("file contents here", completed.output)
        assertEquals("Read /tmp/file.txt", completed.title)
    }

    @Test
    fun `Part Tool serializes callID not callId`() {
        val tool = Part.Tool(
            id = "p1",
            sessionId = "s1",
            messageId = "m1",
            callId = "call_1",
            tool = "Bash",
            state = ToolState.Pending()
        )
        val encoded = json.encodeToString(Part.serializer(), tool)
        assertTrue(encoded.contains("callID"))
        assertFalse(encoded.contains("callId"))
    }

    @Test
    fun `Part Reasoning deserializes`() {
        val jsonStr = """{
            "id": "part_r",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "reasoning",
            "text": "Let me think about this..."
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Reasoning)
        assertEquals("Let me think about this...", (part as Part.Reasoning).text)
    }

    @Test
    fun `Part StepStart deserializes`() {
        val jsonStr = """{
            "id": "part_ss",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "step-start",
            "snapshot": "snap_123"
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.StepStart)
        assertEquals("snap_123", (part as Part.StepStart).snapshot)
    }

    @Test
    fun `Part StepFinish deserializes`() {
        val jsonStr = """{
            "id": "part_sf",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "step-finish",
            "reason": "done",
            "cost": 0.03,
            "tokens": {"input": 50, "output": 20}
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.StepFinish)
        val sf = part as Part.StepFinish
        assertEquals("done", sf.reason)
        assertEquals(0.03, sf.cost!!, 0.001)
        assertEquals(50, sf.tokens!!.input)
        assertEquals(20, sf.tokens!!.output)
    }

    @Test
    fun `Part File deserializes`() {
        val jsonStr = """{
            "id": "part_f",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "file",
            "mime": "image/png",
            "filename": "screenshot.png",
            "url": "data:image/png;base64,abc"
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.File)
        val f = part as Part.File
        assertEquals("image/png", f.mime)
        assertEquals("screenshot.png", f.filename)
        assertEquals("data:image/png;base64,abc", f.url)
    }

    @Test
    fun `Part Snapshot deserializes`() {
        val jsonStr = """{
            "id": "part_snap",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "snapshot",
            "snapshot": "abc123"
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Snapshot)
        assertEquals("abc123", (part as Part.Snapshot).snapshot)
    }

    @Test
    fun `Part Subtask deserializes`() {
        val jsonStr = """{
            "id": "part_sub",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "subtask",
            "prompt": "Fix the bug",
            "agent": "debugger",
            "model": {
                "providerID": "anthropic",
                "modelID": "claude-3"
            }
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Subtask)
        val sub = part as Part.Subtask
        assertEquals("Fix the bug", sub.prompt)
        assertEquals("debugger", sub.agent)
        assertEquals("anthropic", sub.model?.providerId)
        assertEquals("claude-3", sub.model?.modelId)
    }

    @Test
    fun `Part Subtask serializes modelID and providerID`() {
        val sub = Part.Subtask(
            id = "p1",
            sessionId = "s1",
            messageId = "m1",
            model = Part.Subtask.Model(providerId = "openai", modelId = "gpt-4")
        )
        val encoded = json.encodeToString(Part.serializer(), sub)
        assertTrue(encoded.contains("providerID"))
        assertTrue(encoded.contains("modelID"))
    }

    @Test
    fun `Part Retry deserializes`() {
        val jsonStr = """{
            "id": "part_retry",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "retry",
            "attempt": 2,
            "error": {"message": "timeout"},
            "time": {"created": 1700000000000}
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Retry)
        val retry = part as Part.Retry
        assertEquals(2, retry.attempt)
        assertEquals("timeout", retry.errorMessage)
        assertNotNull(retry.time)
    }

    @Test
    fun `Part Compaction deserializes`() {
        val jsonStr = """{
            "id": "part_comp",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "compaction",
            "auto": true
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Compaction)
        assertTrue((part as Part.Compaction).auto)
    }

    @Test
    fun `Part Agent deserializes`() {
        val jsonStr = """{
            "id": "part_agent",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "agent",
            "name": "plan",
            "source": {"detail": "planning phase"}
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Agent)
        assertEquals("plan", (part as Part.Agent).name)
    }

    @Test
    fun `Part Patch deserializes`() {
        val jsonStr = """{
            "id": "part_patch",
            "sessionID": "sess_1",
            "messageID": "msg_1",
            "type": "patch",
            "hash": "abc123",
            "files": ["src/Main.kt", "src/Util.kt"]
        }"""
        val part = json.decodeFromString(Part.serializer(), jsonStr)
        assertTrue(part is Part.Patch)
        val patch = part as Part.Patch
        assertEquals("abc123", patch.hash)
        assertEquals(2, patch.files.size)
    }

    // ============ ToolState ============

    @Test
    fun `ToolState Pending deserializes`() {
        val jsonStr = """{"status": "pending", "input": {"path": "/tmp"}}"""
        val state = json.decodeFromString(ToolState.serializer(), jsonStr)
        assertTrue(state is ToolState.Pending)
        // input is Map<String, JsonElement>, so values are JsonPrimitive
        assertEquals("/tmp", (state as ToolState.Pending).input["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ToolState Running deserializes`() {
        val jsonStr = """{"status": "running", "input": {}, "title": "Reading file", "time": {"start": 1700000000000}}"""
        val state = json.decodeFromString(ToolState.serializer(), jsonStr)
        assertTrue(state is ToolState.Running)
        val running = state as ToolState.Running
        assertEquals("Reading file", running.title)
        assertEquals(1700000000000, running.time?.start)
    }

    @Test
    fun `ToolState Completed deserializes with attachments`() {
        val jsonStr = """{
            "status": "completed",
            "input": {},
            "output": "done",
            "title": "Write",
            "time": {"start": 1000, "end": 2000},
            "attachments": [{"type": "image", "data": "base64..."}]
        }"""
        val state = json.decodeFromString(ToolState.serializer(), jsonStr)
        assertTrue(state is ToolState.Completed)
        val completed = state as ToolState.Completed
        assertEquals("done", completed.output)
        assertEquals(1, completed.attachments?.size)
        assertEquals("image", completed.attachments!![0].type)
    }

    @Test
    fun `ToolState Error deserializes`() {
        val jsonStr = """{"status": "error", "input": {}, "error": "Command failed", "time": {"start": 1000, "end": 2000}}"""
        val state = json.decodeFromString(ToolState.serializer(), jsonStr)
        assertTrue(state is ToolState.Error)
        val err = state as ToolState.Error
        assertEquals("Command failed", err.error)
    }

    @Test
    fun `ToolState round-trip`() {
        // NOTE: ToolState subclasses don't have a `status` property, so encoding
        // ToolState.Completed won't produce a `status` field for polymorphic deserialization.
        // Test with manually constructed JSON that includes the discriminator.
        val jsonStr = """{
            "status": "completed",
            "input": {"path": "/tmp/file"},
            "output": "contents",
            "title": "Read",
            "time": {"start": 1000, "end": 2000}
        }"""
        val decoded = json.decodeFromString(ToolState.serializer(), jsonStr)
        assertTrue(decoded is ToolState.Completed)
        val d = decoded as ToolState.Completed
        assertEquals("contents", d.output)
        assertEquals("Read", d.title)
        assertEquals("/tmp/file", d.input["path"]?.jsonPrimitive?.content)
    }

    // ============ ToolRef ============

    @Test
    fun `ToolRef serializes messageID and callID`() {
        val ref = ToolRef(messageId = "msg_1", callId = "call_1")
        val encoded = json.encodeToString(ToolRef.serializer(), ref)
        assertTrue(encoded.contains("messageID"))
        assertTrue(encoded.contains("callID"))
        assertFalse(encoded.contains("messageId"))
        assertFalse(encoded.contains("callId"))
    }

    @Test
    fun `ToolRef deserializes from uppercase JSON keys`() {
        val jsonStr = """{"messageID": "msg_1", "callID": "call_1"}"""
        val ref = json.decodeFromString(ToolRef.serializer(), jsonStr)
        assertEquals("msg_1", ref.messageId)
        assertEquals("call_1", ref.callId)
    }

    // ============ TimeInfo ============

    @Test
    fun `TimeInfo round-trip`() {
        val ti = TimeInfo(created = 1000, completed = 2000)
        val encoded = json.encodeToString(TimeInfo.serializer(), ti)
        val decoded = json.decodeFromString(TimeInfo.serializer(), encoded)
        assertEquals(ti.created, decoded.created)
        assertEquals(ti.completed, decoded.completed)
    }

    @Test
    fun `TimeInfo with null completed`() {
        val jsonStr = """{"created": 1000}"""
        val ti = json.decodeFromString(TimeInfo.serializer(), jsonStr)
        assertEquals(1000, ti.created)
        assertNull(ti.completed)
    }

    // ============ FileDiff ============

    @Test
    fun `FileDiff round-trip`() {
        val diff = FileDiff(
            file = "src/Main.kt",
            before = "old code",
            after = "new code",
            additions = 5,
            deletions = 2,
            status = "modified"
        )
        val encoded = json.encodeToString(FileDiff.serializer(), diff)
        val decoded = json.decodeFromString(FileDiff.serializer(), encoded)
        assertEquals(diff.file, decoded.file)
        assertEquals(diff.before, decoded.before)
        assertEquals(diff.after, decoded.after)
        assertEquals(diff.additions, decoded.additions)
        assertEquals(diff.deletions, decoded.deletions)
        assertEquals(diff.status, decoded.status)
    }

    @Test
    fun `FileDiff with defaults`() {
        val jsonStr = """{"file": "README.md"}"""
        val diff = json.decodeFromString(FileDiff.serializer(), jsonStr)
        assertEquals("README.md", diff.file)
        assertEquals("", diff.before)
        assertEquals("", diff.after)
        assertEquals(0, diff.additions)
        assertEquals(0, diff.deletions)
        assertNull(diff.status)
    }

    // ============ Project ============

    @Test
    fun `Project round-trip`() {
        val project = Project(
            id = "proj_1",
            worktree = "/home/user/project",
            name = "My Project",
            vcs = "git",
            directory = "/home/user"
        )
        val encoded = json.encodeToString(Project.serializer(), project)
        val decoded = json.decodeFromString(Project.serializer(), encoded)
        assertEquals(project.id, decoded.id)
        assertEquals(project.worktree, decoded.worktree)
        assertEquals(project.name, decoded.name)
        assertEquals(project.vcs, decoded.vcs)
        assertEquals(project.directory, decoded.directory)
    }

    @Test
    fun `Project displayName logic`() {
        assertEquals("explicit", Project(name = "explicit", worktree = "/a/b").displayName)
        assertEquals("b", Project(name = null, worktree = "/a/b").displayName)
        // id.take(8) fallback: needs a non-empty id
        assertEquals("12345678", Project(id = "1234567890", name = null, worktree = "").displayName)
    }

    // ============ MessageWithParts ============

    @Test
    fun `MessageWithParts deserializes from server JSON`() {
        // NOTE: Part subclasses don't have a `type` property, so encoding
        // Part.Text won't produce a `type` field for polymorphic deserialization.
        // We test deserialization from realistic server JSON instead.
        val jsonStr = """{
            "info": {
                "id": "msg_1",
                "sessionID": "sess_1",
                "role": "user",
                "time": {"created": 1000}
            },
            "parts": [
                {
                    "id": "p1",
                    "sessionID": "s1",
                    "messageID": "m1",
                    "type": "text",
                    "text": "hello"
                }
            ]
        }"""
        val mwp = json.decodeFromString(MessageWithParts.serializer(), jsonStr)
        assertTrue(mwp.info is Message.User)
        assertEquals("msg_1", mwp.info.id)
        assertEquals(1, mwp.parts.size)
        assertTrue("part should be Text", mwp.parts[0] is Part.Text)
        assertEquals("hello", (mwp.parts[0] as Part.Text).text)
    }

    // ============ SseEvent data classes (individual serialization) ============

    @Test
    fun `SseEvent PermissionAsked round-trip`() {
        val event = SseEvent.PermissionAsked(
            id = "perm_1",
            sessionId = "sess_1",
            permission = "write",
            patterns = listOf("/tmp/*"),
            metadata = mapOf("key" to "value"),
            always = true,
            tool = ToolRef(messageId = "msg_1", callId = "call_1")
        )
        val encoded = json.encodeToString(SseEvent.PermissionAsked.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.PermissionAsked.serializer(), encoded)
        assertEquals(event.id, decoded.id)
        assertEquals(event.sessionId, decoded.sessionId)
        assertEquals(event.permission, decoded.permission)
        assertEquals(event.patterns, decoded.patterns)
        assertEquals(event.always, decoded.always)
        assertNotNull(decoded.tool)
        assertEquals("msg_1", decoded.tool!!.messageId)
        assertEquals("call_1", decoded.tool!!.callId)
    }

    @Test
    fun `SseEvent SessionDiff round-trip`() {
        val event = SseEvent.SessionDiff(
            sessionId = "sess_1",
            diff = listOf(FileDiff(file = "a.kt", additions = 1, status = "modified"))
        )
        val encoded = json.encodeToString(SseEvent.SessionDiff.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.SessionDiff.serializer(), encoded)
        assertEquals("sess_1", decoded.sessionId)
        assertEquals(1, decoded.diff.size)
        assertEquals("a.kt", decoded.diff[0].file)
    }

    @Test
    fun `SseEvent SessionError with nullable sessionId`() {
        val event = SseEvent.SessionError(sessionId = null, error = "Something broke")
        val encoded = json.encodeToString(SseEvent.SessionError.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.SessionError.serializer(), encoded)
        assertNull(decoded.sessionId)
        assertEquals("Something broke", decoded.error)
    }

    @Test
    fun `SseEvent MessagePartDelta round-trip`() {
        val event = SseEvent.MessagePartDelta(
            sessionId = "s1",
            messageId = "m1",
            partId = "p1",
            field = "text",
            delta = "chunk"
        )
        val encoded = json.encodeToString(SseEvent.MessagePartDelta.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.MessagePartDelta.serializer(), encoded)
        assertEquals(event.sessionId, decoded.sessionId)
        assertEquals(event.messageId, decoded.messageId)
        assertEquals(event.partId, decoded.partId)
        assertEquals(event.field, decoded.field)
        assertEquals(event.delta, decoded.delta)
    }

    @Test
    fun `SseEvent QuestionAsked with nested types round-trip`() {
        val event = SseEvent.QuestionAsked(
            id = "q_1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Choice",
                    question = "Which option?",
                    multiple = true,
                    custom = false,
                    options = listOf(
                        SseEvent.QuestionAsked.Option(label = "A", description = "Option A"),
                        SseEvent.QuestionAsked.Option(label = "B", description = "Option B")
                    )
                )
            ),
            tool = ToolRef(messageId = "msg_1", callId = "call_1")
        )
        val encoded = json.encodeToString(SseEvent.QuestionAsked.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.QuestionAsked.serializer(), encoded)
        assertEquals(1, decoded.questions.size)
        assertEquals("Choice", decoded.questions[0].header)
        assertTrue(decoded.questions[0].multiple)
        assertFalse(decoded.questions[0].custom)
        assertEquals(2, decoded.questions[0].options.size)
        assertNotNull(decoded.tool)
    }

    @Test
    fun `SseEvent TodoUpdated round-trip`() {
        val event = SseEvent.TodoUpdated(
            sessionId = "s1",
            todos = listOf(
                SseEvent.TodoUpdated.Todo(content = "Fix bug", status = "pending", priority = "high")
            )
        )
        val encoded = json.encodeToString(SseEvent.TodoUpdated.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.TodoUpdated.serializer(), encoded)
        assertEquals(1, decoded.todos.size)
        assertEquals("Fix bug", decoded.todos[0].content)
        assertEquals("high", decoded.todos[0].priority)
    }

    @Test
    fun `SseEvent PtyCreated with defaults round-trip`() {
        val event = SseEvent.PtyCreated(id = "pty_1")
        val encoded = json.encodeToString(SseEvent.PtyCreated.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.PtyCreated.serializer(), encoded)
        assertEquals("pty_1", decoded.id)
        assertEquals("", decoded.title)
        assertEquals("", decoded.command)
        assertEquals("", decoded.cwd)
    }

    @Test
    fun `SseEvent CommandExecuted round-trip`() {
        val event = SseEvent.CommandExecuted(
            name = "/compact",
            sessionId = "s1",
            arguments = "all",
            messageId = "m1"
        )
        val encoded = json.encodeToString(SseEvent.CommandExecuted.serializer(), event)
        val decoded = json.decodeFromString(SseEvent.CommandExecuted.serializer(), encoded)
        assertEquals("/compact", decoded.name)
        assertEquals("all", decoded.arguments)
        assertEquals("m1", decoded.messageId)
    }

    // ============ Request/Response DTOs ============

    @Test
    fun `ModelSelection serializes providerID and modelID`() {
        val ms = DtoModelSelection(providerId = "openai", modelId = "gpt-4o")
        val encoded = json.encodeToString(DtoModelSelection.serializer(), ms)
        assertTrue(encoded.contains("providerID"))
        assertTrue(encoded.contains("modelID"))
        assertFalse(encoded.contains("providerId"))
        assertFalse(encoded.contains("modelId"))
    }

    @Test
    fun `PromptRequest round-trip`() {
        val req = PromptRequest(
            parts = listOf(DtoPromptPart(type = "text", text = "Hello")),
            model = DtoModelSelection(providerId = "openai", modelId = "gpt-4o"),
            agent = "coder",
            variant = "v2"
        )
        val encoded = json.encodeToString(PromptRequest.serializer(), req)
        val decoded = json.decodeFromString(PromptRequest.serializer(), encoded)
        assertEquals(1, decoded.parts.size)
        assertEquals("Hello", decoded.parts[0].text)
        assertEquals("openai", decoded.model?.providerId)
        assertEquals("gpt-4o", decoded.model?.modelId)
        assertEquals("coder", decoded.agent)
        assertEquals("v2", decoded.variant)
    }

    @Test
    fun `PromptPart with file attachment round-trip`() {
        val part = DtoPromptPart(
            type = "file",
            path = "/tmp/img.png",
            mime = "image/png",
            filename = "screenshot.png"
        )
        val encoded = json.encodeToString(DtoPromptPart.serializer(), part)
        val decoded = json.decodeFromString(DtoPromptPart.serializer(), encoded)
        assertEquals("file", decoded.type)
        assertEquals("/tmp/img.png", decoded.path)
        assertEquals("image/png", decoded.mime)
        assertEquals("screenshot.png", decoded.filename)
    }

    @Test
    fun `PermissionRequest round-trip`() {
        val req = PermissionRequest(
            id = "perm_1",
            sessionId = "sess_1",
            permission = "write",
            patterns = listOf("/tmp/*"),
            always = null,
            tool = ToolRef(messageId = "msg_1", callId = "call_1")
        )
        val encoded = json.encodeToString(PermissionRequest.serializer(), req)
        val decoded = json.decodeFromString(PermissionRequest.serializer(), encoded)
        assertEquals("perm_1", decoded.id)
        assertEquals("sess_1", decoded.sessionId)
        assertEquals("write", decoded.permission)
        assertEquals(1, decoded.patterns.size)
        assertNotNull(decoded.tool)
    }

    @Test
    fun `PermissionRequest serializes sessionID`() {
        val req = PermissionRequest(id = "p1", sessionId = "s1", permission = "read")
        val encoded = json.encodeToString(PermissionRequest.serializer(), req)
        assertTrue(encoded.contains("sessionID"))
        assertFalse(encoded.contains("\"sessionId\""))
    }

    @Test
    fun `QuestionRequest round-trip`() {
        val req = QuestionRequest(
            id = "q_1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Which model?",
                    header = "Model",
                    options = listOf(QuestionOption(label = "GPT-4", description = "OpenAI")),
                    multiple = false,
                    custom = true
                )
            )
        )
        val encoded = json.encodeToString(QuestionRequest.serializer(), req)
        val decoded = json.decodeFromString(QuestionRequest.serializer(), encoded)
        assertEquals(1, decoded.questions.size)
        assertEquals("Which model?", decoded.questions[0].question)
        assertEquals("GPT-4", decoded.questions[0].options[0].label)
    }

    @Test
    fun `ServerHealth round-trip`() {
        val health = ServerHealth(healthy = true, version = "0.100.0")
        val encoded = json.encodeToString(ServerHealth.serializer(), health)
        val decoded = json.decodeFromString(ServerHealth.serializer(), encoded)
        assertTrue(decoded.healthy)
        assertEquals("0.100.0", decoded.version)
    }

    @Test
    fun `ServerHealth with null version`() {
        val jsonStr = """{"healthy": false}"""
        val health = json.decodeFromString(ServerHealth.serializer(), jsonStr)
        assertFalse(health.healthy)
        assertNull(health.version)
    }

    @Test
    fun `ServerConfigResponse deserializes snake_case fields`() {
        val jsonStr = """{
            "disabled_providers": ["ollama"],
            "enabled_providers": ["openai"],
            "model": "gpt-4o",
            "small_model": "gpt-4o-mini",
            "default_agent": "coder"
        }"""
        val config = json.decodeFromString(ServerConfigResponse.serializer(), jsonStr)
        assertEquals(listOf("ollama"), config.disabledProviders)
        assertEquals(listOf("openai"), config.enabledProviders)
        assertEquals("gpt-4o", config.model)
        assertEquals("gpt-4o-mini", config.smallModel)
        assertEquals("coder", config.defaultAgent)
    }

    @Test
    fun `ServerConfigPatch round-trip`() {
        val patch = ServerConfigPatch(
            disabledProviders = listOf("ollama"),
            model = "claude-3",
            smallModel = "claude-haiku",
            defaultAgent = "plan"
        )
        val encoded = json.encodeToString(ServerConfigPatch.serializer(), patch)
        val decoded = json.decodeFromString(ServerConfigPatch.serializer(), encoded)
        assertEquals(listOf("ollama"), decoded.disabledProviders)
        assertEquals("claude-3", decoded.model)
        assertEquals("claude-haiku", decoded.smallModel)
        assertEquals("plan", decoded.defaultAgent)
    }

    @Test
    fun `ProvidersResponse round-trip`() {
        val resp = DtoProvidersResponse(
            providers = listOf(
                DtoProviderInfo(
                    id = "openai",
                    name = "OpenAI",
                    models = mapOf(
                        "gpt-4o" to ProviderModel(
                            id = "gpt-4o",
                            providerId = "openai",
                            name = "GPT-4o",
                            capabilities = ModelCapabilities(
                                temperature = true, reasoning = true,
                                attachment = true, toolcall = true
                            ),
                            cost = ModelCost(input = 0.01, output = 0.03),
                            limit = ModelLimit(context = 128000, output = 4096)
                        )
                    )
                )
            ),
            default = mapOf("providerID" to "openai", "modelID" to "gpt-4o")
        )
        val encoded = json.encodeToString(DtoProvidersResponse.serializer(), resp)
        val decoded = json.decodeFromString(DtoProvidersResponse.serializer(), encoded)
        assertEquals(1, decoded.providers.size)
        assertEquals("openai", decoded.providers[0].id)
        val model = decoded.providers[0].models["gpt-4o"]!!
        assertEquals("GPT-4o", model.name)
        assertTrue(model.capabilities!!.reasoning)
        assertEquals(128000, model.limit!!.context)
        assertEquals("openai", decoded.default["providerID"])
    }

    @Test
    fun `ProviderModel serializes providerID`() {
        val model = ProviderModel(id = "gpt-4o", providerId = "openai", name = "GPT-4o")
        val encoded = json.encodeToString(ProviderModel.serializer(), model)
        assertTrue(encoded.contains("providerID"))
        assertFalse(encoded.contains("\"providerId\""))
    }

    @Test
    fun `PtyInfo round-trip`() {
        val info = PtyInfo(
            id = "pty_1",
            title = "Terminal",
            command = "/bin/bash",
            args = emptyList(),
            cwd = "/home/user",
            status = "running",
            pid = 12345
        )
        val encoded = json.encodeToString(PtyInfo.serializer(), info)
        val decoded = json.decodeFromString(PtyInfo.serializer(), encoded)
        assertEquals("pty_1", decoded.id)
        assertEquals("/bin/bash", decoded.command)
        assertEquals(12345, decoded.pid)
    }

    @Test
    fun `QuestionReplyBody round-trip`() {
        val body = QuestionReplyBody(answers = listOf(listOf("A", "B"), listOf("C")))
        val encoded = json.encodeToString(QuestionReplyBody.serializer(), body)
        val decoded = json.decodeFromString(QuestionReplyBody.serializer(), encoded)
        assertEquals(2, decoded.answers.size)
        assertEquals(2, decoded.answers[0].size)
        assertEquals("A", decoded.answers[0][0])
    }

    @Test
    fun `Session SessionModel serializes providerID`() {
        val model = Session.SessionModel(id = "gpt-4o", providerId = "openai", variant = "chat")
        val encoded = json.encodeToString(Session.SessionModel.serializer(), model)
        assertTrue(encoded.contains("providerID"))
        assertFalse(encoded.contains("\"providerId\""))
    }

    @Test
    fun `Session PermissionRule round-trip`() {
        val rule = Session.PermissionRule(permission = "write", pattern = "/tmp/*", action = "allow")
        val encoded = json.encodeToString(Session.PermissionRule.serializer(), rule)
        val decoded = json.decodeFromString(Session.PermissionRule.serializer(), encoded)
        assertEquals("write", decoded.permission)
        assertEquals("/tmp/*", decoded.pattern)
        assertEquals("allow", decoded.action)
    }

    @Test
    fun `Session Share round-trip`() {
        val share = Session.Share(url = "https://share.example.com/abc")
        val encoded = json.encodeToString(Session.Share.serializer(), share)
        val decoded = json.decodeFromString(Session.Share.serializer(), encoded)
        assertEquals("https://share.example.com/abc", decoded.url)
    }

    // ============ SessionStatus ============
    // NOTE: SessionStatus has NO custom polymorphic serializer.
    // The sealed class subclasses are individually @Serializable, and the
    // `type` field is used as a discriminator by SseClient.parseEventByType() manually.
    // We test individual subclass serialization here.

    @Test
    fun `SessionStatus Idle serializes and deserializes individually`() {
        val encoded = json.encodeToString(SessionStatus.Idle.serializer(), SessionStatus.Idle)
        // Idle is data object, serializes as {} 
        val decoded = json.decodeFromString(SessionStatus.Idle.serializer(), encoded)
        assertTrue(decoded is SessionStatus.Idle)
    }

    @Test
    fun `SessionStatus Busy serializes and deserializes individually`() {
        val encoded = json.encodeToString(SessionStatus.Busy.serializer(), SessionStatus.Busy)
        val decoded = json.decodeFromString(SessionStatus.Busy.serializer(), encoded)
        assertTrue(decoded is SessionStatus.Busy)
    }

    @Test
    fun `SessionStatus Retry serializes and deserializes individually`() {
        val retry = SessionStatus.Retry(attempt = 3, message = "Rate limited", next = 1700000100000)
        val encoded = json.encodeToString(SessionStatus.Retry.serializer(), retry)
        val decoded = json.decodeFromString(SessionStatus.Retry.serializer(), encoded)
        assertTrue(decoded is SessionStatus.Retry)
        val d = decoded as SessionStatus.Retry
        assertEquals(3, d.attempt)
        assertEquals("Rate limited", d.message)
        assertEquals(1700000100000L, d.next)
    }

    // ============ V2 DTOs ============

    @Test
    fun `SkillInfo round-trip`() {
        val info = SkillInfo(name = "debug", description = "Debug skill", location = "/path/to/skill")
        val encoded = json.encodeToString(SkillInfo.serializer(), info)
        val decoded = json.decodeFromString(SkillInfo.serializer(), encoded)
        assertEquals("debug", decoded.name)
        assertEquals("Debug skill", decoded.description)
    }

    @Test
    fun `TodoItem round-trip`() {
        val item = TodoItem(id = "todo_1", content = "Fix bug", status = "pending", priority = "high")
        val encoded = json.encodeToString(TodoItem.serializer(), item)
        val decoded = json.decodeFromString(TodoItem.serializer(), encoded)
        assertEquals("todo_1", decoded.id)
        assertEquals("Fix bug", decoded.content)
        assertEquals("high", decoded.priority)
    }

    @Test
    fun `FileNodeDto round-trip`() {
        val node = FileNodeDto(name = "Main.kt", path = "/src/Main.kt", type = "file", absolute = "/abs/src/Main.kt", size = 1024)
        val encoded = json.encodeToString(FileNodeDto.serializer(), node)
        val decoded = json.decodeFromString(FileNodeDto.serializer(), encoded)
        assertEquals("Main.kt", decoded.name)
        assertEquals("file", decoded.type)
        assertEquals(1024L, decoded.size)
    }

    @Test
    fun `FileContentDto round-trip`() {
        val content = FileContentDto(type = "text", content = "hello world")
        val encoded = json.encodeToString(FileContentDto.serializer(), content)
        val decoded = json.decodeFromString(FileContentDto.serializer(), encoded)
        assertEquals("text", decoded.type)
        assertEquals("hello world", decoded.content)
    }

    @Test
    fun `AgentInfo round-trip`() {
        val agent = dev.leonardo.octether.data.dto.response.AgentInfo(name = "coder", description = "Code agent", mode = "primary", hidden = false, color = "#00ff00")
        val encoded = json.encodeToString(dev.leonardo.octether.data.dto.response.AgentInfo.serializer(), agent)
        val decoded = json.decodeFromString(dev.leonardo.octether.data.dto.response.AgentInfo.serializer(), encoded)
        assertEquals("coder", decoded.name)
        assertEquals("primary", decoded.mode)
        assertFalse(decoded.hidden)
        assertEquals("#00ff00", decoded.color)
    }

    @Test
    fun `CommandInfo round-trip`() {
        val cmd = dev.leonardo.octether.data.dto.response.CommandInfo(name = "/compact", description = "Compact session", source = "command", hints = listOf("summarize"))
        val encoded = json.encodeToString(dev.leonardo.octether.data.dto.response.CommandInfo.serializer(), cmd)
        val decoded = json.decodeFromString(dev.leonardo.octether.data.dto.response.CommandInfo.serializer(), encoded)
        assertEquals("/compact", decoded.name)
        assertEquals("command", decoded.source)
        assertEquals(1, decoded.hints.size)
    }
}
