package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.runtime.Immutable
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * #311 Task5 工具卡分派 + skill 行模型（契约 ③；mod32 SkillRow.js 同构）。
 * 纯函数，JVM 可测（SpecialToolCardsTest）；PartContent 按此分派渲染。
 */

/** 特殊渲染工具卡类别（按 wire 工具名分派；其余走通用卡/解析器注册表）。 */
enum class SpecialToolCardKind { Question, Skill }

/**
 * 工具名分派：
 * - **question**：OpenCode 内部提问机制（PartContent 既有分支）；
 * - **ask_user_question**：DSH 同语义工具恒名（dsh-tool-ask-user index.js:15-16
 *   name: "ask_user_question"）——双名同走既有 Asked 形态（契约 ③）；
 * - **skill**：DSH skill 加载工具（dsh-tool-skill index.js:59-65，args={name}，
 *   result.content=指令全文）。
 */
internal fun specialToolCardKind(toolName: String): SpecialToolCardKind? = when (toolName) {
    "question", "ask_user_question" -> SpecialToolCardKind.Question
    "skill" -> SpecialToolCardKind.Skill
    else -> null
}

/** skill 行呈现态（mod32 skillRowModel 的 state 推导，不查目录）。 */
enum class SkillCardState { RUNNING, OK, ERROR, STOPPED }

/** skill 行纯投影（@Immutable——直接进 Compose 重组跳过链）。 */
@Immutable
data class SkillRowModel(
    /** 折叠行摘要名：args.name 首行；缺席回退 callId（mod32 skillName）。 */
    val name: String,
    /** 指令全文（result 输出；缺席/空白为 null → 行不可展开）。 */
    val output: String?,
    val state: SkillCardState,
    /** ERROR 态首行错误摘要（摘要位替换 name 显示，web errorSummary 同位）。 */
    val errorSummary: String?,
)

/**
 * [Part.Tool]('skill') → [SkillRowModel]（mod32 skillRowModel 同构映射）：
 * - 未结算（Pending/Running）→ RUNNING（渲染层不挂行——SSE 铁律，同台账
 *   「流式进行中不显示」的 part 级对位：结果未到的卡不出现）；
 * - Error 且错误文本含 interrupted → STOPPED（DSH 中断收尾词，web 同判）；
 * - Error → ERROR（errorSummary=错误首行；output=错误全文仍可展开）；
 * - Completed → OK（output=指令全文）。
 */
internal fun skillRowModel(part: Part.Tool): SkillRowModel {
    val error = part.state as? ToolState.Error
    val completed = part.state as? ToolState.Completed
    val state = when {
        error == null && completed == null -> SkillCardState.RUNNING
        error != null && error.error.contains("interrupted", ignoreCase = true) -> SkillCardState.STOPPED
        error != null -> SkillCardState.ERROR
        else -> SkillCardState.OK
    }
    val output = completed?.output?.takeIf { it.isNotBlank() }
        ?: error?.error?.takeIf { it.isNotBlank() }
    return SkillRowModel(
        name = skillDisplayName(part),
        output = output,
        state = state,
        errorSummary = if (state == SkillCardState.ERROR) output?.lineFirst() else null,
    )
}

/** args.name 首行；非字符串/空串/缺席回退 callId（mod32 skillName 语义）。 */
private fun skillDisplayName(part: Part.Tool): String {
    val nameEl: JsonElement? = when (val s = part.state) {
        is ToolState.Pending -> s.input["name"]
        is ToolState.Running -> s.input["name"]
        is ToolState.Completed -> s.input["name"]
        is ToolState.Error -> s.input["name"]
    }
    val name = (nameEl as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
    return (name ?: part.callId.ifEmpty { part.id }).lineFirst()
}

private fun String.lineFirst(): String = substringBefore('\n')
