package dev.leonardo.ocbeacon.domain.model

import dev.leonardo.ocbeacon.data.dto.response.SearchMatchDto
import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.data.dto.response.SubmatchDto
import dev.leonardo.ocbeacon.data.dto.response.VcsBranchDto
import dev.leonardo.ocbeacon.data.update.GitHubReleaseDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire 兼容矩阵（Tier C-1 / #201，2026-08-24 建）。
 *
 * 锁定 V1/V2 线上契约的**字段名全集**：@SerialName 值（sessionID 等大写 ID 后缀、
 * snake_case 族）与隐式名（属性名即 wire 名）。这是「标识符改名永不改变 wire 形状」
 * 的机器锁——任何 @SerialName 值变动/丢失、或无注解字段被改名，这里先红，
 * 编译器不会替你发现（字符串注解不参与编译检查）。
 *
 * 依据：CONTEXT.md 总则（wire 名 = API 原拼写；域内标识符 camelCase 两层并存）。
 * 改这里任何一个期望名 = 改协议 = 必须先确认 OpenCode server 已变。
 */
class WireCompatMatrixTest {

    private fun names(s: KSerializer<*>): List<String> =
        (0 until s.descriptor.elementsCount).map { s.descriptor.getElementName(it) }

    private fun json() = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ============ Session 族 ============

    @Test
    fun session_wireNames_locked() {
        assertEquals(
            listOf(
                "id", "slug", "projectID", "directory", "parentID", "title", "version",
                "time", "summary", "share", "permission", "revert", "workspaceID",
                "path", "cost", "tokens", "agent", "model", "permissions",
            ),
            names(Session.serializer()),
        )
    }

    @Test
    fun session_nested_wireNames_locked() {
        assertEquals(listOf("created", "updated", "compacting", "archived"), names(Session.Time.serializer()))
        assertEquals(listOf("additions", "deletions", "files", "diffs"), names(Session.Summary.serializer()))
        assertEquals(listOf("url"), names(Session.Share.serializer()))
        assertEquals(listOf("messageID", "partID", "snapshot", "diff"), names(Session.Revert.serializer()))
        assertEquals(listOf("permission", "pattern", "action"), names(Session.PermissionRule.serializer()))
        assertEquals(listOf("input", "output", "reasoning", "cache"), names(Session.SessionTokens.serializer()))
        assertEquals(listOf("read", "write"), names(Session.SessionTokens.Cache.serializer()))
        assertEquals(listOf("id", "providerID", "variant"), names(Session.SessionModel.serializer()))
    }

    // ============ Part 族（PartSerializer 分发 + 各类型字段名） ============

    @Test
    fun part_wireNames_locked() {
        assertEquals(
            listOf("id", "sessionID", "messageID", "text", "synthetic", "ignored", "time", "metadata"),
            names(Part.Text.serializer()),
        )
        assertEquals(
            listOf("id", "sessionID", "messageID", "text", "time", "metadata"),
            names(Part.Reasoning.serializer()),
        )
        assertEquals(
            listOf("id", "sessionID", "messageID", "callID", "tool", "state", "metadata"),
            names(Part.Tool.serializer()),
        )
        assertEquals(
            listOf("id", "sessionID", "messageID", "shellID", "command", "status", "exit", "output", "truncated", "time", "metadata"),
            names(Part.Shell.serializer()),
        )
        assertEquals(listOf("id", "sessionID", "messageID", "snapshot"), names(Part.StepStart.serializer()))
        assertEquals(
            listOf("id", "sessionID", "messageID", "reason", "snapshot", "cost", "tokens"),
            names(Part.StepFinish.serializer()),
        )
        assertEquals(
            listOf("id", "sessionID", "messageID", "mime", "filename", "url", "source"),
            names(Part.File.serializer()),
        )
        assertEquals(listOf("id", "sessionID", "messageID", "snapshot"), names(Part.Snapshot.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "hash", "files"), names(Part.Patch.serializer()))
        assertEquals(
            listOf("id", "sessionID", "messageID", "prompt", "description", "agent", "model", "command"),
            names(Part.Subtask.serializer()),
        )
        assertEquals(listOf("providerID", "modelID"), names(Part.Subtask.Model.serializer()))
        // #219（2026-08-25）：+failed——失败压缩消息标记（失败分割线渲染）。
        assertEquals(listOf("id", "sessionID", "messageID", "auto", "summary", "failed"), names(Part.Compaction.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "attempt", "error", "time"), names(Part.Retry.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "name", "source"), names(Part.Agent.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "message"), names(Part.Permission.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "question"), names(Part.Question.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID", "reason"), names(Part.Abort.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID"), names(Part.SessionTurn.serializer()))
        assertEquals(listOf("id", "sessionID", "messageID"), names(Part.Unknown.serializer()))
    }

