package dev.leonardo.octether.data.api

import dev.leonardo.octether.data.dto.common.*
import dev.leonardo.octether.data.dto.request.*
import dev.leonardo.octether.data.dto.response.*
import dev.leonardo.octether.domain.model.ToolRef
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
 * Characterization tests for domain API DTO serialization/deserialization.
 *
 * These tests lock in the EXISTING serialization contract for DTOs that are NOT
 * already covered by SerializationTest.
 *
 * Phase 0 safety net: if any of these break, the refactoring broke the API contract.
 */
class DtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ============ ServerPaths ============

    @Test
    fun `ServerPaths round-trip`() {
        val paths = ServerPaths(
            home = "/home/user",
            state = "/home/user/.local/state/opencode",
            config = "/home/user/.config/opencode",
            worktree = "/home/user/project",
            directory = "/home/user/project"
        )
        val encoded = json.encodeToString(ServerPaths.serializer(), paths)
        val decoded = json.decodeFromString(ServerPaths.serializer(), encoded)
        assertEquals("/home/user", decoded.home)
        assertEquals("/home/user/.local/state/opencode", decoded.state)
        assertEquals("/home/user/.config/opencode", decoded.config)
        assertEquals("/home/user/project", decoded.worktree)
        assertEquals("/home/user/project", decoded.directory)
    }

    @Test
    fun `ServerPaths with all defaults`() {
        val jsonStr = """{}"""
        val paths = json.decodeFromString(ServerPaths.serializer(), jsonStr)
        assertEquals("", paths.home)
        assertEquals("", paths.state)
        assertEquals("", paths.config)
        assertEquals("", paths.worktree)
        assertEquals("", paths.directory)
    }

    @Test
    fun `ServerPaths partial deserialization`() {
        val jsonStr = """{"home": "/root", "worktree": "/app"}"""
        val paths = json.decodeFromString(ServerPaths.serializer(), jsonStr)
        assertEquals("/root", paths.home)
        assertEquals("", paths.state)
        assertEquals("/app", paths.worktree)
    }

    // ============ ShellRequest ============

    @Test
    fun `ShellRequest round-trip with model`() {
        val req = ShellRequest(
            agent = "coder",
            model = ModelSelection(providerId = "openai", modelId = "gpt-4o"),
            command = "ls -la"
        )
        val encoded = json.encodeToString(ShellRequest.serializer(), req)
        val decoded = json.decodeFromString(ShellRequest.serializer(), encoded)
        assertEquals("coder", decoded.agent)
        assertEquals("openai", decoded.model!!.providerId)
        assertEquals("gpt-4o", decoded.model!!.modelId)
        assertEquals("ls -la", decoded.command)
    }

    @Test
    fun `ShellRequest round-trip without model`() {
        val req = ShellRequest(agent = "coder", command = "pwd")
        val encoded = json.encodeToString(ShellRequest.serializer(), req)
        val decoded = json.decodeFromString(ShellRequest.serializer(), encoded)
        assertEquals("coder", decoded.agent)
        assertNull(decoded.model)
        assertEquals("pwd", decoded.command)
    }

    @Test
    fun `ShellRequest serializes model with providerID and modelID`() {
        val req = ShellRequest(
            agent = "coder",
            model = ModelSelection(providerId = "anthropic", modelId = "claude-3"),
            command = "echo hi"
        )
        val encoded = json.encodeToString(ShellRequest.serializer(), req)
        assertTrue("Should contain providerID", encoded.contains("providerID"))
        assertTrue("Should contain modelID", encoded.contains("modelID"))
        assertFalse("Should NOT contain providerId", encoded.contains("\"providerId\""))
        assertFalse("Should NOT contain modelId", encoded.contains("\"modelId\""))
    }

    // ============ PtyCreateRequest ============

    @Test
    fun `PtyCreateRequest round-trip with all fields`() {
        val req = PtyCreateRequest(title = "My Terminal", cwd = "/home/user")
        val encoded = json.encodeToString(PtyCreateRequest.serializer(), req)
        val decoded = json.decodeFromString(PtyCreateRequest.serializer(), encoded)
        assertEquals("My Terminal", decoded.title)
        assertEquals("/home/user", decoded.cwd)
    }

    @Test
    fun `PtyCreateRequest defaults are null`() {
        val req = PtyCreateRequest()
        val encoded = json.encodeToString(PtyCreateRequest.serializer(), req)
        // With encodeDefaults = true, nulls should be emitted
        val decoded = json.decodeFromString(PtyCreateRequest.serializer(), encoded)
        assertNull(decoded.title)
        assertNull(decoded.cwd)
    }

    // ============ PtyUpdateRequest ============

    @Test
    fun `PtyUpdateRequest round-trip with size`() {
        val req = PtyUpdateRequest(
            title = "Renamed",
            size = PtySize(rows = 40, cols = 120)
        )
        val encoded = json.encodeToString(PtyUpdateRequest.serializer(), req)
        val decoded = json.decodeFromString(PtyUpdateRequest.serializer(), encoded)
        assertEquals("Renamed", decoded.title)
        assertNotNull(decoded.size)
        assertEquals(40, decoded.size!!.rows)
        assertEquals(120, decoded.size!!.cols)
    }

    @Test
    fun `PtyUpdateRequest with only title`() {
        val req = PtyUpdateRequest(title = "New Title")
        val encoded = json.encodeToString(PtyUpdateRequest.serializer(), req)
        val decoded = json.decodeFromString(PtyUpdateRequest.serializer(), encoded)
        assertEquals("New Title", decoded.title)
        assertNull(decoded.size)
    }

    // ============ PtySize ============

    @Test
    fun `PtySize round-trip`() {
        val size = PtySize(rows = 24, cols = 80)
        val encoded = json.encodeToString(PtySize.serializer(), size)
        val decoded = json.decodeFromString(PtySize.serializer(), encoded)
        assertEquals(24, decoded.rows)
        assertEquals(80, decoded.cols)
    }

    // ============ OutputFormat ============

    @Test
    fun `OutputFormat round-trip with schema`() {
        val fmt = OutputFormat(type = "json_schema", schema = "{\"type\":\"object\"}")
        val encoded = json.encodeToString(OutputFormat.serializer(), fmt)
        val decoded = json.decodeFromString(OutputFormat.serializer(), encoded)
        assertEquals("json_schema", decoded.type)
        assertEquals("{\"type\":\"object\"}", decoded.schema)
    }

    @Test
    fun `OutputFormat with null schema`() {
        val fmt = OutputFormat(type = "text")
        val encoded = json.encodeToString(OutputFormat.serializer(), fmt)
        val decoded = json.decodeFromString(OutputFormat.serializer(), encoded)
        assertEquals("text", decoded.type)
        assertNull(decoded.schema)
    }

    // ============ SearchMatchDto ============

    @Test
    fun `SearchMatchDto round-trip`() {
        val match = SearchMatchDto(
            path = "src/Main.kt",
            lines = "fun main()",
            lineNumber = 42,
            absoluteOffset = 1024
        )
        val encoded = json.encodeToString(SearchMatchDto.serializer(), match)
        val decoded = json.decodeFromString(SearchMatchDto.serializer(), encoded)
        assertEquals("src/Main.kt", decoded.path)
        assertEquals("fun main()", decoded.lines)
        assertEquals(42, decoded.lineNumber)
        assertEquals(1024, decoded.absoluteOffset)
    }

    // ============ ProviderCatalogResponse ============

    @Test
    fun `ProviderCatalogResponse round-trip`() {
        val resp = ProviderCatalogResponse(
            all = listOf(ProviderInfo(id = "openai", name = "OpenAI")),
            default = mapOf("providerID" to "openai", "modelID" to "gpt-4o"),
            connected = listOf("openai")
        )
        val encoded = json.encodeToString(ProviderCatalogResponse.serializer(), resp)
        val decoded = json.decodeFromString(ProviderCatalogResponse.serializer(), encoded)
        assertEquals(1, decoded.all.size)
        assertEquals("openai", decoded.all[0].id)
        assertEquals("openai", decoded.default["providerID"])
        assertEquals(1, decoded.connected.size)
        assertTrue(decoded.connected.contains("openai"))
    }

    @Test
    fun `ProviderCatalogResponse with defaults`() {
        val jsonStr = """{"all":[]}"""
        val resp = json.decodeFromString(ProviderCatalogResponse.serializer(), jsonStr)
        assertTrue(resp.all.isEmpty())
        assertTrue(resp.default.isEmpty())
        assertTrue(resp.connected.isEmpty())
    }

    // ============ ProviderAuthMethod ============

    @Test
    fun `ProviderAuthMethod round-trip`() {
        val method = ProviderAuthMethod(type = "oauth", label = "OAuth 2.0")
        val encoded = json.encodeToString(ProviderAuthMethod.serializer(), method)
        val decoded = json.decodeFromString(ProviderAuthMethod.serializer(), encoded)
        assertEquals("oauth", decoded.type)
        assertEquals("OAuth 2.0", decoded.label)
    }

    // ============ ProviderOauthAuthorization ============

    @Test
    fun `ProviderOauthAuthorization round-trip full`() {
        val auth = ProviderOauthAuthorization(
            url = "https://accounts.google.com/o/oauth2",
            method = "oauth",
            instructions = "Visit the URL and enter the code"
        )
        val encoded = json.encodeToString(ProviderOauthAuthorization.serializer(), auth)
        val decoded = json.decodeFromString(ProviderOauthAuthorization.serializer(), encoded)
        assertEquals("https://accounts.google.com/o/oauth2", decoded.url)
        assertEquals("oauth", decoded.method)
        assertEquals("Visit the URL and enter the code", decoded.instructions)
    }

    @Test
    fun `ProviderOauthAuthorization default values`() {
        val auth = ProviderOauthAuthorization()
        assertEquals("", auth.url)
        assertEquals("none", auth.method)
        assertEquals("", auth.instructions)
    }

    @Test
    fun `ProviderOauthAuthorization deserializes from empty object`() {
        val jsonStr = """{}"""
        val auth = json.decodeFromString(ProviderOauthAuthorization.serializer(), jsonStr)
        assertEquals("", auth.url)
        assertEquals("none", auth.method)
        assertEquals("", auth.instructions)
    }

    // ============ ProviderInfo (full fields) ============

    @Test
    fun `ProviderInfo round-trip with all fields`() {
        val info = ProviderInfo(
            id = "openai",
            name = "OpenAI",
            source = "builtin",
            env = listOf("OPENAI_API_KEY"),
            key = "sk-xxx",
            models = emptyMap()
        )
        val encoded = json.encodeToString(ProviderInfo.serializer(), info)
        val decoded = json.decodeFromString(ProviderInfo.serializer(), encoded)
        assertEquals("openai", decoded.id)
        assertEquals("OpenAI", decoded.name)
        assertEquals("builtin", decoded.source)
        assertEquals(1, decoded.env.size)
        assertEquals("OPENAI_API_KEY", decoded.env[0])
        assertEquals("sk-xxx", decoded.key)
    }

    @Test
    fun `ProviderInfo with defaults`() {
        val jsonStr = """{"id": "ollama", "name": "Ollama"}"""
        val info = json.decodeFromString(ProviderInfo.serializer(), jsonStr)
        assertEquals("ollama", info.id)
        assertEquals("", info.source)
        assertTrue(info.env.isEmpty())
        assertNull(info.key)
        assertTrue(info.models.isEmpty())
    }

    // ============ ModelCost with CacheCost ============

    @Test
    fun `ModelCost round-trip with cache`() {
        val cost = ModelCost(
            input = 0.01,
            output = 0.03,
            cache = ModelCost.CacheCost(read = 0.005, write = 0.01)
        )
        val encoded = json.encodeToString(ModelCost.serializer(), cost)
        val decoded = json.decodeFromString(ModelCost.serializer(), encoded)
        assertEquals(0.01, decoded.input, 0.001)
        assertEquals(0.03, decoded.output, 0.001)
        assertNotNull(decoded.cache)
        assertEquals(0.005, decoded.cache!!.read, 0.001)
        assertEquals(0.01, decoded.cache!!.write, 0.001)
    }

    @Test
    fun `ModelCost with defaults`() {
        val jsonStr = """{}"""
        val cost = json.decodeFromString(ModelCost.serializer(), jsonStr)
        assertEquals(0.0, cost.input, 0.001)
        assertEquals(0.0, cost.output, 0.001)
        assertNull(cost.cache)
    }

    // ============ ModelCapabilities ============

    @Test
    fun `ModelCapabilities round-trip`() {
        val caps = ModelCapabilities(
            temperature = true,
            reasoning = true,
            attachment = false,
            toolcall = true
        )
        val encoded = json.encodeToString(ModelCapabilities.serializer(), caps)
        val decoded = json.decodeFromString(ModelCapabilities.serializer(), encoded)
        assertTrue(decoded.temperature)
        assertTrue(decoded.reasoning)
        assertFalse(decoded.attachment)
        assertTrue(decoded.toolcall)
    }

    @Test
    fun `ModelCapabilities with defaults`() {
        val jsonStr = """{}"""
        val caps = json.decodeFromString(ModelCapabilities.serializer(), jsonStr)
        assertFalse(caps.temperature)
        assertFalse(caps.reasoning)
        assertFalse(caps.attachment)
        assertFalse(caps.toolcall)
    }

    // ============ ModelLimit ============

    @Test
    fun `ModelLimit round-trip with all fields`() {
        val limit = ModelLimit(context = 128000, input = 100000, output = 4096)
        val encoded = json.encodeToString(ModelLimit.serializer(), limit)
        val decoded = json.decodeFromString(ModelLimit.serializer(), encoded)
        assertEquals(128000, decoded.context)
        assertEquals(100000, decoded.input)
        assertEquals(4096, decoded.output)
    }

    @Test
    fun `ModelLimit with null input`() {
        val jsonStr = """{"context": 32000, "output": 2048}"""
        val limit = json.decodeFromString(ModelLimit.serializer(), jsonStr)
        assertEquals(32000, limit.context)
        assertNull(limit.input)
        assertEquals(2048, limit.output)
    }

    // ============ SessionStatusInfo ============

    @Test
    fun `SessionStatusInfo round-trip`() {
        val info = SessionStatusInfo(
            id = "sess_1",
            status = mapOf("state" to "busy", "agent" to "coder")
        )
        val encoded = json.encodeToString(SessionStatusInfo.serializer(), info)
        val decoded = json.decodeFromString(SessionStatusInfo.serializer(), encoded)
        assertEquals("sess_1", decoded.id)
        assertEquals("busy", decoded.status["state"])
        assertEquals("coder", decoded.status["agent"])
    }

    @Test
    fun `SessionStatusInfo with defaults`() {
        val jsonStr = """{}"""
        val info = json.decodeFromString(SessionStatusInfo.serializer(), jsonStr)
        assertEquals("", info.id)
        assertTrue(info.status.isEmpty())
    }

    // ============ ShellInfo ============

    @Test
    fun `ShellInfo round-trip`() {
        val info = ShellInfo(path = "/bin/bash", name = "bash", acceptable = true)
        val encoded = json.encodeToString(ShellInfo.serializer(), info)
        val decoded = json.decodeFromString(ShellInfo.serializer(), encoded)
        assertEquals("/bin/bash", decoded.path)
        assertEquals("bash", decoded.name)
        assertTrue(decoded.acceptable)
    }

    @Test
    fun `ShellInfo with acceptable false`() {
        val jsonStr = """{"path": "/usr/bin/zsh", "name": "zsh", "acceptable": false}"""
        val info = json.decodeFromString(ShellInfo.serializer(), jsonStr)
        assertFalse(info.acceptable)
    }

    // ============ SymbolInfo ============

    @Test
    fun `SymbolInfo round-trip with all fields`() {
        val sym = SymbolInfo(
            name = "MainActivity",
            kind = "class",
            path = "src/MainActivity.kt",
            line = 10,
            language = "kotlin"
        )
        val encoded = json.encodeToString(SymbolInfo.serializer(), sym)
        val decoded = json.decodeFromString(SymbolInfo.serializer(), encoded)
        assertEquals("MainActivity", decoded.name)
        assertEquals("class", decoded.kind)
        assertEquals("src/MainActivity.kt", decoded.path)
        assertEquals(10, decoded.line)
        assertEquals("kotlin", decoded.language)
    }

    @Test
    fun `SymbolInfo with defaults`() {
        val jsonStr = """{"name": "foo"}"""
        val sym = json.decodeFromString(SymbolInfo.serializer(), jsonStr)
        assertEquals("foo", sym.name)
        assertEquals("", sym.kind)
        assertEquals("", sym.path)
        assertNull(sym.line)
        assertNull(sym.language)
    }

    // ============ FileStatusInfo ============

    @Test
    fun `FileStatusInfo round-trip`() {
        val info = FileStatusInfo(path = "src/Main.kt", status = "modified", staged = true)
        val encoded = json.encodeToString(FileStatusInfo.serializer(), info)
        val decoded = json.decodeFromString(FileStatusInfo.serializer(), encoded)
        assertEquals("src/Main.kt", decoded.path)
        assertEquals("modified", decoded.status)
        assertTrue(decoded.staged)
    }

    @Test
    fun `FileStatusInfo with defaults`() {
        val jsonStr = """{"path": "a.txt", "status": "untracked"}"""
        val info = json.decodeFromString(FileStatusInfo.serializer(), jsonStr)
        assertFalse(info.staged)
    }

    // ============ PromptPart with URL type ============

    @Test
    fun `PromptPart URL type round-trip`() {
        val part = PromptPart(
            type = "url",
            url = "https://example.com/api/data",
            text = "API Reference"
        )
        val encoded = json.encodeToString(PromptPart.serializer(), part)
        val decoded = json.decodeFromString(PromptPart.serializer(), encoded)
        assertEquals("url", decoded.type)
        assertEquals("https://example.com/api/data", decoded.url)
        assertEquals("API Reference", decoded.text)
    }

    // ============ PromptRequest with format, system, noReply ============

    @Test
    fun `PromptRequest with optional fields round-trip`() {
        val req = PromptRequest(
            parts = listOf(PromptPart(type = "text", text = "Analyze this")),
            model = ModelSelection(providerId = "openai", modelId = "gpt-4o"),
            agent = "coder",
            variant = "v2",
            format = OutputFormat(type = "json_schema", schema = "{}"),
            system = "You are a helpful assistant.",
            noReply = true
        )
        val encoded = json.encodeToString(PromptRequest.serializer(), req)
        val decoded = json.decodeFromString(PromptRequest.serializer(), encoded)
        assertEquals(1, decoded.parts.size)
        assertEquals("Analyze this", decoded.parts[0].text)
        assertEquals("openai", decoded.model!!.providerId)
        assertEquals("coder", decoded.agent)
        assertEquals("v2", decoded.variant)
        assertNotNull(decoded.format)
        assertEquals("json_schema", decoded.format!!.type)
        assertEquals("You are a helpful assistant.", decoded.system)
        assertTrue(decoded.noReply!!)
    }

    // ============ PermissionRequest with metadata ============

    @Test
    fun `PermissionRequest with metadata round-trip`() {
        val req = PermissionRequest(
            id = "perm_meta",
            sessionId = "s1",
            permission = "write",
            patterns = listOf("/tmp/*"),
            metadata = mapOf("file" to kotlinx.serialization.json.JsonPrimitive("test.txt"))
        )
        val encoded = json.encodeToString(PermissionRequest.serializer(), req)
        val decoded = json.decodeFromString(PermissionRequest.serializer(), encoded)
        assertEquals("perm_meta", decoded.id)
        assertEquals("write", decoded.permission)
        assertNotNull(decoded.metadata)
        assertEquals("test.txt", decoded.metadata!!["file"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PermissionRequest deserializes without metadata`() {
        val jsonStr = """{"id":"p1","sessionID":"s1","permission":"read"}"""
        val req = json.decodeFromString(PermissionRequest.serializer(), jsonStr)
        assertNull(req.metadata)
        assertTrue(req.patterns.isEmpty())
    }

    // ============ QuestionRequest with tool ============

    @Test
    fun `QuestionRequest with tool round-trip`() {
        val req = QuestionRequest(
            id = "q_tool",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Confirm?",
                    header = "Confirm",
                    options = listOf(QuestionOption(label = "Yes", description = "Proceed")),
                    multiple = false,
                    custom = false
                )
            ),
            tool = ToolRef(messageId = "msg_1", callId = "call_1")
        )
        val encoded = json.encodeToString(QuestionRequest.serializer(), req)
        val decoded = json.decodeFromString(QuestionRequest.serializer(), encoded)
        assertEquals("q_tool", decoded.id)
        assertNotNull(decoded.tool)
        assertEquals("msg_1", decoded.tool!!.messageId)
        assertEquals("call_1", decoded.tool!!.callId)
        assertFalse(decoded.questions[0].custom)
    }

    // ============ ProviderModel with variants ============

    @Test
    fun `ProviderModel with variants round-trip`() {
        val model = ProviderModel(
            id = "gpt-4o",
            providerId = "openai",
            name = "GPT-4o",
            family = "gpt-4",
            status = "active",
            variants = mapOf("chat" to kotlinx.serialization.json.JsonPrimitive("gpt-4o-chat"))
        )
        val encoded = json.encodeToString(ProviderModel.serializer(), model)
        val decoded = json.decodeFromString(ProviderModel.serializer(), encoded)
        assertEquals("gpt-4o", decoded.id)
        assertEquals("openai", decoded.providerId)
        assertEquals("GPT-4o", decoded.name)
        assertEquals("gpt-4", decoded.family)
        assertEquals("active", decoded.status)
        assertNotNull(decoded.variants)
    }
}
