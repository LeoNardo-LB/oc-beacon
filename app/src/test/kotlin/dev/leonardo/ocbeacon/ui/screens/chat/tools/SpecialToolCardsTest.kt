package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task5 工具卡分派与 skill 行模型（契约 ③；mod32 SkillRow.js 同构）。
 *
 * - question 行：OpenCode 工具名 'question' + DSH 工具名 'ask_user_question'
 *   （dsh-tool-ask-user index.js:15-16 恒名）双名分派到既有 Asked 形态。
 * - skill 行：工具名 'skill'（dsh-tool-skill index.js:59-65，args={name}），
 *   result=指令全文 → 折叠卡（默认收起，mod32:107 语义）。
 */
class SpecialToolCardsTest {

    // ---------- specialToolCardKind：工具名分派 ----------

    @Test
    fun `openCode question and dsh ask_user_question dispatch to question card`() {
        assertEquals(SpecialToolCardKind.Question, specialToolCardKind("question"))
        assertEquals(SpecialToolCardKind.Question, specialToolCardKind("ask_user_question"))
    }

    @Test
    fun `skill dispatches to skill card`() {
        assertEquals(SpecialToolCardKind.Skill, specialToolCardKind("skill"))
    }

    @Test
    fun `generic tools dispatch to null`() {
        assertNull(specialToolCardKind("write"))
        assertNull(specialToolCardKind("edit"))
        assertNull(specialToolCardKind("bash"))
        assertNull(specialToolCardKind(""))
    }

    // ---------- skillRowModel：mod32 skillRowModel 同构 ----------

    private fun skillPart(state: ToolState, nameArg: JsonElement? = JsonPrimitive("code-review"), callId: String = "c9"): Part.Tool {
        val input = if (nameArg == null) emptyMap() else mapOf("name" to nameArg)
        val stateWithInput = when (state) {
            is ToolState.Completed -> state.copy(input = input)
            is ToolState.Error -> state.copy(input = input)
            is ToolState.Pending -> state.copy(input = input)
            is ToolState.Running -> state.copy(input = input)
        }
        return Part.Tool(id = callId, tool = "skill", callId = callId, state = stateWithInput)
    }

    @Test
    fun `completed skill yields ok state with full instructions as output`() {
        val m = skillRowModel(skillPart(ToolState.Completed(output = "line1\nline2 instructions")))
        assertEquals("code-review", m.name)
        assertEquals(SkillCardState.OK, m.state)
        assertEquals("line1\nline2 instructions", m.output)
        assertNull(m.errorSummary)
    }

    @Test
    fun `error skill yields error state with first-line summary and expandable output`() {
        val m = skillRowModel(skillPart(ToolState.Error(error = "skill not found\nstack")))
        assertEquals(SkillCardState.ERROR, m.state)
        assertEquals("skill not found", m.errorSummary)
        assertEquals("skill not found\nstack", m.output)
    }

    @Test
    fun `interrupted error maps to stopped state`() {
        val m = skillRowModel(skillPart(ToolState.Error(error = "Tool execution interrupted")))
        assertEquals(SkillCardState.STOPPED, m.state)
        assertNull(m.errorSummary)
    }

    @Test
    fun `unsettled skill maps to running state with null output`() {
        assertEquals(SkillCardState.RUNNING, skillRowModel(skillPart(ToolState.Pending())).state)
        assertEquals(SkillCardState.RUNNING, skillRowModel(skillPart(ToolState.Running())).state)
        assertNull(skillRowModel(skillPart(ToolState.Pending())).output)
    }

    @Test
    fun `blank output on completed maps to null output`() {
        assertNull(skillRowModel(skillPart(ToolState.Completed(output = ""))).output)
    }

    @Test
    fun `skill name falls back to callId and collapses to first line`() {
        assertEquals(
            "c7",
            skillRowModel(skillPart(ToolState.Completed(output = "x"), nameArg = null, callId = "c7")).name,
        )
        assertEquals(
            "multi",
            skillRowModel(skillPart(ToolState.Completed(output = "x"), nameArg = JsonPrimitive("multi\nsecond"))).name,
        )
    }
}