    @Test
    fun part_polymorphic_roundTrip_typeDiscriminatorAndKeysPreserved() {
        // 注意：type 是**输入侧判别字段**——Part 子类无 type 构造属性，输出 JSON 不含
        // type（F01 注释：默认值省略导致缓存 payload 无 type，靠字段推断回读）。
        // 本测试锁两件事：①输入 type 正确分发到运行时类型；②ID 键（wire 大写形态）
        // 在 decode→encode 回环后保真。
        val payloads = mapOf(
            "text" to ("""{"type":"text","id":"p1","sessionID":"s1","messageID":"m1","text":"hi"}""" to Part.Text::class),
            "reasoning" to ("""{"type":"reasoning","id":"p2","sessionID":"s1","messageID":"m1","text":"think"}""" to Part.Reasoning::class),
            "tool" to ("""{"type":"tool","id":"p3","sessionID":"s1","messageID":"m1","callID":"c1","tool":"bash","state":{"status":"running","input":{}}}""" to Part.Tool::class),
            "shell" to ("""{"type":"shell","id":"p4","sessionID":"s1","messageID":"m1","shellID":"sh1","command":"ls","status":"running"}""" to Part.Shell::class),
            "subtask" to ("""{"type":"subtask","id":"p5","sessionID":"s1","messageID":"m1","prompt":"go"}""" to Part.Subtask::class),
            "patch" to ("""{"type":"patch","id":"p6","sessionID":"s1","messageID":"m1","hash":"abc","files":[]}""" to Part.Patch::class),
            "permission" to ("""{"type":"permission","id":"p7","sessionID":"s1","messageID":"m1","message":"allow?"}""" to Part.Permission::class),
            "question" to ("""{"type":"question","id":"p8","sessionID":"s1","messageID":"m1","question":"which?"}""" to Part.Question::class),
            "abort" to ("""{"type":"abort","id":"p9","sessionID":"s1","messageID":"m1","reason":"user"}""" to Part.Abort::class),
        )
        val json = json()
        for ((expectedType, payloadAndClass) in payloads) {
            val (payload, expectedClass) = payloadAndClass
            val decoded = json.decodeFromString(Part.serializer(), payload)
            assertEquals("input type must dispatch to $expectedType", expectedClass, decoded::class)
            val encoded = json.encodeToString(Part.serializer(), decoded)
            val obj = json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }
            for (wireKey in listOf("id", "sessionID", "messageID")) {
                assertTrue("$wireKey must survive round-trip for $expectedType", obj.containsKey(wireKey))
            }
        }
    }

    @Test
    fun part_cachedPayload_inferenceRoundTrip_f01Branches() {
        // #200 F01：缓存回环（无 type 字段）按顶层字段推断——Permission/Question 分支的
        // wire 形状同样不得漂移
        val json = json()
        val perm = json.decodeFromString(Part.serializer(), """{"id":"p1","messageID":"m1","sessionID":"s1","message":"m"}""")
        assertTrue(perm is Part.Permission)
        val q = json.decodeFromString(Part.serializer(), """{"id":"p2","messageID":"m1","sessionID":"s1","question":"q"}""")
        assertTrue(q is Part.Question)
    }

    // ============ SessionNextEvent 代表（ID 大写族词汇锁） ============

    @Test
    fun sessionNextEvent_idVocabulary_locked() {
        // 全部 27 变体的 @SerialName 词汇域 = {sessionID, messageID, partID, callID,
        // providerID, modelID, timestamp}——以代表变体锁全集
        val vocab = mutableSetOf<String>()
        fun harvest(s: KSerializer<*>) { vocab.addAll(names(s)) }
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.ModelSwitched.serializer())
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.ToolCalled.serializer())
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.ToolInputDelta.serializer())
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.TextDelta.serializer())
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated.serializer())
        harvest(dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Retried.serializer())
        for (w in listOf("sessionID", "messageID", "partID", "callID", "providerID", "modelID")) {
            assertTrue("SessionNextEvent wire vocabulary must contain $w", vocab.contains(w))
        }
        // 反向锁：词汇域不得混入 camelCase 漂移形态
        for (drift in listOf("sessionId", "messageId", "partId", "callId", "providerId", "modelId")) {
            assertTrue("SessionNextEvent wire names must NOT drift to $drift", !vocab.contains(drift))
        }
    }

    @Test
    fun toolRef_wireNames_locked() {
        assertEquals(listOf("messageID", "callID"), names(ToolRef.serializer()))
    }

    // ============ snake_case 族（server 原样形状） ============

    @Test
    fun snakeCase_wireNames_locked() {
        assertEquals(
            listOf("disabled_providers", "enabled_providers", "model", "small_model", "default_agent", "mcp"),
            names(ServerConfigResponse.serializer()),
        )
        assertEquals(
            listOf("path", "lines", "line_number", "absolute_offset", "submatches"),
            names(SearchMatchDto.serializer()),
        )
        assertEquals(listOf("match", "start", "end"), names(SubmatchDto.serializer()))
        assertEquals(listOf("branch", "default_branch"), names(VcsBranchDto.serializer()))
        assertEquals(
            listOf("tag_name", "html_url", "body", "draft", "prerelease"),
            names(GitHubReleaseDto.serializer()),
        )
    }

    @Test
    fun snakeCase_endToEnd_decodeUsesLockedNames() {
        // 用锁定的 wire 名解码真实形状样本（/find 响应）——名字与解码双锁
        val json = json()
        val sample = """
            [{"path":{"text":"a.kt"},"lines":{"text":"fun x()"},"line_number":3,
              "absolute_offset":42,"submatches":[{"match":{"text":"x"},"start":4,"end":5}]}]
        """.trimIndent()
        val decoded = json.decodeFromString(ListSerializer(SearchMatchDto.serializer()), sample)
        assertEquals(3, decoded[0].lineNumber)
        assertEquals(42, decoded[0].absoluteOffset)
        assertEquals(1, decoded[0].submatches.size)
    }
}
