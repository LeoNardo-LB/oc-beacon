package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task4 deliverables——client-only 转录 fold 钉死（契约 ②，mod28:59-172 同构）。
 *
 * 映射依据（app 侧模型无 surfaceOp/isError 字段，按 transcript 最接近语义）：
 * - isError：DshEventMapper.mapToolResult 以 data.error/message.error 判错——
 *   有错 → ToolState.Error，无错 → ToolState.Completed。故「成功」≈ Completed。
 * - surfaceOp==='append'：未建模。服务端唯一 tool/result 发射点恒 append
 *   （agent-loop appendToolResult:302-318、session 关闭器 :540-553）；
 *   「非 append」（replacement copy）在 app 转录中不可表达——
 *   本测试以 Pending/Running（结果未到达，web 定义中同样不进 produced）覆盖
 *   可表达子集，偏差见任务报告。
 */
class TurnDeliverablesTest {

    // ---------- fixture ----------

    private fun writeArgs(path: String = "src/A.kt", content: String = "body") = buildJsonObject {
        put("file_path", path)
        put("content", content)
    }

    private fun editArgs(
        path: String = "src/A.kt",
        old: String = "old",
        new: String = "new",
        replaceAll: kotlinx.serialization.json.JsonElement? = null,
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "file_path" to JsonPrimitive(path),
            "old_string" to JsonPrimitive(old),
            "new_string" to JsonPrimitive(new),
        )
        replaceAll?.let { map["replace_all"] = it }
        return map
    }

    private fun editorArgs(
        path: String = "src/B.kt",
        command: String = "create",
        extras: Map<String, kotlinx.serialization.json.JsonElement> = mapOf("file_text" to JsonPrimitive("x")),
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "path" to JsonPrimitive(path),
            "command" to JsonPrimitive(command),
        )
        map.putAll(extras)
        return map
    }

    private fun toolPart(
        name: String,
        input: Map<String, kotlinx.serialization.json.JsonElement>,
        state: ToolState = ToolState.Completed(input = input),
        callId: String = "c_$name",
    ) = Part.Tool(id = callId, tool = name, callId = callId, state = state)

    private fun assistantMsg(id: String, vararg parts: Part) = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s1",
            time = TimeInfo(created = 1L, completed = 2L), parentId = "",
        ),
        parts = parts.toList(),
    )

    // ---------- mutationPath：写类工具 × 参数判定矩阵（mod28:59-93） ----------

    @Test
    fun `write with content and file_path yields path`() {
        assertEquals(
            "src/A.kt",
            mutationPath("write", writeArgs().toMap()),
        )
    }

    @Test
    fun `write without content arg is not a mutation`() {
        val args = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "file_path" to JsonPrimitive("src/A.kt"),
        )
        assertNull(mutationPath("write", args))
    }

    @Test
    fun `write with non-string content is not a mutation`() {
        val args = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "file_path" to JsonPrimitive("src/A.kt"),
            "content" to JsonPrimitive(42),
        )
        assertNull(mutationPath("write", args))
    }

    @Test
    fun `blank path preserves nothing`() {
        assertNull(mutationPath("write", writeArgs(path = "   ").toMap()))
        assertNull(mutationPath("edit", editArgs(path = "")))
    }

    @Test
    fun `path keeps exact spelling including surrounding spaces`() {
        assertEquals(" a.md ", mutationPath("write", writeArgs(path = " a.md ").toMap()))
    }

    @Test
    fun `edit with valid args yields path`() {
        assertEquals("src/A.kt", mutationPath("edit", editArgs()))
    }

    @Test
    fun `edit with boolean replace_all is valid`() {
        assertEquals(
            "src/A.kt",
            mutationPath("edit", editArgs(replaceAll = JsonPrimitive(true))),
        )
    }

    @Test
    fun `edit rejects empty old_string missing new_string identical strings and non-bool replace_all`() {
        assertNull("空 old_string 不算", mutationPath("edit", editArgs(old = "")))
        assertNull("old==new 不算", mutationPath("edit", editArgs(old = "same", new = "same")))
        assertNull("缺 new_string 不算", mutationPath(
            "edit",
            mapOf(
                "file_path" to JsonPrimitive("a"),
                "old_string" to JsonPrimitive("o"),
            ),
        ))
        assertNull("非 bool replace_all 不算", mutationPath("edit", editArgs(replaceAll = JsonPrimitive("yes"))))
    }

    @Test
    fun `str_replace_editor create requires file_text`() {
        assertEquals("src/B.kt", mutationPath("str_replace_editor", editorArgs()))
        assertNull(
            mutationPath("str_replace_editor", editorArgs(extras = emptyMap())),
        )
    }

    @Test
    fun `str_replace_editor str_replace requires non-empty old_str`() {
        assertEquals(
            "src/B.kt",
            mutationPath(
                "str_replace_editor",
                editorArgs(
                    command = "str_replace",
                    extras = mapOf("old_str" to JsonPrimitive("o")),
                ),
            ),
        )
        assertNull(
            mutationPath(
                "str_replace_editor",
                editorArgs(command = "str_replace", extras = mapOf("old_str" to JsonPrimitive(""))),
            ),
        )
    }

    @Test
    fun `str_replace_editor insert requires non-negative integer line and new_str`() {
        val valid = editorArgs(
            command = "insert",
            extras = mapOf("insert_line" to JsonPrimitive(0), "new_str" to JsonPrimitive("n")),
        )
        assertEquals("src/B.kt", mutationPath("str_replace_editor", valid))
        assertNull(
            "负行号",
            mutationPath(
                "str_replace_editor",
                editorArgs(
                    command = "insert",
                    extras = mapOf("insert_line" to JsonPrimitive(-1), "new_str" to JsonPrimitive("n")),
                ),
            ),
        )
        assertNull(
            "非整数行号",
            mutationPath(
                "str_replace_editor",
                editorArgs(
                    command = "insert",
                    extras = mapOf("insert_line" to JsonPrimitive(1.5), "new_str" to JsonPrimitive("n")),
                ),
            ),
        )
        assertNull(
            "缺 new_str",
            mutationPath(
                "str_replace_editor",
                editorArgs(command = "insert", extras = mapOf("insert_line" to JsonPrimitive(3))),
            ),
        )
    }

    @Test
    fun `str_replace_editor read-only command and unknown tools are not mutations`() {
        assertNull(
            mutationPath("str_replace_editor", editorArgs(command = "view", extras = emptyMap())),
        )
        assertNull(mutationPath("read", mapOf("file_path" to JsonPrimitive("a.kt"))))
        assertNull(mutationPath("bash", mapOf("command" to JsonPrimitive("ls"))))
    }

    // ---------- 结果状态矩阵：成功 / 失败 / 未完结 ----------

    @Test
    fun `only settled successful results count as produced`() {
        val args = writeArgs().toMap()
        val ok = producedFilesFromTools(
            listOf(toolPart("write", args, state = ToolState.Completed(input = args))),
        )
        assertEquals(listOf("src/A.kt"), ok)

        val failed = producedFilesFromTools(
            listOf(
                toolPart("write", args, state = ToolState.Error(input = args, error = "denied")),
            ),
        )
        assertEquals(emptyList<String>(), failed)

        val pending = producedFilesFromTools(
            listOf(toolPart("write", args, state = ToolState.Pending(input = args))),
        )
        assertEquals(emptyList<String>(), pending)

        val running = producedFilesFromTools(
            listOf(toolPart("write", args, state = ToolState.Running(input = args))),
        )
        assertEquals(emptyList<String>(), running)
    }

    // ---------- fold：首见序去重 + 消息序遍历 ----------

    @Test
    fun `produced paths keep first-seen order and dedupe exact spelling`() {
        val a = writeArgs(path = "a.md").toMap()
        val b = writeArgs(path = "b.md").toMap()
        val aAgain = editArgs(path = "a.md")
        val out = producedFilesFromTools(
            listOf(
                toolPart("write", a),
                toolPart("write", b),
                toolPart("edit", aAgain),
            ),
        )
        assertEquals(listOf("a.md", "b.md"), out)
    }

    @Test
    fun `same basename different spelling stays two entries`() {
        val abs = writeArgs(path = "/tmp/x/a.md").toMap()
        val rel = writeArgs(path = "a.md").toMap()
        val out = producedFilesFromTools(listOf(toolPart("write", abs), toolPart("write", rel)))
        assertEquals(listOf("/tmp/x/a.md", "a.md"), out)
    }

    @Test
    fun `turnProducedFiles walks messages in visual order across tool hosts`() {
        val a = writeArgs(path = "a.md").toMap()
        val b = editArgs(path = "dir/b.md")
        val out = turnProducedFiles(
            listOf(
                assistantMsg("dsh-call-1", toolPart("write", a, callId = "c1")),
                assistantMsg("seq-2", Part.Text(id = "p", sessionId = "s1", messageId = "seq-2", text = "hi")),
                assistantMsg("dsh-call-3", toolPart("edit", b, callId = "c3")),
            ),
        )
        assertEquals(listOf("a.md", "dir/b.md"), out)
    }

    @Test
    fun `turnProducedFiles tolerates null turn messages`() {
        assertEquals(emptyList<String>(), turnProducedFiles(null))
    }

    // ---------- #398：服务器声明交付（deliverables/presented）并入折法 ----------

    private fun presentedPart(vararg files: Pair<String, String?>) = Part.Deliverables(
        id = "dsh-deliverables-c1",
        sessionId = "s1",
        messageId = "dsh-call-c1",
        presented = files.map { Part.Deliverables.PresentedFile(path = it.first, description = it.second) },
    )

    @Test
    fun `server presented files lead the row and merge with produced paths`() {
        val args = writeArgs(path = "src/A.kt").toMap()
        val out = turnProducedFiles(
            listOf(
                assistantMsg(
                    "dsh-call-c1",
                    toolPart("write", args, callId = "c1"),
                    // 同宿主（mapper 把 Deliverables 挂在 present 工具卡消息上）
                    presentedPart("/tmp/report.md" to "报告", "src/A.kt" to null),
                ),
            ),
        )
        // 服务器权威声明在前；与 produced 精确拼写去重（src/A.kt 不重复）
        assertEquals(listOf("/tmp/report.md", "src/A.kt"), out)
    }

    @Test
    fun `presented files dedupe exact spelling and drop blank paths`() {
        val out = turnProducedFiles(
            listOf(assistantMsg("dsh-call-c1", presentedPart("/a.md" to null, " /a.md " to null, "/a.md" to "again"))),
        )
        // 精确拼写去重（" /a.md " 与 "/a.md" 是两条）；空描述无碍
        assertEquals(listOf("/a.md", " /a.md "), out)
    }

    @Test
    fun `presentedOnlyTurn yields files without any tool call`() {
        assertEquals(
            listOf("/tmp/x.md"),
            turnProducedFiles(listOf(assistantMsg("dsh-call-c1", presentedPart("/tmp/x.md" to null)))),
        )
    }
}
